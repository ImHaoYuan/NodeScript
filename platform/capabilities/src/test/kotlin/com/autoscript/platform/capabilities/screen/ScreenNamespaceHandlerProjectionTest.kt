package com.autoscript.platform.capabilities.screen

import com.autoscript.domain.automation.ScreenConsentBroker
import com.autoscript.domain.automation.ScreenConsentToken
import com.autoscript.domain.automation.ScreenSnapshot
import com.autoscript.domain.bridge.AuthenticatedRunContext
import com.autoscript.domain.bridge.BridgeResponse
import com.autoscript.domain.engine.EngineId
import com.autoscript.domain.json.DomainJson
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertInstanceOf
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * `screen` handler 的**投屏会话路径**（§9.2 安全面）—— 集成验收补充点名的那几条：
 * 低信任脚本**猜 session id 也读不到/关不掉**别人的投屏；旧会话的**迟到 close**
 * 打不到新 consent 代际；两条帧源（a11y 截图 / 投屏会话）**一次装配期分岔、运行期不互顶**。
 *
 * 身份用**协程上下文**给（[AuthenticatedRunContext]），与生产路径同一条
 * （`NewlineFrameServer` 每条连接建一次）。三个身份显式区分：
 * - `合法` = 开会话的那个执行；
 * - `越权` = 另一个 run 的执行（同一台设备、同一条 handler 单例）；
 * - `无身份` = 上下文里没有认证元素。
 */
class ScreenNamespaceHandlerProjectionTest {

    private val engine = EngineId(0)

    /** a11y 帧源（`capture` 恒走它；本文件只验它**没有**被投屏路径顶替）。 */
    private fun source(): ScreenshotSource = ScreenshotSource(
        object : ScreenshotSource.FrameProducer {
            override suspend fun snapshot() = ScreenSnapshot(locked = false, secureForeground = false, hasWindows = true)
            override suspend fun produce(width: Int, height: Int) = ProducedFrame(byteArrayOf(7, 7, 7), 1080, 2400)
        },
    )

    private val consent = ScreenConsentBroker { object : ScreenConsentToken {} }

    private fun handler(sessions: FakeSessions = FakeSessions()): ScreenNamespaceHandler =
        // analyzer 传替身：投屏帧必须经帧表发号（缺位时如实 ERR_NOT_IMPLEMENTED，
        // 那条由 `MediaProjectionSourceTest` 单独钉）。
        ScreenNamespaceHandler(source(), MediaProjectionSource(sessions, FakeAnalyzer()), consent)

    private fun sessionIdOf(resp: BridgeResponse.Ok): String {
        val session = (DomainJson.decodeObject(resp.payload!!)["session"] as DomainJson.Value.Obj).fields
        val id = (session["refId"] as DomainJson.Value.N).raw
        val gen = (session["generation"] as DomainJson.Value.N).raw
        return """{"refId":$id,"generation":$gen}"""
    }

    private fun errCode(resp: BridgeResponse): String =
        assertInstanceOf(BridgeResponse.Err::class.java, resp).errorCode

    // ── 归属三态 ─────────────────────────────────────────────────────

    @Test
    fun `无身份 startCapturer 如实 PERMISSION_DENIED —— 绝不放行`() = runBlocking {
        val h = handler()
        // 上下文里没有 AuthenticatedRunContext（runBlocking 默认空上下文）。
        assertEquals("ERR_PERMISSION_DENIED", errCode(h.handle(screenReq(1, "startCapturer", null))))
    }

    @Test
    fun `越权身份猜 id 取帧 —— 读不到别人的投屏`() = runBlocking {
        val h = handler()
        val session = runBlocking(AuthenticatedRunContext(engine, 42L, 1L)) {
            assertInstanceOf(BridgeResponse.Ok::class.java, h.handle(screenReq(2, "startCapturer", null)))
        }
        val ref = sessionIdOf(session)
        val stolen = runBlocking(AuthenticatedRunContext(engine, 43L, 2L)) {
            h.handle(screenReq(3, "nextFrame", """{"session":$ref}"""))
        }
        assertEquals("ERR_PERMISSION_DENIED", errCode(stolen))
    }

    @Test
    fun `越权身份猜 id 关会话 —— 关不掉，且主人的会话还在`() = runBlocking {
        val sessions = FakeSessions()
        val h = handler(sessions)
        val session = runBlocking(AuthenticatedRunContext(engine, 42L, 1L)) {
            assertInstanceOf(BridgeResponse.Ok::class.java, h.handle(screenReq(4, "startCapturer", null)))
        }
        val ref = sessionIdOf(session)
        val denied = runBlocking(AuthenticatedRunContext(engine, 43L, 2L)) {
            h.handle(screenReq(5, "closeSession", """{"session":$ref}"""))
        }
        assertEquals("ERR_PERMISSION_DENIED", errCode(denied))
        // 主人照常取帧（越权那次没有动手）。
        val frame = runBlocking(AuthenticatedRunContext(engine, 42L, 1L)) {
            h.handle(screenReq(6, "nextFrame", """{"session":$ref}"""))
        }
        assertTrue(assertInstanceOf(BridgeResponse.Ok::class.java, frame).payload!!.contains(""""width""""))
        Unit
    }

    // ── 迟到 close 打不到新代际 ──────────────────────────────────────

    @Test
    fun `旧会话的迟到 close 打不到新 consent 代际`() = runBlocking {
        val sessions = FakeSessions()
        val h = handler(sessions)
        val ctx = AuthenticatedRunContext(engine, 42L, 1L)
        val first = runBlocking(ctx) {
            sessionIdOf(assertInstanceOf(BridgeResponse.Ok::class.java, h.handle(screenReq(7, "startCapturer", null))))
        }
        runBlocking(ctx) {
            assertInstanceOf(BridgeResponse.Ok::class.java, h.handle(screenReq(8, "closeSession", """{"session":$first}""")))
        }
        val second = runBlocking(ctx) {
            sessionIdOf(assertInstanceOf(BridgeResponse.Ok::class.java, h.handle(screenReq(9, "startCapturer", null))))
        }
        assertTrue(first != second, "重开必须是新句柄（id 不复用、代际 +1）")

        // 迟到的旧 close：旧 id 已不在场 → 如实 NOT_FOUND（不是"成功"，也不是"打到新会话"）。
        assertEquals(
            "ERR_NOT_FOUND",
            errCode(runBlocking(ctx) { h.handle(screenReq(10, "closeSession", """{"session":$first}""")) }),
        )
        // 新会话必须还活着。
        val frame = runBlocking(ctx) { h.handle(screenReq(11, "nextFrame", """{"session":$second}""")) }
        assertTrue(assertInstanceOf(BridgeResponse.Ok::class.java, frame).payload!!.contains(""""width""""))
        Unit
    }

    // ── 两条帧源不互顶 ───────────────────────────────────────────────

    @Test
    fun `投屏接线后 capture 仍走 a11y 帧源 —— 不静默换通道`() = runBlocking {
        val h = handler()
        val cap = runBlocking(AuthenticatedRunContext(engine, 42L, 1L)) {
            assertInstanceOf(BridgeResponse.Ok::class.java, h.handle(screenReq(12, "capture", null)))
        }
        // a11y 帧源报的是 1080x2400（见上面 producer）；投屏帧是 2x2 的替身 —— 尺寸就分得开。
        val o = DomainJson.decodeObject(cap.payload!!)
        assertEquals("1080", (o["width"] as DomainJson.Value.N).raw, "capture 恒走 a11y 帧源")
        Unit
    }

    @Test
    fun `投屏接线但同意口缺位 —— 如实 CAPTURE_DENIED，不回落兼容会话`() = runBlocking {
        val h = ScreenNamespaceHandler(
            source(),
            MediaProjectionSource(FakeSessions(), FakeAnalyzer()),
            consent = null,
        )
        val resp = runBlocking(AuthenticatedRunContext(engine, 42L, 1L)) {
            h.handle(screenReq(13, "startCapturer", null))
        }
        assertEquals("ERR_CAPTURE_DENIED", errCode(resp))
    }

    @Test
    fun `未接线投屏（骨架装配）—— 兼容会话路径逐字不变`() = runBlocking {
        // 与 `ScreenNamespaceHandlerTest` 同形：投屏缺省 null 时 startCapturer 走
        // `FrameSource.openSession`（无归属语义），本用例只钉"这条分岔还在"。
        val h = ScreenNamespaceHandler(source())
        val start = assertInstanceOf(
            BridgeResponse.Ok::class.java,
            h.handle(screenReq(14, "startCapturer", null)),
        )
        val ref = sessionIdOf(start)
        val frame = assertInstanceOf(
            BridgeResponse.Ok::class.java,
            h.handle(screenReq(15, "nextFrame", """{"session":$ref}""")),
        )
        assertTrue(frame.payload!!.contains(""""width""""))
        Unit
    }
}
