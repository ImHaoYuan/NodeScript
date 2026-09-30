package com.autoscript.platform.capabilities

import com.autoscript.domain.bridge.RpcNamespaceHandler
import com.autoscript.domain.bridge.BridgeResponse
import com.autoscript.domain.bridge.BridgeRequest
import com.autoscript.domain.json.DomainJson
import com.autoscript.domain.automation.FrameSource
import com.autoscript.domain.automation.ImageFrame
import com.autoscript.domain.automation.ScreenCaptureSession
import com.autoscript.domain.bridge.HandleRef
import com.autoscript.domain.core.AutojsException
import com.autoscript.domain.core.ErrorCode

/**
 * `screen` namespace 桥处理器（docs §9.2 / §8.8 / §12.3）：JS `screen.*` 面的 Kotlin 对偶。
 *
 * 归属：住 `:platform:capabilities`；构造只收 `:domain` 的 [FrameSource] SPI
 *（含 `recycle` 帧释放）。Android 真实现（a11y takeScreenshot / MediaProjection）
 * 只需实现该 SPI 即可替换内存帧源 —— 本类会话记账/载荷逻辑不变（见
 * 生产装配 = `CapabilityNamespaces.screen(ScreenshotSource(AndroidFrameProducer()))`，
 * 见 [PlatformWiring] 落点 —— handler 只认 SPI，真假实现同一条缝）。
 *
 * `:app` 装配层薄转接挂 BridgeRouter。载荷复用本模块内 [DomainJson]（同模块 internal 可见）。
 *
 * 方法表（与 `bridge/js` images.ts `screen` 对应）：
 * - `capture`：无参 → Ok `{ref:{refId,generation},width,height}`；
 *   锁屏/FLAG_SECURE/无窗口/节流 → 分类 Err（§8.8：分类错误而非黑图）；
 * - `recycle`：payload `{ref}` → Ok `true`（幂等；未知句柄 → ERR_STALE_HANDLE）；
 * - `startCapturer`：payload 可选 `{width?,height?}` → Ok `{session:{refId,generation}}`
 *   （MediaProjection 会话；open 时即做策略判定，失败直接 Err，不发空会话）。
 *   尺寸是**请求提示**（透传 `FrameSource.openSession`，生产者可忽略）—— 回包不含尺寸、
 *   `nextFrame` 的宽高恒为真实帧；非法（≤0/非整数）回 ERR_INVALID_PARAM，不静默套默认；
 * - `nextFrame`：payload `{session}` → Ok `{ref,width,height}`；
 * - `closeSession`：payload `{session}` → Ok `true`（幂等；未知会话 → ERR_NOT_FOUND）；
 * - 未知方法 → ERR_NOT_IMPLEMENTED；非法载荷 → ERR_INVALID_PARAM。
 */
class ScreenNamespaceHandler(
    private val source: FrameSource,
) : RpcNamespaceHandler() {

    private val guard = Any()
    private val sessions = HashMap<Long, ScreenCaptureSession>()
    private var nextSessionId = 1L

    override suspend fun dispatch(request: BridgeRequest): BridgeResponse = when (request.method) {
        "capture" -> capture(request)
        "recycle" -> recycle(request)
        "startCapturer" -> startCapturer(request)
        "nextFrame" -> nextFrame(request)
        "closeSession" -> closeSession(request)
        else -> err(request, ErrorCode.ERR_NOT_IMPLEMENTED, "未知 screen 方法: ${request.method}")
    }

    private suspend fun capture(request: BridgeRequest): BridgeResponse {
        return ok(request, framePayload(source.capture()))
    }

    private suspend fun recycle(request: BridgeRequest): BridgeResponse {
        val ref = requiredRef(decodePayload(request.payload), "ref")
        return run {
            source.recycle(ref)
            ok(request, "true")
        }
    }

    private suspend fun startCapturer(request: BridgeRequest): BridgeResponse {
        val size = optSize(request.payload)
        return run {
            val session = source.openSession(size.first, size.second)
            val id = synchronized(guard) {
                val nid = nextSessionId++
                sessions[nid] = session
                nid
            }
            ok(request, DomainJson.encode(mapOf("session" to mapOf("refId" to id, "generation" to 1L))))
        }
    }

    private suspend fun nextFrame(request: BridgeRequest): BridgeResponse {
        val sessionId = requiredRef(decodePayload(request.payload), "session").refId
        val session = synchronized(guard) { sessions[sessionId] }
            ?: return err(request, ErrorCode.ERR_NOT_FOUND, "未知截图会话 $sessionId")
        return ok(request, framePayload(session.nextFrame()))
    }

    private suspend fun closeSession(request: BridgeRequest): BridgeResponse {
        val sessionId = requiredRef(decodePayload(request.payload), "session").refId
        val session = synchronized(guard) { sessions.remove(sessionId) }
            ?: return err(request, ErrorCode.ERR_NOT_FOUND, "未知截图会话 $sessionId")
        session.close()
        return ok(request, "true")
    }

    // ── 载荷 ─────────────────────────────────────────────────────────

    private fun framePayload(f: ImageFrame): String =
        DomainJson.encode(
            mapOf(
                "ref" to mapOf("refId" to f.handle.refId, "generation" to f.handle.generation),
                "width" to f.width.toLong(),
                "height" to f.height.toLong(),
            ),
        )

    /**
     * 可选尺寸提示：payload 缺席（null）→ 不带提示；字段缺席/null 同理；非法
     * （非整数、≤0、超 Int）抛 IllegalArgumentException → 调用方折 ERR_INVALID_PARAM。
     * **不静默套默认**：请求了 0 就是要 0，替他改成 1080 是报假尺寸的前一步。
     */
    private fun optSize(payload: String?): Pair<Int?, Int?> {
        if (payload == null) return null to null
        val o = DomainJson.decodeObject(payload)
        return optPositiveInt(o, "width") to optPositiveInt(o, "height")
    }

    private fun optPositiveInt(o: Map<String, DomainJson.Value>, key: String): Int? {
        val v = o[key] ?: return null
        if (v is DomainJson.Value.Null) return null
        val n = (v as? DomainJson.Value.N)?.raw?.toLongOrNull()
            ?: throw IllegalArgumentException("字段 $key 必须是数字")
        if (n <= 0L || n > Int.MAX_VALUE) throw IllegalArgumentException("字段 $key 必须 > 0，实际 $n")
        return n.toInt()
    }

    private fun decodePayload(payload: String?): Map<String, DomainJson.Value> {
        if (payload == null) throw IllegalArgumentException("缺 payload")
        return DomainJson.decodeObject(payload)
    }

    private fun requiredRef(o: Map<String, DomainJson.Value>, key: String): HandleRef {
        val v = o[key] ?: throw IllegalArgumentException("缺 $key 字段")
        if (v !is DomainJson.Value.Obj) throw IllegalArgumentException("$key 必须是对象")
        val refId = (v.fields["refId"] as? DomainJson.Value.N)?.raw?.toLongOrNull()
            ?: throw IllegalArgumentException("缺数字 $key.refId")
        val gen = (v.fields["generation"] as? DomainJson.Value.N)?.raw?.toLongOrNull()
            ?: throw IllegalArgumentException("缺数字 $key.generation")
        return HandleRef(refId, gen)
    }
}
