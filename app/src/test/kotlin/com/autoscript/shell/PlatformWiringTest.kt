package com.autoscript.shell

import com.autoscript.appservice.scheduler.core.InMemoryIntentLog
import com.autoscript.appservice.scheduler.core.SchedulerProvider
import com.autoscript.appservice.scheduler.core.TriggerHandle
import com.autoscript.domain.automation.ColorHit
import com.autoscript.domain.automation.FeatureHit
import com.autoscript.domain.automation.ImageAnalyzer
import com.autoscript.domain.automation.ImageFrame
import com.autoscript.domain.automation.ImageMatch
import com.autoscript.domain.automation.ScreenSnapshot
import com.autoscript.domain.json.DomainJson
import com.autoscript.domain.bridge.BridgeRequest
import com.autoscript.domain.bridge.BridgeResponse
import com.autoscript.domain.bridge.HandleRef
import com.autoscript.domain.core.AutojsException
import com.autoscript.domain.core.ErrorCode
import com.autoscript.domain.engine.EngineId
import com.autoscript.domain.engine.EngineRunReceipt
import com.autoscript.domain.engine.EngineRunRequest
import com.autoscript.domain.engine.EngineStatus
import com.autoscript.domain.engine.KillCause
import com.autoscript.domain.engine.ScriptEngine
import com.autoscript.domain.engine.StopResult
import com.autoscript.domain.storage.InMemoryDataStore
import com.autoscript.platform.system.settings.SystemSettings
import com.autoscript.platform.system.zip.ZipArchiver
import com.autoscript.platform.system.app.AppLauncher
import com.autoscript.platform.system.clipboard.Clipboard
import com.autoscript.platform.system.device.DeviceInfoProvider
import com.autoscript.platform.system.device.DeviceProfile
import com.autoscript.domain.system.DialogHost
import com.autoscript.platform.system.floatingWindow.FloatingWindowHost
import com.autoscript.platform.system.floatingWindow.FloatingWindowSpec
import com.autoscript.platform.system.notification.NotificationPoster
import com.autoscript.platform.system.notification.NotificationSpec
import com.autoscript.platform.system.sensors.SensorDelay
import com.autoscript.platform.system.sensors.SensorEventBatch
import com.autoscript.platform.system.sensors.SensorSource
import com.autoscript.platform.system.shell.ShellExecutor
import com.autoscript.platform.system.shell.ShellMode
import com.autoscript.platform.system.shell.ShellResult
import com.autoscript.platform.system.SystemSpis
import java.nio.file.Path
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertInstanceOf
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import com.autoscript.platform.system.SystemNamespaces
import com.autoscript.platform.capabilities.CapabilityNamespaces
import com.autoscript.platform.capabilities.a11y.AndroidUiTree
import com.autoscript.platform.capabilities.a11y.SystemA11yBridge
import com.autoscript.platform.capabilities.screen.AndroidFrameProducer
import com.autoscript.platform.capabilities.screen.ProducedFrame
import com.autoscript.platform.capabilities.screen.ScreenshotSource

/**
 * 生产能力装配验证（§12.2 接线现状 + §6 包级例外二）。
 *
 * [PlatformWiring.inject] 是 `SystemSpis.Bundle` → `AppShellKit.assemble` 注入束的
 * 纯转接（[PlatformWiring.of] 只多一步 `Context` → Bundle）。本测试用假 SPI 走**同一条
 * 拼装路径**，经 `AppShell.router` 真分发验三件事：
 * 1. 七条独立缝（datastore/zip/settings/notification/clipboard/sensors/images）+ 五命名空间束接通，
 *    handler 是 `CapabilityNamespaces` 的真转接（协议解释权在平台侧，装配只挂载）；
 * 2. `dialogs`：inject 缺省不传 → null → 如实 `ERR_NOT_IMPLEMENTED`；传真宿主
 *    → 经 `CapabilityNamespaces.dialogs` 真转接到 `DialogHost`（生产 of() 传真宿主）；
 * 3. `a11y`/`screen` 生产已接（都经 SystemA11yBridge）：测试进程无无障碍服务 →
 *    如实 `ERR_SERVICE_DISABLED`（不是 NOT_IMPLEMENTED —— namespace 已挂，差的是服务连接）。
 *
 * 真机路径的差异只有 `of(context)` 那一步（`SystemSpis.of` 造 Android 实现），
 * 由 platform/system 的契约测试覆盖；两条路径共用 [PlatformWiring.inject]，
 * 不给"测试走另一套装配"留门。
 */
class PlatformWiringTest {

    // ── 假 SPI（最小可辨识实现：记录调用 + 回固定值）────────────────────

    private class FakeShell : ShellExecutor {
        var lastCommand: String? = null
        override suspend fun exec(command: String, mode: ShellMode, timeoutMillis: Long): ShellResult {
            lastCommand = command
            return ShellResult(code = 0, stdout = "uid=0", stderr = null)
        }
    }

    private class FakeZip : ZipArchiver {
        var compressed: Pair<Path, Path>? = null
        override suspend fun compress(source: Path, archive: Path) {
            compressed = source to archive
        }
        override suspend fun extract(archive: Path, targetDir: Path) = Unit
    }

    private class FakeSettings : SystemSettings {
        private val map = mutableMapOf<String, Any>()
        override fun canWrite(): Boolean = true
        override fun getString(key: String): String? = map[key] as? String
        override fun getInt(key: String): Int? = map[key] as? Int
        override fun putString(key: String, value: String) {
            map[key] = value
        }
        override fun putInt(key: String, value: Int) {
            map[key] = value
        }
    }

    private class FakeClipboard : Clipboard {
        var stored: String? = null
        override fun getText(): String? = stored
        override fun setText(text: String) {
            stored = text
        }
    }

    private class FakeSensors : SensorSource {
        val live = mutableSetOf<Long>()
        var nextId = 1L
        override fun isSupported(name: String): Boolean = name == "accelerometer"
        override suspend fun register(name: String, delay: SensorDelay): HandleRef {
            val id = nextId++
            live += id
            return HandleRef(id, 1)
        }
        override suspend fun unregister(ref: HandleRef) {
            live -= ref.refId
        }
        override suspend fun unregisterAll() {
            live.clear()
        }
        override suspend fun drain(ref: HandleRef, sinceSeq: Long, max: Int): SensorEventBatch =
            SensorEventBatch(sinceSeq, sinceSeq, emptyList())
    }

    /** 假分析器：decode 回一帧（宽高固定），匹配结果由用例指定。 */
    private class FakeImageAnalyzer(
        var hit: ImageMatch? = ImageMatch(12, 34, 100, 50, 0.97),
        var failWith: AutojsException? = null,
    ) : ImageAnalyzer {
        val decoded = mutableListOf<String>()
        val released = mutableListOf<HandleRef>()
        private var nextRefId = 1L
        private val live = mutableSetOf<Long>()
        private fun requireLive(h: HandleRef) {
            if (h.generation != 1L || h.refId !in live) {
                throw AutojsException(ErrorCode.ERR_STALE_HANDLE, "帧 ${h.refId} 不在场")
            }
        }
        override suspend fun decode(path: String): ImageFrame {
            failWith?.let { throw it }
            decoded += path
            val id = nextRefId++
            live += id
            return ImageFrame(HandleRef(id, 1), 640, 480)
        }
        // §18-8(b)：inject 把同一个 analyzer 同时喂给 images 与 screen，
        // 截屏帧从这条口进同一张表（这里只验转接，不碰像素）。
        override suspend fun ingest(width: Int, height: Int, rgba: ByteArray): ImageFrame {
            val id = nextRefId++
            live += id
            return ImageFrame(HandleRef(id, 1), width, height)
        }
        override suspend fun release(handle: HandleRef) {
            if (handle.generation != 1L || handle.refId !in live) {
                throw AutojsException(ErrorCode.ERR_STALE_HANDLE, "帧 ${handle.refId} 不在场")
            }
            live -= handle.refId
            released += handle
        }
        override suspend fun matchTemplate(
            haystack: HandleRef,
            needle: HandleRef,
            threshold: Double,
            region: List<Int>?,
        ): ImageMatch? = hit
        override suspend fun findImage(
            haystack: HandleRef,
            needle: HandleRef,
            threshold: Double,
            region: List<Int>?,
        ): ImageMatch? = hit

        var colorHit: ColorHit? = ColorHit(7, 8, 10, 20, 30, 255)
        val colorCalls = mutableListOf<List<Int>>()
        override suspend fun findColor(
            haystack: HandleRef,
            color: List<Int>,
            tolerance: Int,
            region: List<Int>?,
        ): ColorHit? {
            colorCalls += color
            return colorHit
        }

        override suspend fun toGrayscale(frame: HandleRef): ImageFrame {
            requireLive(frame)
            val id = nextRefId++
            live += id
            return ImageFrame(HandleRef(id, 1), 640, 480)
        }

        override suspend fun crop(frame: HandleRef, region: List<Int>): ImageFrame {
            requireLive(frame)
            val id = nextRefId++
            live += id
            return ImageFrame(HandleRef(id, 1), 640, 480)
        }

        override suspend fun resize(frame: HandleRef, width: Int, height: Int): ImageFrame {
            requireLive(frame)
            val id = nextRefId++
            live += id
            return ImageFrame(HandleRef(id, 1), width, height)
        }

        override suspend fun rotate(frame: HandleRef, degrees: Double): ImageFrame {
            requireLive(frame)
            val id = nextRefId++
            live += id
            return ImageFrame(HandleRef(id, 1), 640, 480)
        }

        override suspend fun findFeature(scene: HandleRef, template: HandleRef): FeatureHit? {
            requireLive(scene)
            requireLive(template)
            return null
        }
    }

    private class FakeNotification : NotificationPoster {
        val posted = mutableListOf<NotificationSpec>()
        override fun canPost(): Boolean = true
        override fun post(spec: NotificationSpec) {
            posted += spec
        }
        override fun cancel(id: Int) = Unit
    }

    private fun bundle(): SystemSpis.Bundle = SystemSpis.Bundle(
        shell = FakeShell(),
        device = object : DeviceInfoProvider {
            override fun profile(): DeviceProfile = DeviceProfile(model = "Pixel 8", sdkInt = 34)
        },
        app = object : AppLauncher {
            override suspend fun launch(packageName: String): Boolean = packageName == "com.example.target"
            override suspend fun currentPackage(): String? = "com.example.here"
        },
        floatingWindow = object : FloatingWindowHost {
            override suspend fun create(spec: FloatingWindowSpec): HandleRef = HandleRef(7, 1)
            override suspend fun close(ref: HandleRef) = Unit
        },
        datastore = InMemoryDataStore(),
        zip = FakeZip(),
        settings = FakeSettings(),
        notification = FakeNotification(),
        clipboard = FakeClipboard(),
        sensors = FakeSensors(),
    )

    // ── 装壳（与 AppShellSystemMountTest 同一骨架，注入束换成 PlatformWiring 的）──

    private class FakeEngine(override val id: EngineId, override val pid: Int? = null) : ScriptEngine {
        override suspend fun execute(run: EngineRunRequest): EngineRunReceipt =
            EngineRunReceipt(runId = 1, handle = HandleRef(1, 1))
        override suspend fun stop(): StopResult = StopResult.Clean
        override suspend fun kill(): KillCause = KillCause.REQUESTED
        override suspend fun status(): EngineStatus = EngineStatus.STOPPED
    }

    private fun shell(wiring: PlatformWiring.Injection): AppShell = AppShell.assemble(
        engineFactory = { id -> FakeEngine(id) },
        schedulerProvider = object : SchedulerProvider {
            override suspend fun registerTrigger(targetFireAtMillis: Long, taskId: String): TriggerHandle =
                TriggerHandle { }
            override suspend fun cancelTrigger(handle: TriggerHandle) = Unit
        },
        intentLog = InMemoryIntentLog(),
        systemHandlers = wiring.systemHandlers,
        datastoreHandler = wiring.datastoreHandler,
        zipHandler = wiring.zipHandler,
        settingsHandler = wiring.settingsHandler,
        notificationHandler = wiring.notificationHandler,
        clipboardHandler = wiring.clipboardHandler,
        sensorsHandler = wiring.sensorsHandler,
        imagesHandler = wiring.imagesHandler,
        a11yHandler = wiring.a11yHandler,
        screenHandler = wiring.screenHandler,
    )

    private suspend fun dispatch(
        s: AppShell,
        ns: String,
        method: String,
        payload: String?,
    ): BridgeResponse = s.router.dispatch(BridgeRequest(1, ns, method, payload, 5_000))

    private fun okPayload(r: BridgeResponse): String =
        assertInstanceOf(BridgeResponse.Ok::class.java, r, "响应须 Ok，实际：$r").payload!!

    private fun errCode(r: BridgeResponse): String =
        assertInstanceOf(BridgeResponse.Err::class.java, r).errorCode

    // ── 用例 ────────────────────────────────────────────────────────────

    @Test
    fun `五命名空间束接通，dialogs 诚实缺位`() = runBlocking {
        val spis = bundle()
        val wiring = PlatformWiring.inject(spis)
        assertNull(wiring.systemHandlers.dialogs, "inject 未传 dialogs → 留 null 而不是假实现")

        shell(wiring).use { s ->
            // 载荷字段语义归 SystemNamespaces 测试；这里验「装配接通 + SPI 收到真命令」
            assertInstanceOf(
                BridgeResponse.Ok::class.java,
                dispatch(s, "shell", "exec", """{"cmd":"id"}"""),
            )
            assertEquals("id", (spis.shell as FakeShell).lastCommand, "SPI 必须收到真命令")

            assertInstanceOf(
                BridgeResponse.Ok::class.java,
                dispatch(s, "device", "model", null),
                "device 走 CapabilityNamespaces 真转接",
            )
            assertInstanceOf(
                BridgeResponse.Ok::class.java,
                dispatch(s, "app", "currentPackage", null),
            )
            assertInstanceOf(
                BridgeResponse.Ok::class.java,
                dispatch(s, "floatingWindow", "create", """{"title":"t"}"""),
            )
            assertEquals(
                "ERR_NOT_IMPLEMENTED",
                errCode(dispatch(s, "dialogs", "prompt", "{}")),
                "dialogs 缺位必须如实未实现（不伪造弹窗）",
            )
        }
        Unit
    }

    @Test
    fun `存储通知剪贴板传感图像七条独立缝经真 handler 落到假 SPI`() = runBlocking {
        val spis = bundle()
        val wiring = PlatformWiring.inject(spis)

        shell(wiring).use { s ->
            // datastore：put → get 往返（InMemoryDataStore 真参与，不是 mock 回包）
            assertEquals("true", okPayload(dispatch(s, "datastore", "put", """{"key":"cfg","value":{"a":1}}""")))
            assertEquals(
                """{"found":true,"value":{"a":1}}""",
                okPayload(dispatch(s, "datastore", "get", """{"key":"cfg"}""")),
            )

            // settings：写进假 SPI、读回同一份
            assertEquals("true", okPayload(dispatch(s, "settings", "putString", """{"key":"k","value":"v"}""")))
            assertEquals("\"v\"", okPayload(dispatch(s, "settings", "getString", """{"key":"k"}""")))

            // notification：canPost 探针 + post 真落到假 SPI
            assertEquals("true", okPayload(dispatch(s, "notification", "canPost", null)))
            assertEquals("true", okPayload(dispatch(s, "notification", "post", """{"id":7,"text":"跑完了"}""")))
            // 落局部：SystemSpis 来自 :domain（跨模块的 public 属性不给 smart cast）。
            val notifier = spis.notification as FakeNotification
            assertEquals(1, notifier.posted.size)
            assertEquals("跑完了", notifier.posted[0].text)

            // clipboard：set 到假 SPI、get 读回同一份（空串是真值）
            assertEquals("true", okPayload(dispatch(s, "clipboard", "setText", "{\"text\":\"hello\"}")))
            assertEquals("\"hello\"", okPayload(dispatch(s, "clipboard", "getText", null)))
            assertEquals("true", okPayload(dispatch(s, "clipboard", "setText", "{\"text\":\"\"}")))
            assertEquals("\"\"", okPayload(dispatch(s, "clipboard", "getText", null)))

            // sensors：register 发号 + drain 空增量游标回显（语义归 platform:system 侧测）
            val regPayload = okPayload(dispatch(s, "sensors", "register", "{\"name\":\"accelerometer\"}"))
            assertTrue(regPayload.contains("refId"), "register 回 ref 体，实际 $regPayload")
            assertEquals("true", okPayload(dispatch(s, "sensors", "isSupported", "{\"name\":\"accelerometer\"}")))
            val drainPayload = okPayload(
                dispatch(s, "sensors", "drain", "{\"ref\":{\"refId\":1,\"generation\":1},\"sinceSeq\":0}"),
            )
            assertTrue(
                drainPayload.contains("\"first\":0") && drainPayload.contains("\"events\":[]"),
                "空增量游标回显，实际 $drainPayload",
            )
            assertEquals("true", okPayload(dispatch(s, "sensors", "unregisterAll", null)))

            // images：inject 收假分析器 → 独立缝接通（decode 真宽高 + 命中体），
            // 未注入时桥对 images.* 如实 ERR_NOT_IMPLEMENTED（生产侧刻意不喂）
            val analyzer = FakeImageAnalyzer()
            val wired = PlatformWiring.inject(bundle(), images = analyzer)
            shell(wired).use { s ->
                val frame = okPayload(dispatch(s, "images", "decode", """{"path":"/sdcard/icon.png"}"""))
                assertTrue(
                    frame.contains("\"refId\":1") && frame.contains("\"width\":640") && frame.contains("\"height\":480"),
                    "decode 回帧三字段（宽高是文件真值），实际 $frame",
                )
                assertEquals(listOf("/sdcard/icon.png"), analyzer.decoded)
                // findColor：同一独立缝第五方法的端到端（见缝即通，假分析器给出固定命中）。
                // 必须在 release 之前打 —— 帧放了就打不到了（帧纪律）
                val hitPayload = okPayload(
                    dispatch(
                        s, "images", "findColor",
                        """{"haystack":{"refId":1,"generation":1},"color":[10,20,30,255],"tolerance":5}""",
                    ),
                )
                assertTrue(
                    hitPayload.contains("\"x\":7") && hitPayload.contains("\"r\":10"),
                    "findColor 回 {x,y,r,g,b,a}，实际 $hitPayload",
                )
                assertEquals(listOf(listOf(10, 20, 30, 255)), analyzer.colorCalls)
                assertEquals(
                    "true",
                    okPayload(dispatch(s, "images", "release", """{"ref":{"refId":1,"generation":1}}""")),
                )
                assertEquals(listOf<Long>(1L), analyzer.released.map { it.refId })
            }


            assertEquals(
                "ERR_NOT_IMPLEMENTED",
                errCode(dispatch(shell(PlatformWiring.inject(bundle())), "images", "decode", "{}")),
                "生产 inject 不喂分析器 → 独立缝缺省同样不伪造（真实现等 :bridge:image，P1）",
            )

            // zip：compress 参数原样到假归档器
            assertInstanceOf(
                BridgeResponse.Ok::class.java,
                dispatch(s, "zip", "compress", """{"source":"/a/dir","archive":"/b/out.zip"}"""),
            )
            assertEquals(
                Path.of("/a/dir") to Path.of("/b/out.zip"),
                (spis.zip as FakeZip).compressed,
                "假归档器必须收到调用方给的两个路径",
            )
        }
        Unit
    }

    @Test
    fun `dialogs 传真宿主即接通 wire 形状与 extras 契约一致`() = runBlocking {
        val host = object : DialogHost {
            override suspend fun prompt(request: com.autoscript.domain.system.DialogPromptRequest) =
                com.autoscript.domain.system.DialogOutcome("张三", confirmed = true)

            override suspend fun choose(request: com.autoscript.domain.system.DialogChooseRequest) =
                com.autoscript.domain.system.DialogChoice(1)
        }
        val wiring = PlatformWiring.inject(bundle(), dialogs = host)
        shell(wiring).use { s ->
            val prompt = okPayload(
                dispatch(s, "dialogs", "prompt", """{"title":"名字"}"""),
            )
            assertTrue(
                prompt.contains(""""value":"张三"""") && prompt.contains("confirmed"),
                "prompt 回 value/confirmed 两字段（extras.ts 契约），实际 $prompt",
            )
            assertEquals("1", okPayload(dispatch(s, "dialogs", "choose", """{"title":"选","options":["a","b"]}""")),
                "choose 裸下标直出（取消才是 -1）")
        }
        Unit
    }

    @Test
    fun `a11y 与 screen 生产已接但服务未连——双双如实 ERR_SERVICE_DISABLED`() = runBlocking {
        shell(PlatformWiring.inject(bundle())).use { s ->
            assertEquals(
                "ERR_SERVICE_DISABLED",
                errCode(
                    dispatch(s, "a11y", "findOne", """{"conditions":{}}"""),
                ),
                "namespace 已挂（AndroidUiTree 真转接）；测试进程无无障碍服务 → 差的是连接不是实现",
            )
            assertEquals(
                "ERR_SERVICE_DISABLED",
                errCode(dispatch(s, "screen", "capture", null)),
                "screen 同底（ScreenshotSource+AndroidFrameProducer 经 SystemA11yBridge）",
            )
        }
        Unit
    }

    // ── 跨命名空间帧表（§18-8(b)；2026-09-30 自 capabilities ImagesNamespaceHandlerTest 迁入：
    // images handler 已随步骤 6 迁 :platform:system，跨 system×capabilities 的互认测试只有
    // 同时依赖两者的 :app 能住 —— 生产侧也正是 PlatformWiring 把同一个 analyzer 同时喂给两边) ──

    private fun rgbaProducer(w: Int, h: Int): ScreenshotSource.FrameProducer =
        object : ScreenshotSource.FrameProducer {
            override suspend fun snapshot(): ScreenSnapshot =
                ScreenSnapshot(locked = false, secureForeground = false, hasWindows = true)

            override suspend fun produce(width: Int, height: Int): ProducedFrame =
                ProducedFrame(ByteArray(w * h * 4), w, h)
        }

    private fun refJson(ref: HandleRef): String =
        """{"ref":{"refId":${ref.refId},"generation":${ref.generation}}}"""

    private fun matchJson(haystack: HandleRef, needle: HandleRef, threshold: String): String =
        """{"haystack":{"refId":${haystack.refId},"generation":${haystack.generation}},"needle":{"refId":${needle.refId},"generation":${needle.generation}},"threshold":$threshold}"""

    @Test
    fun `截屏帧与 decode 帧同一张表——findImage 通、images 能放 screen 的帧`() = runBlocking {
        val shared = FakeImageAnalyzer(hit = null)
        val images = SystemNamespaces.images(shared)
        // 可控时钟：capture 走 333ms 节流，别让用例撞在窗口上
        var now = 1_000L
        val screen = ScreenshotSource(rgbaProducer(4, 4), clock = { now }, analyzer = shared)

        val shot = screen.capture()
        assertEquals(1L, shot.handle.refId, "截屏帧进的是 images 那张表（号段从 1 起）")

        val iconPayload = okPayload(images.handle(BridgeRequest(2, "images", "decode", """{"path":"/sdcard/icon.png"}""", 5_000)))
        val iconFields = (DomainJson.decodeObject(iconPayload)["ref"] as DomainJson.Value.Obj).fields
        val icon = HandleRef(
            (iconFields["refId"] as DomainJson.Value.N).raw.toLong(),
            (iconFields["generation"] as DomainJson.Value.N).raw.toLong(),
        )
        assertEquals(2L, icon.refId, "decode 接着截屏帧往下发号 —— 同一段，不是两张表")

        // 互认的核心：截屏帧当 haystack 不是 ERR_STALE_HANDLE
        val matched = okPayload(
            images.handle(
                BridgeRequest(3, "images", "findImage", matchJson(shot.handle, icon, "0.9"), 5_000),
            ),
        )
        assertEquals("null", matched, "跨来源两帧都认得（fake 未设命中 → 裸 null，不是 STALE）")

        // `images.release` 放得掉一帧截屏（曾经：这张表里根本没有它）
        assertEquals("true", okPayload(images.handle(BridgeRequest(4, "images", "release", refJson(shot.handle), 5_000))))
        assertEquals(
            "ERR_STALE_HANDLE",
            errCode(images.handle(BridgeRequest(5, "images", "release", refJson(shot.handle), 5_000))),
            "放掉即离场：两边同一口径",
        )
        // screen 侧再 recycle 同一帧 → 同码（同一张表、同一个"已释放"事实）
        val e = assertThrows<AutojsException> { runBlocking { screen.recycle(shot.handle) } }
        assertEquals(ErrorCode.ERR_STALE_HANDLE, e.error)

        // 截屏帧放掉后，decode 帧照常在场可放（两帧互不牵连）
        assertEquals("true", okPayload(images.handle(BridgeRequest(6, "images", "release", refJson(icon), 5_000))))
        Unit
    }
}
