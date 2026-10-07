package com.autoscript.bridge

import com.autoscript.domain.bridge.AuthenticatedRunContext
import com.autoscript.domain.bridge.BridgeRequest
import com.autoscript.domain.bridge.BridgeResponse
import com.autoscript.domain.engine.EngineId
import com.autoscript.domain.permission.CapabilityMask
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

class RequestIsolationTest {
    private fun req(id: Long = 1, ttl: Long = 5_000) = BridgeRequest(id, "probe", "m", null, ttl)

    /**
     * 已认证调用方夹具。**掩码给全量**：本类钉的是「请求隔离/连接归属」，不是 A5 授权
     * （授权路径的允许/拒绝矩阵在 `BridgeRouterTest` 的 mask 用例里）—— 这里若给窄掩码，
     * 被拒的原因就变成授权而非隔离，断言会指着错的东西。
     */
    private fun caller(id: Long) = AuthenticatedRunContext(EngineId(0), id, id, CapabilityMask.ALL)

    @Test
    fun `双连接同时用正负请求号不会冲突，关闭一条不影响另一条`() = runBlocking {
        for (id in listOf(1L, -1L)) {
            val registry = RequestRegistry()
            BridgeRouter(registry).use { router ->
                val entered = ChannelSignal(2)
                val gate = CompletableDeferred<Unit>()
                router.register("probe") { request -> entered.mark(); gate.await(); BridgeResponse.Ok(request.id, null) }
                val a = async(caller(1)) { router.dispatch(req(id)) }
                val b = async(caller(2)) { router.dispatch(req(id)) }
                withTimeout(2_000) { entered.done.await() }
                assertEquals(2, registry.size())
                router.closeConnection(1)
                assertEquals("ERR_ENGINE_STOPPED", (a.await() as BridgeResponse.Err).errorCode)
                gate.complete(Unit)
                assertEquals(BridgeResponse.Ok(id, null), b.await())
                assertEquals(0, registry.size())
            }
        }
        Unit
    }

    @Test
    fun `旧ticket迟到不能清掉复用id的新请求，过期只结算一次`() {
        val clock = FakeClock()
        val registry = RequestRegistry(clock)
        var settled = 0
        val old = requireNotNull(registry.register(1, req(ttl = 10)) { settled++ })
        clock.now = 10
        assertEquals(1, registry.expireDue())
        val fresh = requireNotNull(registry.register(1, req()) { settled++ })
        assertFalse(registry.complete(old, BridgeResponse.Ok(1, null)))
        assertFalse(registry.cancel(old))
        assertTrue(registry.isRegistered(1, 1))
        assertTrue(registry.complete(fresh, BridgeResponse.Ok(1, null)))
        assertEquals(2, settled)
    }

    @Test
    fun `收割TTL会取消handler，取消调用也立即清账`() = runBlocking {
        val clock = FakeClock()
        val registry = RequestRegistry(clock)
        BridgeRouter(registry).use { router ->
            val entered = CompletableDeferred<Unit>()
            val cancelled = CompletableDeferred<Unit>()
            router.register("probe") {
                entered.complete(Unit)
                try { awaitCancellation() } finally { cancelled.complete(Unit) }
            }
            val request = async(caller(1)) { router.dispatch(req()) }
            entered.await()
            clock.now = 5_000
            registry.expireDue()
            assertEquals("ERR_TIMEOUT", (withTimeout(2_000) { request.await() } as BridgeResponse.Err).errorCode)
            assertTrue(cancelled.isCompleted)
            val next = async(caller(1), start = CoroutineStart.UNDISPATCHED) { router.dispatch(req()) }
            next.cancelAndJoin()
            assertEquals(0, registry.size())
        }
        Unit
    }

    @Test
    fun `handler自行取消不会悬挂调用且同连接重复id仍拒绝`() = runBlocking {
        val registry = RequestRegistry()
        BridgeRouter(registry).use { router ->
            val entered = CompletableDeferred<Unit>()
            val gate = CompletableDeferred<Unit>()
            router.register("probe") {
                entered.complete(Unit)
                gate.await()
                throw CancellationException("handler stopped")
            }
            val first = async(caller(1)) { router.dispatch(req()) }
            withTimeout(2_000) { entered.await() }
            val duplicate = withContext(caller(1)) { router.dispatch(req()) } as BridgeResponse.Err
            assertEquals("ERR_INVALID_PARAM", duplicate.errorCode)
            gate.complete(Unit)
            withTimeout(2_000) { first.join() }
            assertTrue(first.isCancelled)
            assertEquals(0, registry.size())
        }
        Unit
    }

    @Test
    fun `console 无身份拒绝，payload 自报归属拒绝，宿主直写仍为0`() = runBlocking {
        val collector = ConsoleCollector()
        val request = BridgeRequest(1, "console", "log", """{"level":"log","text":"hello"}""", 5_000)
        assertEquals("ERR_PERMISSION_DENIED", (collector.handle(request) as BridgeResponse.Err).errorCode)
        withContext(caller(7)) {
            val forged = request.copy(payload = """{"level":"log","text":"x","runId":0}""")
            assertEquals("ERR_INVALID_PARAM", (collector.handle(forged) as BridgeResponse.Err).errorCode)
            assertTrue(collector.handle(request) is BridgeResponse.Ok)
        }
        collector.append(0, "info", "host")
        assertEquals(listOf(7L, 0L), collector.drain(0).second.map { it.runId })
    }

    private class ChannelSignal(private val count: Int) {
        private val received = java.util.concurrent.atomic.AtomicInteger()
        val done = CompletableDeferred<Unit>()
        fun mark() { if (received.incrementAndGet() == count) done.complete(Unit) }
    }
}
