package com.autoscript.platform.capabilities.screen

import com.autoscript.domain.automation.ScreenConsentBroker
import com.autoscript.domain.automation.ScreenConsentToken
import com.autoscript.domain.automation.ScreenRecordingController
import com.autoscript.domain.bridge.AuthenticatedRunContext
import com.autoscript.domain.bridge.BridgeResponse
import com.autoscript.domain.engine.EngineId
import com.autoscript.domain.json.DomainJson
import com.autoscript.domain.permission.CapabilityMask
import java.nio.file.Path
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertInstanceOf
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir

/**
 * `screen` handler 的**录屏腿**（§9.2 录屏腿）—— 桥面那一半。
 *
 * 覆盖：wire 回包形状（`{session,path}` / `{path,sizeBytes,completed[,detail]}`）、
 * 三道校验在 handler 层的落点（含"录屏会话不能用 closeSession 收"这条分型）、
 * **连接终结时录屏会话被收口**（本任务点名的重点之一），以及**项目号从认证身份取、
 * 不从 payload 取**这条安全判据。
 *
 * 真 `MediaRecorder` / API 34+ 时序**未在本机验证**（无设备）。
 */
class ScreenNamespaceHandlerRecordingTest {

    private val engine = EngineId(0)

    /** a11y 帧源（录屏腿与它无关；本文件只验录屏路径不碰它）。 */
    private fun source(): ScreenshotSource = ScreenshotSource(
        object : ScreenshotSource.FrameProducer {
            override suspend fun snapshot() = com.autoscript.domain.automation.ScreenSnapshot(
                locked = false,
                secureForeground = false,
                hasWindows = true,
            )
            override suspend fun produce(width: Int, height: Int) = ProducedFrame(byteArrayOf(7, 7, 7), 1080, 2400)
        },
    )

    private val consent = ScreenConsentBroker { object : ScreenConsentToken {} }

    private fun handler(sessions: FakeRecordingSessions, filesDir: Path): ScreenNamespaceHandler =
        ScreenNamespaceHandler(
            source(),
            projection = null,
            consent = consent,
            recorder = MediaProjectionRecorder(sessions, filesDir),
        )

    /** 带项目号的执行身份（生产链上由 `RunIdentityRegistry.authenticate` 装填）。 */
    private fun ctx(runId: Long, connectionId: Long, projectId: String = "demo"): AuthenticatedRunContext =
        AuthenticatedRunContext(engine, runId, connectionId, CapabilityMask.ALL).also { it.projectId = projectId }

    private fun sessionRef(resp: BridgeResponse.Ok): String {
        val o = DomainJson.decodeObject(resp.payload!!)
        val session = (o["session"] as DomainJson.Value.Obj).fields
        val id = (session["refId"] as DomainJson.Value.N).raw
        val gen = (session["generation"] as DomainJson.Value.N).raw
        return """{"refId":$id,"generation":$gen}"""
    }

    /** 以某个认证身份派发（生产链上身份随协程上下文传递，不是 handler 参数）。 */
    private suspend fun handleAs(
        context: AuthenticatedRunContext,
        h: ScreenNamespaceHandler,
        req: com.autoscript.domain.bridge.BridgeRequest,
    ): BridgeResponse = kotlinx.coroutines.withContext(context) { h.handle(req) }

    private fun errCode(resp: BridgeResponse): String =
        assertInstanceOf(BridgeResponse.Err::class.java, resp).errorCode

    private fun okPayload(resp: BridgeResponse): Map<String, DomainJson.Value> =
        DomainJson.decodeObject(assertInstanceOf(BridgeResponse.Ok::class.java, resp).payload!!)

    // ── 回包形状 ─────────────────────────────────────────────────────

    @Test
    fun `startRecording 回 {session,path} —— path 开的时候就回（脚本崩了也找得到产物）`(@TempDir filesDir: Path) = runBlocking {
        val sessions = FakeRecordingSessions()
        val h = handler(sessions, filesDir)
        val resp = assertInstanceOf(
            BridgeResponse.Ok::class.java,
            handleAs(ctx(42L, 1L), h, screenReq(1, "startRecording", null)),
        )
        val o = DomainJson.decodeObject(resp.payload!!)
        assertTrue(o.containsKey("session"))
        val path = (o["path"] as DomainJson.Value.S).v
        assertTrue(path.endsWith(".mp4"), "path 是录屏产物落点：$path")
        assertEquals(path, sessions.startCalls.single().second.path)
        Unit
    }

    @Test
    fun `stopRecording 回 {path,sizeBytes,completed} —— completed=true 时不发 detail 字段`(@TempDir filesDir: Path) = runBlocking {
        val sessions = FakeRecordingSessions()
        val h = handler(sessions, filesDir)
        val started = assertInstanceOf(
            BridgeResponse.Ok::class.java,
            handleAs(ctx(42L, 1L), h, screenReq(2, "startRecording", null)),
        )
        val ref = sessionRef(started)
        val o = okPayload(handleAs(ctx(42L, 1L), h, screenReq(3, "stopRecording", """{"session":$ref}""")))
        assertTrue(o.containsKey("path"))
        assertEquals("true", (o["completed"] as DomainJson.Value.B).v.toString())
        assertEquals(4_096L, (o["sizeBytes"] as DomainJson.Value.N).raw.toLong())
        assertFalse(o.containsKey("detail"), "成功时**不发** detail（undefined 与 null 对脚本是两个意思）")
        Unit
    }

    @Test
    fun `stopRecording 失败如实回 completed=false + detail`(@TempDir filesDir: Path) = runBlocking {
        val sessions = FakeRecordingSessions()
        val h = handler(sessions, filesDir)
        val started = assertInstanceOf(
            BridgeResponse.Ok::class.java,
            handleAs(ctx(42L, 1L), h, screenReq(4, "startRecording", null)),
        )
        sessions.current!!.failOnStop = "RuntimeException: stop failed"
        val o = okPayload(
            handleAs(ctx(42L, 1L), h, screenReq(5, "stopRecording", """{"session":${sessionRef(started)}}""")),
        )
        assertEquals("false", (o["completed"] as DomainJson.Value.B).v.toString())
        assertEquals("RuntimeException: stop failed", (o["detail"] as DomainJson.Value.S).v)
        Unit
    }

    // ── 归属三态 ─────────────────────────────────────────────────────

    @Test
    fun `无身份 startRecording 如实 PERMISSION_DENIED —— 绝不放行`(@TempDir filesDir: Path) = runBlocking {
        val h = handler(FakeRecordingSessions(), filesDir)
        assertEquals("ERR_PERMISSION_DENIED", errCode(h.handle(screenReq(6, "startRecording", null))))
    }

    @Test
    fun `身份上没有项目号 → PERMISSION_DENIED（绝不套默认项目名）`(@TempDir filesDir: Path) = runBlocking {
        val h = handler(FakeRecordingSessions(), filesDir)
        val resp = handleAs(ctx(42L, 1L, projectId = ""), h, screenReq(7, "startRecording", null))
        assertEquals("ERR_PERMISSION_DENIED", errCode(resp))
    }

    @Test
    fun `越权身份猜 id 收别人的录屏 —— 收不掉，ERR_PERMISSION_DENIED`(@TempDir filesDir: Path) = runBlocking {
        val sessions = FakeRecordingSessions()
        val h = handler(sessions, filesDir)
        val started = assertInstanceOf(
            BridgeResponse.Ok::class.java,
            handleAs(ctx(42L, 1L), h, screenReq(8, "startRecording", null)),
        )
        val ref = sessionRef(started)
        val denied = handleAs(ctx(43L, 2L), h, screenReq(9, "stopRecording", """{"session":$ref}"""))
        assertEquals("ERR_PERMISSION_DENIED", errCode(denied))
        assertTrue(sessions.current != null, "越权那次绝不能动手")
        Unit
    }

    @Test
    fun `未知录屏会话 id → ERR_NOT_FOUND`(@TempDir filesDir: Path) = runBlocking {
        val h = handler(FakeRecordingSessions(), filesDir)
        val resp = handleAs(ctx(42L, 1L), h, screenReq(10, "stopRecording", """{"session":{"refId":99,"generation":1}}"""))
        assertEquals("ERR_NOT_FOUND", errCode(resp))
    }

    // ── 两条腿分型 ───────────────────────────────────────────────────

    @Test
    fun `录屏会话不能用 closeSession 收 —— 如实指路 stopRecording（closeSession 不回产物）`(@TempDir filesDir: Path) = runBlocking {
        val sessions = FakeRecordingSessions()
        val h = handler(sessions, filesDir)
        val started = assertInstanceOf(
            BridgeResponse.Ok::class.java,
            handleAs(ctx(42L, 1L), h, screenReq(11, "startRecording", null)),
        )
        val resp = handleAs(ctx(42L, 1L), h, screenReq(12, "closeSession", """{"session":${sessionRef(started)}}"""))
        assertEquals("ERR_INVALID_PARAM", errCode(resp))
        assertTrue(sessions.current != null, "分类错误，但**不能**顺手把会话收了")
        Unit
    }

    @Test
    fun `录屏会话不能取帧 —— 如实分类，不假装回一帧`(@TempDir filesDir: Path) = runBlocking {
        val h = handler(FakeRecordingSessions(), filesDir)
        val started = assertInstanceOf(
            BridgeResponse.Ok::class.java,
            handleAs(ctx(42L, 1L), h, screenReq(13, "startRecording", null)),
        )
        val resp = handleAs(ctx(42L, 1L), h, screenReq(14, "nextFrame", """{"session":${sessionRef(started)}}"""))
        assertEquals("ERR_INVALID_PARAM", errCode(resp))
        Unit
    }

    @Test
    fun `录屏未接线 —— 如实 NOT_IMPLEMENTED（不退化成"录一段帧当视频"）`() = runBlocking {
        val h = ScreenNamespaceHandler(source(), projection = null, consent = consent, recorder = null)
        val resp = handleAs(ctx(42L, 1L), h, screenReq(15, "startRecording", null))
        assertEquals("ERR_NOT_IMPLEMENTED", errCode(resp))
    }

    @Test
    fun `同意口缺位 —— 如实 CAPTURE_DENIED（有人能问用户才开得了录屏）`(@TempDir filesDir: Path) = runBlocking {
        val h = ScreenNamespaceHandler(
            source(),
            projection = null,
            consent = null,
            recorder = MediaProjectionRecorder(FakeRecordingSessions(), filesDir),
        )
        val resp = handleAs(ctx(42L, 1L), h, screenReq(16, "startRecording", null))
        assertEquals("ERR_CAPTURE_DENIED", errCode(resp))
    }

    @Test
    fun `stopRecording 幂等：第二次回同一次结果（熄屏裁剪之后也答得出产物）`(@TempDir filesDir: Path) = runBlocking {
        val h = handler(FakeRecordingSessions(), filesDir)
        val context = ctx(42L, 1L)
        val started = assertInstanceOf(
            BridgeResponse.Ok::class.java,
            handleAs(context, h, screenReq(20, "startRecording", null)),
        )
        val ref = sessionRef(started)
        val first = okPayload(handleAs(context, h, screenReq(21, "stopRecording", """{"session":$ref}""")))
        val second = okPayload(handleAs(context, h, screenReq(22, "stopRecording", """{"session":$ref}""")))
        assertEquals(first, second, "第二次必须回同一次结果，不是 ERR_NOT_FOUND")
        Unit
    }

    @Test
    fun `录屏条目有界 —— 开过 70 次之后最老的如实 NOT_FOUND，最新的照常收得掉`(@TempDir filesDir: Path) = runBlocking {
        val h = handler(FakeRecordingSessions(), filesDir)
        val context = ctx(42L, 1L)
        var firstRef = ""
        var lastRef = ""
        repeat(70) { i ->
            val started = assertInstanceOf(
                BridgeResponse.Ok::class.java,
                handleAs(context, h, screenReq(100L + i, "startRecording", null)),
            )
            val ref = sessionRef(started)
            if (i == 0) firstRef = ref
            lastRef = ref
            assertInstanceOf(
                BridgeResponse.Ok::class.java,
                handleAs(context, h, screenReq(200L + i, "stopRecording", """{"session":$ref}""")),
            )
        }
        // 上限 64：第 1 条已被回收 → 如实 NOT_FOUND（产物信息不再在册，不是编一份出来）。
        assertEquals(
            "ERR_NOT_FOUND",
            errCode(handleAs(context, h, screenReq(400, "stopRecording", """{"session":$firstRef}""")),
            ),
        )
        // 最新的那条**必须**还在（回收绝不能丢到活的那条上）。
        assertInstanceOf(
            BridgeResponse.Ok::class.java,
            handleAs(context, h, screenReq(401, "stopRecording", """{"session":$lastRef}""")),
        )
        Unit
    }

    // ── 连接终结收口（本任务点名的重点） ─────────────────────────────

    @Test
    fun `连接撤销 → 录屏会话被收口且文件 finalize（本任务点名的重点）`(@TempDir filesDir: Path) = runBlocking {
        val sessions = FakeRecordingSessions()
        val h = handler(sessions, filesDir)
        val context = ctx(42L, 1L)
        val started = assertInstanceOf(
            BridgeResponse.Ok::class.java,
            handleAs(context, h, screenReq(17, "startRecording", null)),
        )
        val path = (DomainJson.decodeObject(started.payload!!)["path"] as DomainJson.Value.S).v
        val ref = sessionRef(started)
        val live = sessions.current!!
        assertTrue(live != null, "开出来了")
        // 连接终结（脚本崩了 / socket 断了 / 看门狗掐了）：`ConnectionResourceRegistry` 撤销。
        context.resources.revokeAll()
        assertEquals(null, sessions.current, "资源随连接终结收口 —— 结构上不可能'脚本没了录屏还挂着'")
        assertEquals(1, live.finalizeCalls, "mp4 必须当场 finalize（否则留下一个写不出 moov box 的坏文件）")
        // 产物路径**在开的时候就已经回给脚本了**（见 `RecordingHandle` 的 KDoc）——
        // 这正是"连接没了"这条路上产物不丢的原因，不是靠事后还能查。
        assertTrue(path.endsWith(".mp4"))
        // 连接没了 → 桥面这条句柄也摘了：如实 NOT_FOUND（不是编一份结果出来）。
        assertEquals(
            "ERR_NOT_FOUND",
            errCode(handleAs(context, h, screenReq(18, "stopRecording", """{"session":$ref}"""))),
        )
        Unit
    }

    @Test
    fun `熄屏裁剪（连接还在）→ 会话被 finalize，脚本随后仍问得到产物`(@TempDir filesDir: Path) = runBlocking {
        // 与上一条**刻意不同**的收口路径：`SCREEN_OFF` 裁剪时连接还活着，脚本可能接着
        // 问"刚才那段录成什么了"。那条路走的是设备层直调（`AppShellApplication`），
        // 不经连接撤销，所以桥面条目必须留着。
        val sessions = FakeRecordingSessions()
        val h = handler(sessions, filesDir)
        val context = ctx(42L, 1L)
        val started = assertInstanceOf(
            BridgeResponse.Ok::class.java,
            handleAs(context, h, screenReq(30, "startRecording", null)),
        )
        val ref = sessionRef(started)
        sessions.closeCurrentRecording()
        val o = okPayload(handleAs(context, h, screenReq(31, "stopRecording", """{"session":$ref}""")))
        assertEquals("true", (o["completed"] as DomainJson.Value.B).v.toString())
        Unit
    }

    @Test
    fun `连接撤销只收自己的 —— 另一条连接的录屏不受影响`(@TempDir filesDir: Path) = runBlocking {
        val sessions = FakeRecordingSessions()
        val h = handler(sessions, filesDir)
        val owner = ctx(42L, 1L)
        assertInstanceOf(BridgeResponse.Ok::class.java, handleAs(owner, h, screenReq(19, "startRecording", null)))
        // 另一条连接（同一个 run、不同 connectionId）撤销：不该动到上面那条。
        ctx(42L, 2L).resources.revokeAll()
        assertTrue(sessions.current != null, "别的连接撤销不该牵连这条")
        Unit
    }
}
