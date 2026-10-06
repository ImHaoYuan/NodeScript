package com.autoscript.bridge

import com.autoscript.domain.automation.InputChannelSession
import com.autoscript.domain.bridge.BridgeResponse
import com.autoscript.domain.core.ErrorCode
import java.io.BufferedInputStream
import java.io.InputStream
import java.io.OutputStream
import java.net.ServerSocket
import java.net.Socket
import java.nio.charset.StandardCharsets
import kotlin.coroutines.cancellation.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.launch

/**
 * newline-delimited JSON frame 传输服务端（docs §7.5）。
 *
 * 与 `bridge/js` 的 `SocketBootstrap` 双侧对齐：
 * - 一行一帧：请求 `{"t":"req",...}\n` → [BridgeRouter.dispatch] → 响应 `{"t":"ok"|"err",...}\n` 回写；
 * - 每行独立协程 dispatch，响应按 requestId 关联（允许乱序回包，客户端按 id 结算）；
 * - 在途帧数**有界**（[maxInFlight]）：到顶就压住读循环（背压），不无限起协程 ——
 *   一帧一个协程没有上限时，对端狂发小帧就能把进程堆栈/内存吃光；
 * - 非法帧（超 [maxFrameBytes] / 非法 JSON / 非请求信封）→ 记 [onProtocolError]；
 *   **取得到信封 id 就回错误帧**（否则对端要干等到 TTL 才醒），取不到才丢弃；
 *   连接保持（除超限帧：直接关连接，防内存吞噬）；
 * - EOF 即正常结束；[close] 取消全部在途任务并关闭监听。
 *
 * **每连接一个 [InputChannelSession]**（2026-10-06）：会话级状态（当前输入通道）挂在
 * **连接**上而不是 handler 上 —— handler 是全局单例、所有脚本共用，字段级会话态会让
 * 一个脚本设的通道漏给另一个。连接内的帧共享同一个会话对象（`setInputChannel` 之后
 * 本连接的后续调用都生效），连接之间天然不共享。
 *
 * 传输介质由调用方决定：桌面/CI 走 loopback TCP（`ServerSocket(0)`），设备上走 unix domain socket
 *（`ServerSocketChannel.bind(UnixDomainSocketAddress)`，同一 read/write 路径）。
 */
class NewlineFrameServer(
    private val router: BridgeRouter,
    private val transport: JsonTransport = JsonTransport(),
    private val scope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.IO),
    private val maxFrameBytes: Int = DEFAULT_MAX_FRAME_BYTES,
    private val maxInFlight: Int = DEFAULT_MAX_IN_FLIGHT,
    private val onProtocolError: (reason: String) -> Unit = {},
) : AutoCloseable {

    /** 在途帧配额：读循环在起协程**之前**取，取不到就挂起 —— 背压，不是排队。 */
    private val inFlight = Semaphore(maxInFlight)

    /** 监听循环：每个 accept 起一个连接任务。返回监听 Job（取消即停）。 */
    fun acceptLoop(serverSocket: ServerSocket): Job = scope.launch {
        while (true) {
            val socket = try {
                serverSocket.accept()
            } catch (_: Exception) {
                break // 监听关闭
            }
            serveConnection(socket)
        }
    }

    /** 服务一条连接：读帧 → 并发 dispatch → 回写。返回读循环 Job（EOF 即正常结束）。 */
    fun serveConnection(socket: Socket): Job = scope.launch {
        socket.use { s ->
            readLoop(s.getInputStream(), s.getOutputStream(), InputChannelSession())
        }
    }

    /**
     * 流版本（测试/自定义传输直接用；与 socket 版本同一语义）。
     *
     * [session] 由调用方给：生产是每连接新建（见类 KDoc），测试可以传一个预置了通道的
     * 会话来验「同一连接内共享」。**默认值只服务「不关心会话」的调用方** —— 传默认值时
     * 每条连接仍是各自新建，不会互相串。
     */
    fun serveConnection(
        input: InputStream,
        output: OutputStream,
        session: InputChannelSession = InputChannelSession(),
    ): Job = scope.launch {
        readLoop(input, output, session)
    }

    /**
     * 读循环：读一帧起一个 server-scope 协程 dispatch（慢请求不挡快请求，响应按 id 关联可乱序）。
     * 帧任务挂在 server scope 而非连接 Job 下：EOF 关连接时在途 dispatch 不被连带取消
     *（写回失败即对端已走，丢弃）。
     */
    private suspend fun readLoop(input: InputStream, output: OutputStream, session: InputChannelSession) {
        val buffered = BufferedInputStream(input)
        while (true) {
            val frame = try {
                readFrame(buffered)
            } catch (e: FrameTooLargeException) {
                onProtocolError("帧超过上限 ${maxFrameBytes} 字节")
                break // 关连接，防内存吞噬
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {
                break
            } ?: break // EOF：对端正常关闭
            // 每帧独立 server-scope 协程（scope.launch 非连接 Job.launch）：
            // 慢请求不挡快请求，响应按 id 关联可乱序；EOF 关连接时在途 dispatch 不被连带取消。
            // 配额在**起协程前**取：满了就挂起读循环（背压），在途帧数因此永远 ≤ maxInFlight。
            inFlight.acquire()
            // session 作为上下文元素进**每一帧**的协程：连接内的帧共享同一个会话对象
            //（setInputChannel 之后本连接后续调用都生效），连接之间不共享。
            scope.launch(session) {
                try {
                    handleFrame(frame, output)
                } finally {
                    inFlight.release()
                }
            }
        }
    }

    /** 单帧：解码 → dispatch → 回包。**任何**解码/dispatch 失败都尽量回错误帧而不是丢弃。 */
    private suspend fun handleFrame(frame: ByteArray, output: OutputStream) {
        val request = try {
            transport.decodeRequest(frame)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            onProtocolError("非法请求帧: ${e.message}")
            // 帧坏了，但信封 id 常还在（缺字段/值型不对才抛）—— 取得到就回错误帧，
            // 否则对端要等满 TTL 才醒，看起来像"服务端没反应"。取不到（非 JSON）只能丢。
            replyOrDrop(transport.probeRequestId(frame)?.let { id ->
                BridgeResponse.Err(id, ErrorCode.ERR_INVALID_PARAM.code, "非法请求帧: ${e.message}")
            }, output)
            return
        }
        val response = try {
            router.dispatch(request)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            // dispatch 自己已把 handler 异常折成 Err（含 ERR_TIMEOUT）；走到这里说明是
            // 桥内部的漏网异常，仍按 Router 的老口径（ERR_INVALID_PARAM）回，不静默丢包。
            onProtocolError("dispatch 异常: ${e.message}")
            BridgeResponse.Err(request.id, ErrorCode.ERR_INVALID_PARAM.code, "dispatch 异常: ${e.message}")
        }
        replyOrDrop(response, output)
    }

    /** 写回一帧；`null` 或写失败（对端已走）即丢弃 —— 写不出去不该炸掉 server。 */
    private fun replyOrDrop(response: BridgeResponse?, output: OutputStream) {
        if (response == null) return
        val line = transport.encodeResponse(response) + LF
        synchronized(output) {
            try {
                output.write(line)
                output.flush()
            } catch (_: Exception) {
                // 丢弃，不抛
            }
        }
    }

    /**
     * 读一帧（到 `\n` 为止，不含换行）。返回 null = EOF（无任何字节）。
     * 超 [maxFrameBytes] 抛 [FrameTooLargeException]；`\r\n` 兼容（去尾 `\r`）。
     */
    private fun readFrame(input: InputStream): ByteArray? {
        // 手工逐字节读：BufferedReader.readLine 无长度上限，恶意巨帧会吞内存。
        var buf = ByteArray(256)
        var len = 0
        while (true) {
            val b = input.read()
            if (b == -1) return if (len == 0) null else buf.copyOf(len).trimCr()
            if (b == '\n'.code) return buf.copyOf(len).trimCr()
            if (len + 1 > maxFrameBytes) throw FrameTooLargeException()
            if (len == buf.size) buf = buf.copyOf(buf.size * 2)
            buf[len++] = b.toByte()
        }
    }

    private fun ByteArray.trimCr(): ByteArray =
        if (isNotEmpty() && last() == '\r'.code.toByte()) copyOf(size - 1) else this

    override fun close() {
        scope.cancel()
    }

    companion object {
        /**
         * 单帧上限。§7.5 的口径是**控制面结构化小对象**（大二进制走 side-channel，不过 JSON），
         * 8MB 已覆盖最肥的合法帧（整屏 a11y 树 dump）并留足余量；64MB 那个值是「没人想过」的
         * 默认，一帧一协程无界时它等于「单帧能吃掉 64MB 堆」。两侧同值：`bridge/js` 的
         * `DEFAULT_MAX_FRAME`（`bootstrap.ts`）必须一起改。
         */
        const val DEFAULT_MAX_FRAME_BYTES: Int = 8 * 1024 * 1024

        /** 在途帧上限：超出的连接被背压压住读循环，而不是继续起协程。 */
        const val DEFAULT_MAX_IN_FLIGHT: Int = 256
    }
}

class FrameTooLargeException : IllegalStateException("帧超过上限")

private operator fun ByteArray.plus(other: ByteArray): ByteArray {
    val out = ByteArray(size + other.size)
    copyInto(out)
    other.copyInto(out, size)
    return out
}

private val LF = "\n".toByteArray(StandardCharsets.UTF_8)
