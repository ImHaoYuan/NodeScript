package com.autoscript.bridge

import com.autoscript.domain.bridge.AuthenticatedRunContext
import com.autoscript.domain.bridge.BridgeRequest
import com.autoscript.domain.bridge.BridgeResponse
import com.autoscript.domain.engine.EngineId
import com.autoscript.domain.permission.CapabilityMask
import com.autoscript.domain.permission.ScriptAuthorizationSnapshot
import com.autoscript.domain.engine.RunIdentityLease
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.io.OutputStream
import java.io.IOException
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

class ConnectionLifecycleTest {
    private val transport = JsonTransport()
    private fun req(id: Long = 1) = transport.encodeRequest(BridgeRequest(id, "probe", "m", null, 5_000)) + byteArrayOf(10)
    /** 连接生命周期用例与授权档无关：快照给全量，授权语义在别处钉。 */
    private fun issue(registry: RunIdentityRegistry, run: Long = 1) = registry.issue(
        EngineId(0), run, "lifecycle-probe",
        ScriptAuthorizationSnapshot(mask = CapabilityMask.ALL),
    ).also {
        it.confirmSpawn(null) { true }
    }
    private fun lines(out: ByteArrayOutputStream): String = synchronized(out) { out.toString(Charsets.UTF_8) }

    @Test
    fun `无hello旧客户端和伪造票据不触发业务handler`() = runBlocking {
        RunIdentityRegistry().use { identities ->
            val registry = RequestRegistry()
            BridgeRouter(registry).use { router ->
                var calls = 0
                router.register("probe") { calls++; BridgeResponse.Ok(it.id, null) }
                NewlineFrameServer(router, identities).use { server ->
                    for (bytes in listOf(req(), BridgeHandshake.hello("0".repeat(64)) + req())) {
                        val output = ByteArrayOutputStream()
                        withTimeout(2_000) { server.serveConnection(ByteArrayInputStream(bytes), output).join() }
                        assertFalse(lines(output).contains("\"t\":\"ok\""))
                    }
                    assertEquals(0, calls)
                    assertEquals(0, registry.size())
                }
            }
        }
        Unit
    }

    @Test
    fun `EOF 后仍等已受理请求排完才释放IO`() = runBlocking {
        RunIdentityRegistry().use { identities ->
            BridgeRouter(RequestRegistry()).use { router ->
                val entered = CompletableDeferred<Unit>()
                val gate = CompletableDeferred<Unit>()
                router.register("probe") { entered.complete(Unit); gate.await(); BridgeResponse.Ok(it.id, null) }
                NewlineFrameServer(router, identities).use { server ->
                    val out = ByteArrayOutputStream()
                    var closed = false
                    val lease = issue(identities)
                    val job = server.serveConnection(ByteArrayInputStream(BridgeHandshake.hello(lease.token) + req()), out,
                        closeConnection = { closed = true })
                    withTimeout(2_000) { entered.await() }
                    assertFalse(job.isCompleted)
                    assertFalse(closed)
                    gate.complete(Unit)
                    withTimeout(2_000) { job.join() }
                    assertTrue(lines(out).contains("\"t\":\"ok\""))
                    assertTrue(closed)
                    assertEquals(0, identities.size())
                }
            }
        }
        Unit
    }

    @Test
    fun `自然退出先于尾帧读取仍保留原归属，同槽新执行不串`() = runBlocking {
        RunIdentityRegistry().use { identities ->
            BridgeRouter(RequestRegistry()).use { router ->
                val seen = java.util.concurrent.CopyOnWriteArrayList<Long>()
                val first = CompletableDeferred<Unit>()
                router.register("probe") {
                    seen.add(currentCoroutineContext()[AuthenticatedRunContext]!!.engineRunId)
                    first.complete(Unit)
                    BridgeResponse.Ok(it.id, null)
                }
                NewlineFrameServer(router, identities).use { server ->
                    val lease = issue(identities, 11)
                    val out = ByteArrayOutputStream()
                    // 第一段含完整 hello+请求；第二段模拟还没被 reader 取出的 socket 尾帧。
                    val input = GatedInput(BridgeHandshake.hello(lease.token) + req(), req(2))
                    val old = server.serveConnection(input, out, closeConnection = input::close)
                    withTimeout(2_000) { first.await(); input.waiting.await() }
                    lease.naturalExit(); lease.naturalExit()
                    val fresh = issue(identities, 12)
                    val other = server.serveConnection(ByteArrayInputStream(BridgeHandshake.hello(fresh.token) + req()), ByteArrayOutputStream())
                    withTimeout(2_000) { other.join() }
                    input.release.countDown()
                    withTimeout(2_000) { old.join() }
                    assertEquals(listOf(11L, 12L, 11L), seen)
                    assertTrue(lines(out).contains("\"id\":2"))
                }
            }
        }
        Unit
    }

    @Test
    fun `对端不再收响应时仍排完已发送的尾帧`() = runBlocking {
        RunIdentityRegistry().use { identities ->
            val registry = RequestRegistry()
            BridgeRouter(registry).use { router ->
                val seen = java.util.concurrent.CopyOnWriteArrayList<Long>()
                router.register("probe") {
                    seen.add(currentCoroutineContext()[AuthenticatedRunContext]!!.engineRunId)
                    BridgeResponse.Ok(it.id, null)
                }
                NewlineFrameServer(router, identities).use { server ->
                    val lease = issue(identities, 81)
                    val input = GatedInput(BridgeHandshake.hello(lease.token) + req(), req(2))
                    val failedWrite = CompletableDeferred<Unit>()
                    val output = object : OutputStream() {
                        var first = true
                        override fun write(value: Int) = error("只接完整帧")
                        override fun write(bytes: ByteArray, off: Int, len: Int) {
                            if (first) {
                                first = false // hello ACK 成功，此后模拟对端退出后 write EPIPE。
                                return
                            }
                            failedWrite.complete(Unit)
                            throw IOException("peer closed output")
                        }
                    }
                    val job = server.serveConnection(input, output, closeConnection = input::close)
                    withTimeout(2_000) { failedWrite.await(); input.waiting.await() }
                    lease.naturalExit()
                    input.release.countDown()
                    withTimeout(2_000) { job.join() }
                    assertEquals(listOf(81L, 81L), seen)
                    assertEquals(0, registry.size())
                    assertEquals(0, identities.size())
                    assertTrue(input.closed)
                }
            }
        }
        Unit
    }

    @Test
    fun `排空超时取消handler并清表，重复naturalExit不延长期限`() = runBlocking {
        RunIdentityRegistry().use { identities ->
            val registry = RequestRegistry()
            BridgeRouter(registry).use { router ->
                val entered = CompletableDeferred<Unit>()
                val cancelled = CompletableDeferred<Unit>()
                router.register("probe") {
                    entered.complete(Unit)
                    try { awaitCancellation() } finally { cancelled.complete(Unit) }
                }
                NewlineFrameServer(router, identities, drainMillis = 50).use { server ->
                    val lease = issue(identities)
                    val input = GatedInput(BridgeHandshake.hello(lease.token) + req(), byteArrayOf())
                    val job = server.serveConnection(input, ByteArrayOutputStream(), closeConnection = input::close)
                    withTimeout(2_000) { entered.await() }
                    lease.naturalExit()
                    withTimeout(2_000) { job.join(); cancelled.await() }
                    assertTrue(job.isCancelled)
                    assertEquals(0, registry.size())
                    assertTrue(input.closed)
                }
            }
        }
        Unit
    }

    @Test
    fun `preauth超时和壳关闭都显式唤醒阻塞read`() = runBlocking {
        for (closeServer in listOf(false, true)) {
            RunIdentityRegistry().use { identities ->
                BridgeRouter(RequestRegistry()).use { router ->
                    NewlineFrameServer(router, identities, handshakeMillis = if (closeServer) 5_000 else 50).use { server ->
                        val input = GatedInput(byteArrayOf(), byteArrayOf())
                        val job = server.serveConnection(input, ByteArrayOutputStream(), closeConnection = input::close)
                        withTimeout(2_000) { input.waiting.await() }
                        if (closeServer) server.close()
                        withTimeout(2_000) { job.join() }
                        assertTrue(input.closed)
                        assertTrue(job.isCancelled)
                    }
                }
            }
        }
        Unit
    }

    @Test
    fun `硬撤销当前连接取消handler，不撤掉其他已认证执行`() = runBlocking {
        RunIdentityRegistry().use { identities ->
            val registry = RequestRegistry()
            BridgeRouter(registry).use { router ->
                val entered = CompletableDeferred<Unit>()
                router.register("probe") {
                    if (currentCoroutineContext()[AuthenticatedRunContext]!!.engineRunId == 1L) {
                        entered.complete(Unit); awaitCancellation()
                    }
                    BridgeResponse.Ok(it.id, null)
                }
                NewlineFrameServer(router, identities).use { server ->
                    val a = issue(identities)
                    val input = GatedInput(BridgeHandshake.hello(a.token) + req(), byteArrayOf())
                    val old = server.serveConnection(input, ByteArrayOutputStream(), closeConnection = input::close)
                    withTimeout(2_000) { entered.await() }
                    a.revoke()
                    withTimeout(2_000) { old.join() }
                    val b = issue(identities, 2)
                    val output = ByteArrayOutputStream()
                    withTimeout(2_000) {
                        server.serveConnection(ByteArrayInputStream(BridgeHandshake.hello(b.token) + req()), output).join()
                    }
                    assertTrue(input.closed)
                    assertTrue(lines(output).contains("\"t\":\"ok\""))
                    assertEquals(0, registry.size())
                }
            }
        }
        Unit
    }

    /** 阻塞行为与 socket 同型，close 真能解阻塞；每次最多读第一段，避免 BufferedInputStream 提前吃尾帧。 */
    private class GatedInput(first: ByteArray, tail: ByteArray) : InputStream() {
        private val head = ByteArrayInputStream(first)
        private val rest = ByteArrayInputStream(tail)
        val release = CountDownLatch(1)
        val waiting = CompletableDeferred<Unit>()
        @Volatile var closed = false
        override fun read(bytes: ByteArray, off: Int, len: Int): Int {
            if (closed) return -1
            if (head.available() > 0) return head.read(bytes, off, len)
            waiting.complete(Unit)
            check(release.await(5, TimeUnit.SECONDS)) { "测试尾帧门未释放" }
            return if (closed) -1 else rest.read(bytes, off, len)
        }
        override fun read(): Int = ByteArray(1).let { if (read(it, 0, 1) < 0) -1 else it[0].toInt() and 255 }
        override fun close() { closed = true; release.countDown() }
    }
}
