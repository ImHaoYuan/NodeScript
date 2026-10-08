package com.autoscript.bridge

import com.autoscript.domain.bridge.AuthenticatedRunContext
import com.autoscript.domain.bridge.BridgeRequest
import com.autoscript.domain.bridge.BridgeResponse
import com.autoscript.domain.engine.EngineId
import com.autoscript.domain.permission.CapabilityMask
import com.autoscript.domain.permission.ScriptAuthorizationSnapshot
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * 连接级资源收口在**真实连接生命周期**上的接线（§7.5 × §9.2）。
 *
 * 这条缝要堵的病是"脚本崩了但投屏还挂着"：投屏会话是进程级单资源，脚本进程没了、
 * 连接断了、执行被停了，它都不该活着（通知栏一条"正在投屏"、`VirtualDisplay` 占着
 * 编码器，却没有任何脚本在跑）。收口点必须在连接层，且要覆盖**全部**结束方式：
 *
 * - 硬撤销（对端断开 / 宿主 `close()`）；
 * - **按连接号从外部撤销**（脚本崩了/被看门狗掐了 —— 调用方手上只有连接号）；
 * - 正常退出（EOF 排空完，脚本跑完是最常见的一种）。
 *
 * 本文件钉接入端那一侧：注册表挂在连接身份上、每条连接一份、谁先到都恰好收一次、
 * 两条连接互不牵连。注册表本身的四条契约由 `ConnectionResourceRegistryTest` 钉；
 * 会话侧怎么还资源由 `:platform:capabilities` 的用例钉。
 */
class ConnectionResourceLifecycleTest {

    private val transport = JsonTransport()

    private fun req(id: Long = 1) =
        transport.encodeRequest(BridgeRequest(id, "probe", "m", null, 5_000)) + byteArrayOf(10)

    private fun issue(registry: RunIdentityRegistry, run: Long = 1) =
        registry.issue(EngineId(0), run, "resource-probe",
            ScriptAuthorizationSnapshot(mask = CapabilityMask.ALL)).also { it.confirmSpawn(null) { true } }

    /** 每条连接各自收掉的资源次数（按连接号记账 —— 隔离与否看这个）。 */
    private val closedByConnection = ConcurrentHashMap<Long, AtomicInteger>()

    private fun record(ctx: AuthenticatedRunContext) {
        ctx.resources.register {
            closedByConnection.computeIfAbsent(ctx.connectionId) { AtomicInteger() }.incrementAndGet()
        }
    }

    private fun closed(connectionId: Long): Int = closedByConnection[connectionId]?.get() ?: 0

    @Test
    fun `硬撤销 —— 这条连接上的资源恰好收一次`() = runBlocking {
        RunIdentityRegistry().use { identities ->
            BridgeRouter(RequestRegistry()).use { router ->
                val entered = CompletableDeferred<Long>()
                router.register("probe") {
                    val ctx = currentCoroutineContext()[AuthenticatedRunContext]!!
                    record(ctx)
                    entered.complete(ctx.connectionId)
                    // 挂住不返回：连接在请求在途时被撤销（对端断开 / 宿主 close）。
                    awaitCancellation()
                }
                NewlineFrameServer(router, identities).use { srv ->
                    val lease = issue(identities)
                    val input = ByteArrayInputStream(BridgeHandshake.hello(lease.token) + req())
                    val job = srv.serveConnection(input, ByteArrayOutputStream(), closeConnection = input::close)
                    val connectionId = withTimeout(2_000) { entered.await() }
                    withTimeout(2_000) { job.cancel() }
                    job.join()
                    assertEquals(1, closed(connectionId), "撤销必须收掉这条连接上的资源，且只收一次")
                }
            }
        }
        Unit
    }

    @Test
    fun `按连接号从外部撤销 —— 脚本崩了或被掐了那条路也收口`() = runBlocking {
        RunIdentityRegistry().use { identities ->
            BridgeRouter(RequestRegistry()).use { router ->
                val entered = CompletableDeferred<Long>()
                router.register("probe") {
                    val ctx = currentCoroutineContext()[AuthenticatedRunContext]!!
                    record(ctx)
                    entered.complete(ctx.connectionId)
                    awaitCancellation()
                }
                NewlineFrameServer(router, identities).use { srv ->
                    val lease = issue(identities)
                    val input = ByteArrayInputStream(BridgeHandshake.hello(lease.token) + req())
                    val job = srv.serveConnection(input, ByteArrayOutputStream(), closeConnection = input::close)
                    val connectionId = withTimeout(2_000) { entered.await() }
                    // 调用方手上只有连接号（看门狗掐执行 / 宿主停脚本）—— 资源必须当场收掉，
                    // 而不是等 socket 自己断。
                    srv.abortConnection(connectionId)
                    assertEquals(1, closed(connectionId), "按号撤销要立刻收口")
                    // 随后连接自己结束：同一份资源不许被收第二次（旧会话的迟到收口
                    // 会把新会话的资源也拆掉 —— 这正是"恰好一次"要挡的）。
                    withTimeout(2_000) { job.cancel() }
                    job.join()
                    assertEquals(1, closed(connectionId), "收口是幂等的：连接结束不许再收一遍")
                }
            }
        }
        Unit
    }

    @Test
    fun `正常退出（EOF 排空完）也收口 —— 不只在硬撤销那条路上`() = runBlocking {
        RunIdentityRegistry().use { identities ->
            BridgeRouter(RequestRegistry()).use { router ->
                val seen = CompletableDeferred<Long>()
                router.register("probe") {
                    val ctx = currentCoroutineContext()[AuthenticatedRunContext]!!
                    record(ctx)
                    seen.complete(ctx.connectionId)
                    BridgeResponse.Ok(it.id, null)
                }
                NewlineFrameServer(router, identities).use { srv ->
                    val lease = issue(identities)
                    val out = ByteArrayOutputStream()
                    withTimeout(2_000) {
                        srv.serveConnection(ByteArrayInputStream(BridgeHandshake.hello(lease.token) + req()), out).join()
                    }
                    assertTrue(out.toString(Charsets.UTF_8).contains("\"t\":\"ok\""))
                    val connectionId = withTimeout(2_000) { seen.await() }
                    assertEquals(
                        1,
                        closed(connectionId),
                        "脚本跑完正常退出是最常见的一种结束 —— 也必须收口",
                    )
                }
            }
        }
        Unit
    }

    @Test
    fun `两条连接各收各的 —— 撤销一条不多收另一条`() = runBlocking {
        RunIdentityRegistry().use { identities ->
            BridgeRouter(RequestRegistry()).use { router ->
                // run 21 的那条挂住（等外部撤销），run 22 的那条正常跑完（EOF 收口）。
                val held = CompletableDeferred<Long>()
                router.register("probe") {
                    val ctx = currentCoroutineContext()[AuthenticatedRunContext]!!
                    record(ctx)
                    if (ctx.engineRunId == 21L) {
                        held.complete(ctx.connectionId)
                        awaitCancellation()
                    }
                    BridgeResponse.Ok(it.id, null)
                }
                NewlineFrameServer(router, identities).use { srv ->
                    val a = issue(identities, 21)
                    val b = issue(identities, 22)
                    val jobA = srv.serveConnection(
                        ByteArrayInputStream(BridgeHandshake.hello(a.token) + req()),
                        ByteArrayOutputStream(),
                    )
                    val connA = withTimeout(2_000) { held.await() }
                    withTimeout(2_000) {
                        srv.serveConnection(
                            ByteArrayInputStream(BridgeHandshake.hello(b.token) + req(2)),
                            ByteArrayOutputStream(),
                        ).join()
                    }
                    srv.abortConnection(connA)
                    withTimeout(2_000) { jobA.cancel() }
                    jobA.join()

                    assertEquals(1, closed(connA), "A 自己的资源收了一次")
                    // 两条连接各一份注册表：B 的收口不该被 A 的撤销再触发一次（否则总数会是 3）。
                    assertEquals(2, closedByConnection.values.sumOf { it.get() }, "两条连接各收各的")
                }
            }
        }
        Unit
    }
}
