package com.autoscript.platform.capabilities.screen

import com.autoscript.domain.automation.ImageAnalyzer
import com.autoscript.domain.automation.MediaProjectionOpenException
import com.autoscript.domain.automation.MediaProjectionOpenFailure
import com.autoscript.domain.automation.MediaProjectionSessionOwner
import com.autoscript.domain.automation.MediaProjectionSessionState
import com.autoscript.domain.automation.ScreenConsentBroker
import com.autoscript.domain.automation.ScreenConsentToken
import com.autoscript.domain.core.AutojsException
import com.autoscript.domain.core.ErrorCode
import com.autoscript.domain.engine.EngineId
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeoutOrNull
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows

/**
 * 投屏会话语义面（§9.2 安全面 + §18-8(b) 帧表共用）—— 纯 JVM，不碰 Android。
 *
 * 判据分六组（对着集成验收点逐条）：
 * - **归属三态**：合法身份放行、**越权身份拒绝且不动手**、无身份在 handler 层拒；
 * - **代际**：跨代句柄 `ERR_STALE_HANDLE`，**迟到 close 打不到新会话**；
 * - **单会话 + 单开启**：已有活会话或已有一次开启在进行时再开如实 `ERR_CAPTURE_DENIED`，**不抢占**；
 * - **开启中收口**：`closeCurrent`/`releaseConnection` 落在 `open` 的挂起点里 → 那条会话
 *   建好也不提交（`CancellationException`），且**取消不被吞成"开失败"**；
 * - **连接撤销**：只收自己的（别人的会话原样活着）；
 * - **帧表**：帧一律经 `ImageAnalyzer.ingest` 发号；analyzer 缺位 → `ERR_NOT_IMPLEMENTED`。
 */
class MediaProjectionSourceTest {

    private val engine = EngineId(0)

    /** 同一次执行、两条连接 —— 归属必须能分开（这正是 owner 含 connectionId 的理由）。 */
    private val ownerA = MediaProjectionSessionOwner(engine, 42L, 1L)
    private val ownerA2 = MediaProjectionSessionOwner(engine, 42L, 2L)

    /** 另一次执行。 */
    private val ownerB = MediaProjectionSessionOwner(engine, 43L, 1L)

    private val consent = ScreenConsentBroker { object : ScreenConsentToken {} }

    private fun source(sessions: FakeSessions, analyzer: ImageAnalyzer? = FakeAnalyzer()) =
        MediaProjectionSource(sessions, analyzer)

    private suspend fun MediaProjectionSource.openAs(owner: MediaProjectionSessionOwner) =
        start(consent, owner)

    // ── 归属 ─────────────────────────────────────────────────────────

    @Test
    fun `合法身份开会话取帧 —— 帧经 ingest 发号`() = runBlocking {
        val sessions = FakeSessions()
        val analyzer = FakeAnalyzer()
        val src = source(sessions, analyzer)
        val handle = src.openAs(ownerA)
        assertEquals(1L, handle.refId)
        assertEquals(1L, handle.generation)
        val frame = src.nextFrame(handle.refId, handle.generation, ownerA)
        assertEquals(1, frame.width)
        assertEquals(1, analyzer.ingestCalls, "投屏帧必须经同一个帧表发号（§18-8(b)）")
        Unit
    }

    @Test
    fun `越权身份取帧如实 PERMISSION_DENIED`() {
        val src = source(FakeSessions())
        val handle = runBlocking { src.openAs(ownerA) }
        val e = assertThrows<AutojsException> {
            runBlocking { src.nextFrame(handle.refId, handle.generation, ownerB) }
        }
        assertEquals(ErrorCode.ERR_PERMISSION_DENIED, e.error)
    }

    @Test
    fun `同一次执行的另一条连接也算越权 —— 归属含连接号`() {
        val src = source(FakeSessions())
        val handle = runBlocking { src.openAs(ownerA) }
        val e = assertThrows<AutojsException> {
            runBlocking { src.nextFrame(handle.refId, handle.generation, ownerA2) }
        }
        assertEquals(ErrorCode.ERR_PERMISSION_DENIED, e.error)
        Unit
    }

    @Test
    fun `越权身份关会话 —— 拒绝且不动手（别人的流不许断）`() {
        val sessions = FakeSessions()
        val src = source(sessions)
        val handle = runBlocking { src.openAs(ownerA) }
        val e = assertThrows<AutojsException> {
            runBlocking { src.stop(handle.refId, handle.generation, ownerB) }
        }
        assertEquals(ErrorCode.ERR_PERMISSION_DENIED, e.error)
        assertEquals(MediaProjectionSessionState.ACTIVE, sessions.state, "越权 close 之后会话必须还在跑")
        Unit
    }

    // ── 代际 ─────────────────────────────────────────────────────────

    @Test
    fun `跨代句柄 ERR_STALE_HANDLE`() {
        val src = source(FakeSessions())
        val handle = runBlocking { src.openAs(ownerA) }
        val e = assertThrows<AutojsException> {
            runBlocking { src.nextFrame(handle.refId, handle.generation + 99, ownerA) }
        }
        assertEquals(ErrorCode.ERR_STALE_HANDLE, e.error)
    }

    @Test
    fun `旧会话的迟到 close 打不到新会话`() = runBlocking {
        val sessions = FakeSessions()
        val src = source(sessions)
        val first = src.openAs(ownerA)
        assertTrue(src.stop(first.refId, first.generation, ownerA))

        val second = src.openAs(ownerA)
        assertEquals(first.refId + 1, second.refId, "会话 id 单调递增、绝不复用")
        assertEquals(first.generation + 1, second.generation)

        // 迟到的旧句柄：如实报找不到（幂等 false），**新会话不受影响**。
        assertFalse(src.stop(first.refId, first.generation, ownerA), "旧 id 已不在场 → 幂等 false")
        assertEquals(MediaProjectionSessionState.ACTIVE, sessions.state, "新会话必须还活着")
        assertEquals(1, src.nextFrame(second.refId, second.generation, ownerA).width)
        Unit
    }

    @Test
    fun `系统收回（onStop）后取帧如实 CAPTURE_DENIED，且 current 已清可重开`() = runBlocking {
        val sessions = FakeSessions()
        val src = source(sessions)
        val handle = src.openAs(ownerA)
        sessions.systemStop()
        val e = assertThrows<AutojsException> {
            runBlocking { src.nextFrame(handle.refId, handle.generation, ownerA) }
        }
        assertEquals(ErrorCode.ERR_CAPTURE_DENIED, e.error)
        // **进程级状态回到 IDLE**（不是 STOPPED）：系统收回后设备层把 `current` 一并清了，
        // 于是"这条设备上现在没有会话" —— `STOPPED` 是**句柄级**的事实（那个 DeviceSession
        // 还活着但不可用），两者不是同一个问题。只置一个 stopped 标志而不清 current 会让
        // `current != null` 永久挡住重开，那才是本条要钉的故障。
        assertEquals(MediaProjectionSessionState.IDLE, src.projectionState())
        // 系统停过之后必须还能重开。
        val again = src.openAs(ownerA)
        assertEquals(MediaProjectionSessionState.ACTIVE, sessions.state)
        assertEquals(1, src.nextFrame(again.refId, again.generation, ownerA).width)
        Unit
    }

    // ── 单会话 / 单开启 ──────────────────────────────────────────────

    @Test
    fun `已有一条活会话时再开 —— 如实拒绝且不抢占`() {
        val sessions = FakeSessions()
        val src = source(sessions)
        runBlocking { src.openAs(ownerA) }
        val e = assertThrows<AutojsException> { runBlocking { src.openAs(ownerB) } }
        assertEquals(ErrorCode.ERR_CAPTURE_DENIED, e.error)
        assertEquals(listOf(ownerA), sessions.openCalls, "第二次开不许落到设备层（不杀旧会话）")
        assertEquals(MediaProjectionSessionState.ACTIVE, sessions.state)
        Unit
    }

    @Test
    fun `并发 start —— 后到的那次如实拒绝，不产生第二条会话`() = runBlocking {
        val sessions = FakeSessions().apply { gateOpen = true }
        val src = source(sessions)
        val first = async { runCatching { src.openAs(ownerA) } }
        // 等第一次真的卡在 open 里（`opening` 已置位）。
        withTimeoutOrNull(2_000) {
            while (sessions.pendingOpen == null) delay(1)
        }
        val second = async { runCatching { src.openAs(ownerB) } }
        val secondResult = second.await()
        assertTrue(secondResult.isFailure, "第二次开必须失败")
        assertEquals(
            ErrorCode.ERR_CAPTURE_DENIED,
            (secondResult.exceptionOrNull() as AutojsException).error,
        )
        sessions.finishOpen()
        assertTrue(first.await().isSuccess, "第一次开照常成功")
        assertEquals(1, sessions.openCalls.count { it == ownerA })
        assertEquals(MediaProjectionSessionState.ACTIVE, sessions.state)
        Unit
    }

    @Test
    fun `开启失败按分类透传（DENIED 与 UNAVAILABLE 两个码）`() {
        val denied = FakeSessions().apply {
            failWith = MediaProjectionOpenException(MediaProjectionOpenFailure.DENIED, "用户取消")
        }
        val e1 = assertThrows<AutojsException> { runBlocking { source(denied).openAs(ownerA) } }
        assertEquals(ErrorCode.ERR_CAPTURE_DENIED, e1.error)

        val unavailable = FakeSessions().apply {
            failWith = MediaProjectionOpenException(MediaProjectionOpenFailure.UNAVAILABLE, "没有投屏通道")
        }
        val e2 = assertThrows<AutojsException> { runBlocking { source(unavailable).openAs(ownerA) } }
        assertEquals(ErrorCode.ERR_SERVICE_DISABLED, e2.error)
        Unit
    }

    // ── 开启中被收口 / 取消 ──────────────────────────────────────────

    @Test
    fun `开启中 closeCurrent —— 会话不提交，取消原样传播（不吞成开失败）`() = runBlocking {
        val sessions = FakeSessions().apply { gateOpen = true }
        val src = source(sessions)
        val pending = async { runCatching { src.openAs(ownerA) } }
        withTimeoutOrNull(2_000) {
            while (sessions.pendingOpen == null) delay(1)
        }
        src.closeCurrent()
        sessions.finishOpen()
        val result = pending.await()
        assertTrue(result.exceptionOrNull() is CancellationException, "取消必须原样传播：${result.exceptionOrNull()}")
        assertNull(sessions.current, "被收口的那次不许提交成在册会话")
        assertEquals(MediaProjectionSessionState.IDLE, src.projectionState())
        Unit
    }

    @Test
    fun `开启中连接撤销 —— 只收自己的，且不提交野句柄`() = runBlocking {
        val sessions = FakeSessions().apply { gateOpen = true }
        val src = source(sessions)
        val pending = async { runCatching { src.openAs(ownerA) } }
        withTimeoutOrNull(2_000) {
            while (sessions.pendingOpen == null) delay(1)
        }
        assertTrue(src.releaseConnection(ownerA), "撤销要如实回'确实收了'（那条正在开）")
        sessions.finishOpen()
        val result = pending.await()
        assertTrue(result.exceptionOrNull() is CancellationException)
        assertNull(sessions.current, "撤销过的开启不许提交")
        Unit
    }

    @Test
    fun `协程取消 —— 原样传播且不留会话`() = runBlocking {
        val sessions = FakeSessions().apply { gateOpen = true }
        val src = source(sessions)
        val pending = async { runCatching { src.openAs(ownerA) } }
        withTimeoutOrNull(2_000) {
            while (sessions.pendingOpen == null) delay(1)
        }
        pending.cancel()
        sessions.finishOpen()
        withTimeoutOrNull(2_000) { pending.join() }
        // 取消必须**原样传播**（不是被吞成"开失败"）：`await` 要以 `CancellationException`
        // 收尾。刻意不用 `getCompletionExceptionOrNull()`/`isCancelled`（前者是实验 API，
        // 后者只回答"这个 Job 被取消过"——那是我们刚做过的事，不是本条的判据）；
        // 这里要问的是"**它的收尾异常**是不是取消"，所以直接接住 `await` 抛出来的那个。
        val endedWith = runCatching { pending.await() }.exceptionOrNull()
        assertTrue(endedWith is CancellationException, "取消要原样传播，实际：$endedWith")
        assertNull(sessions.current, "取消后不许留下会话")
        Unit
    }

    // ── 连接撤销 ─────────────────────────────────────────────────────

    @Test
    fun `连接撤销只收自己的 —— 别人的会话原样活着`() = runBlocking {
        val sessions = FakeSessions()
        val src = source(sessions)
        val mine = src.openAs(ownerA)
        // 撤销 ownerA 的连接：它那条必须被收掉。
        assertTrue(src.releaseConnection(ownerA))
        assertNull(sessions.current, "自己的会话必须被收掉")
        assertEquals(MediaProjectionSessionState.IDLE, src.projectionState())

        // 重开一条给 ownerB，再撤销 ownerA（已无资源）——不许动 B 的。
        val theirs = src.openAs(ownerB)
        assertFalse(src.releaseConnection(ownerA), "ownerA 已经没有资源了")
        assertEquals(MediaProjectionSessionState.ACTIVE, sessions.state, "撤销别人不许动到 ownerB")
        assertEquals(1, src.nextFrame(theirs.refId, theirs.generation, ownerB).width)
        // 幂等：再撤一次仍然只回 false，不炸。
        assertFalse(src.releaseConnection(ownerA))
        assertEquals(MediaProjectionSessionState.ACTIVE, sessions.state)
        assertNotNull(mine)
        Unit
    }

    @Test
    fun `连接撤销幂等 —— 重复撤销不炸且不回退别人的状态`() = runBlocking {
        val sessions = FakeSessions()
        val src = source(sessions)
        src.openAs(ownerB)
        assertFalse(src.releaseConnection(ownerA))
        assertFalse(src.releaseConnection(ownerA))
        assertEquals(MediaProjectionSessionState.ACTIVE, sessions.state)
        Unit
    }

    // ── 帧表 ─────────────────────────────────────────────────────────

    @Test
    fun `analyzer 缺位如实 NOT_IMPLEMENTED —— 绝不本地发号`() {
        val src = source(FakeSessions(), analyzer = null)
        val handle = runBlocking { src.openAs(ownerA) }
        val e = assertThrows<AutojsException> {
            runBlocking { src.nextFrame(handle.refId, handle.generation, ownerA) }
        }
        assertEquals(ErrorCode.ERR_NOT_IMPLEMENTED, e.error)
        assertTrue(
            e.message!!.contains("撞号"),
            "错误消息要点明理由（本地发号会让两个帧源撞号），实际：${e.message}",
        )
        Unit
    }

    // ── 框架侧收口 ───────────────────────────────────────────────────

    @Test
    fun `closeCurrent 作废所有句柄并停设备层会话`() = runBlocking {
        val sessions = FakeSessions()
        val src = source(sessions)
        val handle = src.openAs(ownerA)
        src.closeCurrent()
        assertEquals(1, sessions.closeCurrentCalls)
        assertEquals(MediaProjectionSessionState.IDLE, sessions.state)
        val e = assertThrows<AutojsException> {
            runBlocking { src.nextFrame(handle.refId, handle.generation, ownerA) }
        }
        assertEquals(ErrorCode.ERR_NOT_FOUND, e.error, "框架收口后旧句柄一律作废")
        Unit
    }

    @Test
    fun `closeCurrent 幂等 —— 没有会话时不炸`() = runBlocking {
        val sessions = FakeSessions()
        val src = source(sessions)
        src.closeCurrent()
        src.closeCurrent()
        assertEquals(2, sessions.closeCurrentCalls)
        assertEquals(MediaProjectionSessionState.IDLE, src.projectionState())
        Unit
    }
}
