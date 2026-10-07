package com.autoscript.engine.nodeprocess

import com.autoscript.domain.core.AutojsException
import com.autoscript.domain.engine.EngineId
import com.autoscript.domain.engine.EngineRunRequest
import com.autoscript.domain.engine.EngineStatus
import com.autoscript.domain.engine.RunIdentityIssuer
import com.autoscript.domain.engine.RunIdentityLease
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.fail
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir

class RunIdentityLifecycleTest {
    @TempDir lateinit var dir: Path

    private class Lease : RunIdentityLease {
        override val token = "c".repeat(64)
        var confirmed = false
        var pid: Int? = null
        var alive: (() -> Boolean)? = null
        var drained = false
        var revoked = false
        override fun confirmSpawn(pid: Int?, isAlive: () -> Boolean) { confirmed = true; this.pid = pid; alive = isAlive }
        override fun naturalExit() { drained = true }
        override fun revoke() { revoked = true }
    }

    private class Child(override val pid: Int? = 42) : SpawnedProcess {
        var live = true
        var killed = false
        override val isAlive: Boolean get() = live
        override val stderrTail = ""
        override fun exitValue(): Int? = if (live) null else 0
        override fun destroy() { live = false }
        override fun destroyForcibly() { live = false; killed = true }
        override fun waitFor(timeoutMillis: Long) = !live
    }

    private fun engine(launcher: ProcessLauncher, issuer: RunIdentityIssuer?): NodeProcessEngine {
        val script = dir.resolve("scripts/p1/test.js")
        Files.createDirectories(script.parent)
        Files.write(script, "process.exit(0)".toByteArray())
        return NodeProcessEngine(EngineId(0), NodeEngineConfig(dir, Path.of("node"), hostSocketName = "test-socket"), launcher, issuer)
    }
    private fun request() = EngineRunRequest("p1", "test.js")

    @Test
    fun `票据在spawn前签发，确认PID来自同一进程，noPid不降级`() = runBlocking {
        val lease = Lease()
        var issued = false
        var issuedRun = 0L
        val child = Child(null)
        val e = engine({ _, env, _ ->
            assertTrue(issued)
            assertFalse(lease.confirmed)
            assertEquals(lease.token, env[NodeProcessEngine.ENV_BRIDGE_TOKEN])
            assertEquals(issuedRun.toString(), env[NodeProcessEngine.ENV_RUN_ID])
            child
        }, { _, runId -> issued = true; issuedRun = runId; lease })
        val receipt = e.execute(request())
        assertTrue(lease.confirmed)
        assertNull(lease.pid)
        assertNull(receipt.pid)
        assertEquals(issuedRun, receipt.runId)
        assertTrue(lease.alive!!())
        e.kill()
        assertTrue(lease.revoked)
        assertFalse(lease.alive!!())
    }

    @Test
    fun `spawn异常撤销凭据，在线无issuer在spawn前拒绝`() {
        val lease = Lease()
        val e = engine({ _, _, _ -> throw java.io.IOException("spawn failed") }, { _, _ -> lease })
        assertThrows(AutojsException::class.java) { runBlocking { e.execute(request()) } }
        assertTrue(lease.revoked)
        assertFalse(lease.confirmed)
        val missing = engine({ _, _, _ -> fail<SpawnedProcess>("不许 spawn") }, null)
        assertThrows(AutojsException::class.java) { runBlocking { missing.execute(request()) } }
    }

    @Test
    fun `自然退出后release stop不硬撤销，同槽重跑独立lease`() = runBlocking {
        val first = Child()
        var next = first
        val leases = mutableListOf<Lease>()
        val e = engine({ _, _, _ -> next }, { _, _ -> Lease().also { leases.add(it) } })
        val a = e.execute(request())
        first.live = false
        assertEquals(EngineStatus.STOPPED, e.status())
        e.stop()
        assertTrue(leases[0].drained)
        assertFalse(leases[0].revoked)
        next = Child(43)
        val b = e.execute(request())
        assertNotEquals(a.runId, b.runId)
        e.stop()
        assertTrue(leases[1].revoked)
        assertFalse(leases[0].revoked)
    }

    @Test
    fun `spawn返回交接时取消也回收刚取得的进程与凭据`() = runBlocking {
        val entered = CompletableDeferred<Unit>()
        val release = CountDownLatch(1)
        val lease = Lease()
        val child = Child()
        val e = engine({ _, _, _ ->
            entered.complete(Unit)
            check(release.await(5, TimeUnit.SECONDS))
            child
        }, { _, _ -> lease })
        val job = launch { e.execute(request()) }
        try {
            withTimeout(2_000) { entered.await() }
            job.cancel()
        } finally { release.countDown() }
        withTimeout(2_000) { job.join() }
        assertTrue(lease.revoked)
        assertTrue(child.killed)
        assertFalse(child.isAlive)
    }
}
