package com.autoscript.bridge

import com.autoscript.domain.automation.InputChannelSession
import com.autoscript.domain.bridge.BridgeResponse
import com.autoscript.domain.core.AutojsException
import com.autoscript.domain.core.ErrorCode
import java.io.BufferedInputStream
import java.io.InputStream
import java.io.OutputStream
import java.net.ServerSocket
import java.net.Socket
import java.util.concurrent.atomic.AtomicLong
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.supervisorScope
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.sync.Semaphore

/**
 * newline 帧接入（§7.5）：hello 绑定执行后才分发，handler 表共享、请求账按连接隔离。
 * 每连接持有真实 IO 关闭器：单纯 cancel 无法唤醒阻塞 socket.read。EOF 后先排完已接收请求，
 * 自然退出保留原归属读取尾帧；主动撤销立即关 IO。全局在途配额仍在起请求协程之前取。
 */
class NewlineFrameServer(
    val router: BridgeRouter,
    val identities: RunIdentityRegistry,
    private val transport: JsonTransport = JsonTransport(),
    private val scope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.IO),
    private val maxFrameBytes: Int = DEFAULT_MAX_FRAME_BYTES,
    private val maxInFlight: Int = DEFAULT_MAX_IN_FLIGHT,
    private val handshakeMillis: Long = BridgeHandshake.TIMEOUT_MILLIS,
    private val drainMillis: Long = 5_000,
    private val onProtocolError: (reason: String) -> Unit = {},
) : AutoCloseable {
    private val lock = Any()
    private val connections = HashSet<Connection>()
    private val listeners = HashSet<ServerSocket>()
    private var closed = false
    private val inFlight = Semaphore(maxInFlight)
    private val handshakes = Semaphore(32)

    init { require(maxFrameBytes > 0 && maxInFlight > 0 && handshakeMillis > 0 && drainMillis > 0) }

    fun acceptLoop(serverSocket: ServerSocket): Job {
        synchronized(lock) {
            if (closed) serverSocket.close() else listeners.add(serverSocket)
        }
        return scope.launch {
            try {
                while (isActive) {
                    val socket = try { serverSocket.accept() } catch (_: Exception) { break }
                    serveConnection(socket)
                }
            } finally {
                serverSocket.close()
                synchronized(lock) { listeners.remove(serverSocket) }
            }
        }
    }

    fun serveConnection(socket: Socket): Job = serveConnection(
        socket.getInputStream(), socket.getOutputStream(), closeConnection = { socket.close() },
    )

    /** 关闭器在 launch 前登记：pre-auth、阻塞读、排空中的连接都必须可由 close 唤醒。 */
    fun serveConnection(
        input: InputStream,
        output: OutputStream,
        session: InputChannelSession = InputChannelSession(),
        peerPid: Int? = null,
        closeConnection: () -> Unit = { try { input.close() } finally { output.close() } },
    ): Job {
        val conn = Connection(closeConnection)
        synchronized(lock) {
            if (closed) conn.abort() else connections.add(conn)
        }
        val job = scope.launch { conn.serve(input, output, session, peerPid) }
        job.invokeOnCompletion { conn.dispose() }
        return job
    }

    private inner class Connection(private val closer: () -> Unit) {
        val id = connectionIds.getAndIncrement()
        private val stateLock = Any()
        private var ended = false
        private var draining = false
        private var owner: Job? = null
        private var drainTimer: Job? = null
        private var binding: RunIdentityRegistry.Binding? = null

        fun abort() {
            val job = synchronized(stateLock) { ended = true; owner }
            // 先取消投递域，再立即关闭 IO（取消回调也会 close）；绝不等 job 完成才关 fd。
            // 若先 close 再 cancel，EOF 可抢先唤醒 reader，把硬撤销误走成自然排空。
            job?.cancel()
            runCatching { closer() }
            router.closeConnection(id)
        }

        private fun beginDrain() = synchronized(stateLock) {
            if (!draining && !ended) {
                draining = true
                drainTimer = scope.launch { delay(drainMillis); abort() }
            }
        }

        suspend fun serve(input: InputStream, output: OutputStream, session: InputChannelSession, peerPid: Int?) {
            val current = currentCoroutineContext()[Job]!!
            synchronized(stateLock) { owner = current; if (ended) current.cancel() }
            currentCoroutineContext().ensureActive()
            val buffered = BufferedInputStream(input)
            val timeout = scope.launch { delay(handshakeMillis); abort() }
            var permit = false
            try {
                if (!handshakes.tryAcquire()) return
                permit = true
                val hello = readBlocking { readFrame(buffered, BridgeHandshake.MAX_BYTES) } ?: return
                val token = try { BridgeHandshake.decodeHello(hello) } catch (_: Exception) {
                    writeBlocking(output, BridgeHandshake.error(ErrorCode.ERR_PERMISSION_DENIED))
                    return
                }
                val bound = try {
                    identities.authenticate(token, peerPid, id, ::beginDrain, ::abort)
                } catch (e: CancellationException) { throw e } catch (_: AutojsException) {
                    writeBlocking(output, BridgeHandshake.error(ErrorCode.ERR_PERMISSION_DENIED))
                    return
                }
                binding = bound
                writeBlocking(output, BridgeHandshake.ack())
                timeout.cancel()
                handshakes.release()
                permit = false
                // 返回的 Job 包含读循环 + 全部请求排空，不能 EOF 就先关闭 output。
                supervisorScope {
                    while (isActive) {
                        val frame = readBlocking { readFrame(buffered, maxFrameBytes) } ?: break
                        inFlight.acquire()
                        val requestJob = launch(bound.caller + session, start = CoroutineStart.LAZY) {
                            handleFrame(frame, output)
                        }
                        // 取消发生在 launch 前/首次调度前也归还许可证。
                        requestJob.invokeOnCompletion { inFlight.release() }
                        requestJob.start()
                    }
                    beginDrain()
                }
            } catch (e: CancellationException) {
                throw e
            } catch (_: FrameTooLargeException) {
                onProtocolError("桥帧超过上限")
            } catch (_: Exception) {
                // 断链/关闭；不记录原始 hello 或异常文本，以免 token 被诊断回显。
                onProtocolError("桥连接 IO 结束")
            } finally {
                timeout.cancel()
                if (permit) handshakes.release()
                dispose()
            }
        }

        /** suspend 的取消回调直接 close IO，读线程由 IO 域排水退出，不挂死关闭调用方。 */
        private suspend fun <T> readBlocking(action: () -> T): T = suspendCancellableCoroutine { continuation ->
            val worker = scope.launch(Dispatchers.IO) {
                val result = runCatching(action)
                continuation.resumeWith(result)
            }
            continuation.invokeOnCancellation { runCatching { closer() }; worker.cancel() }
        }

        private suspend fun writeBlocking(output: OutputStream, bytes: ByteArray) = readBlocking {
            synchronized(output) { output.write(bytes); output.flush() }
        }

        private suspend fun handleFrame(frame: ByteArray, output: OutputStream) {
            try {
                val request = try {
                    transport.decodeRequest(frame)
                } catch (e: CancellationException) {
                    throw e
                } catch (_: Exception) {
                    onProtocolError("非法请求帧")
                    transport.probeRequestId(frame)?.let {
                        val error = BridgeResponse.Err(it, ErrorCode.ERR_INVALID_PARAM.code, "非法请求帧")
                        writeBlocking(output, transport.encodeResponse(error) + LF)
                    }
                    return
                }
                val response = router.dispatch(request)
                writeBlocking(output, transport.encodeResponse(response) + LF)
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {
                // 对端自然退出后无法回 ACK，不等于已送来的尾帧作废；有界读至 EOF 并继续排空。
                beginDrain()
            }
        }

        fun dispose() {
            synchronized(stateLock) { ended = true; drainTimer?.cancel() }
            binding?.close()
            router.closeConnection(id)
            runCatching { closer() }
            synchronized(lock) { connections.remove(this) }
        }
    }

    private fun readFrame(input: InputStream, limit: Int): ByteArray? {
        var buf = ByteArray(minOf(256, limit))
        var length = 0
        while (true) {
            val b = input.read()
            if (b == -1) return null // 未以换行结尾的半帧不是已接收请求。
            if (b == '\n'.code) {
                val size = if (length > 0 && buf[length - 1] == '\r'.code.toByte()) length - 1 else length
                return buf.copyOf(size)
            }
            if (length >= limit) throw FrameTooLargeException()
            if (length == buf.size) buf = buf.copyOf(minOf(limit, buf.size * 2))
            buf[length++] = b.toByte()
        }
    }

    override fun close() {
        val all = synchronized(lock) {
            if (closed) return
            closed = true
            listeners.forEach { runCatching { it.close() } }
            listeners.clear()
            connections.toList()
        }
        identities.close()
        all.forEach { it.abort() }
        scope.cancel()
    }

    companion object {
        const val DEFAULT_MAX_FRAME_BYTES = 8 * 1024 * 1024
        const val DEFAULT_MAX_IN_FLIGHT = 256
        private val connectionIds = AtomicLong(1)
        private val LF = byteArrayOf(10)
    }
}

class FrameTooLargeException : IllegalStateException("帧超过上限")
