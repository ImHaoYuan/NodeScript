package com.autoscript.platform.capabilities.screen
import com.autoscript.domain.bridge.generated.WireMethods

import com.autoscript.domain.bridge.RpcNamespaceHandler
import com.autoscript.domain.bridge.BridgeResponse
import com.autoscript.domain.bridge.BridgeRequest
import com.autoscript.domain.json.DomainJson
import com.autoscript.domain.automation.FrameSource
import com.autoscript.domain.automation.ImageFrame
import com.autoscript.domain.automation.MediaProjectionSessionOwner
import com.autoscript.domain.automation.ScreenCaptureSession
import com.autoscript.domain.automation.ScreenConsentBroker
import com.autoscript.domain.automation.mediaProjectionOwnerOrThrow
import com.autoscript.domain.automation.registerConnectionResource
import com.autoscript.domain.bridge.HandleRef
import com.autoscript.domain.core.AutojsException
import com.autoscript.domain.core.ErrorCode

/**
 * `screen` namespace 桥处理器（docs §9.2 / §8.8 / §12.3）：JS `screen.*` 面的 Kotlin 对偶。
 *
 * 归属：住 `:platform:capabilities`；构造收 `:domain` 的 [FrameSource] SPI（含 `recycle`
 * 帧释放）与**可选**的 [MediaProjectionCapturer]（投屏会话面，§9.2）。
 *
 * **两条帧源、一次装配期选择，运行期绝不互相顶替**（本类最重要的一条纪律）：
 * - `capture`/`recycle` 恒走 [FrameSource]（a11y 截图，333ms 节流）—— 与投屏无关；
 * - `startCapturer`/`nextFrame`/`closeSession` 走**会话**：投屏面已接线（生产）时走
 *   MediaProjection；未接线（骨架/单测）时走 [FrameSource.openSession] 的旧会话路径。
 *   这是**构造期**的分岔，不是失败后的静默回落 —— 投屏在**运行期**失败（用户取消、
 *   系统收回、超时）一律如实回 `ERR_CAPTURE_DENIED`/`ERR_TIMEOUT`，**绝不**改走 a11y
 *   截图那条路（那会让脚本以为在录屏，实际拿到的是节流帧）。
 *
 * **会话所有权（§9.2 安全面）**：handler 是全局单例，会话句柄若只按 id 记账，
 * 任何脚本猜一个 id 就能读别人的屏、关别人的会话。所以**投屏会话**句柄绑定开它的那个
 * 已认证执行（[mediaProjectionOwnerOrThrow] 从协程上下文取
 * [com.autoscript.domain.bridge.AuthenticatedRunContext]），三道校验缺一不可：
 * id 在场（否则 `ERR_NOT_FOUND`）→ 代际一致（否则 `ERR_STALE_HANDLE`）→
 * **归属一致**（否则 `ERR_PERMISSION_DENIED` —— 不是"找不到"，两者对调用方含义不同）。
 * 会话 id **单调递增、绝不复用**，`generation` 取投影代际：关掉再开后旧句柄两道都不中，
 * **迟到回调/迟到 close 打不到新会话**。
 *
 * 方法表（与 `bridge/js` images.ts `screen` 对应）：
 * - `capture`：无参 → Ok `{ref:{refId,generation},width,height}`；
 *   锁屏/FLAG_SECURE/无窗口/节流 → 分类 Err（§8.8：分类错误而非黑图）；
 * - `recycle`：payload `{ref}` → Ok `true`（幂等；未知句柄 → ERR_STALE_HANDLE）；
 * - `startCapturer`：payload 可选 `{width?,height?}` → Ok `{session:{refId,generation}}`
 *   （投屏会话；首次会征一次系统同意，用户取消 → `ERR_CAPTURE_DENIED`）。
 *   尺寸是**请求提示**（透传，生产者可忽略）—— 回包不含尺寸、`nextFrame` 的宽高恒为真实帧；
 *   非法（≤0/非整数）回 ERR_INVALID_PARAM，不静默套默认；
 * - `nextFrame`：payload `{session}` → Ok `{ref,width,height}`；
 * - `closeSession`：payload `{session}` → Ok `true`（未知会话 → ERR_NOT_FOUND；越权 → ERR_PERMISSION_DENIED）；
 * - 未知方法 → ERR_NOT_IMPLEMENTED；非法载荷 → ERR_INVALID_PARAM。
 */
class ScreenNamespaceHandler(
    private val source: FrameSource,
    private val projection: MediaProjectionCapturer? = null,
    private val consent: ScreenConsentBroker? = null,
) : RpcNamespaceHandler() {

    /** 申报方法表（wire-schema 对账挂点）：单源指向生成物 [WireMethods.BY_NS]，不手抄。 */
    override fun methods(): Set<String> = WireMethods.BY_NS.getValue("screen")

    private val guard = Any()

    /** 在场面表：一个 id 空间（id 单调递增、绝不复用），条目自己知道该走哪条缝。 */
    private val live = HashMap<Long, LiveSession>()
    private var nextSessionId = 1L

    /** 一条在场会话。[owner] 为 null = 未接线投屏的兼容路径（无归属语义，见类 KDoc）。 */
    private sealed interface LiveSession {
        val generation: Long
        val owner: MediaProjectionSessionOwner?
    }

    /** 兼容路径（[FrameSource.openSession]；单测/骨架用，生产不落这条）。 */
    private data class CompatSession(
        override val generation: Long,
        val session: ScreenCaptureSession,
    ) : LiveSession {
        override val owner: MediaProjectionSessionOwner? = null
    }

    /** 投屏会话：设备面句柄 + **开它的那个执行**。 */
    private data class ProjectionSession(
        override val generation: Long,
        override val owner: MediaProjectionSessionOwner,
        val handle: MediaProjectionHandle,
    ) : LiveSession

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

    /**
     * 开一条会话。**装配期分岔**（见类 KDoc）：
     * - 投屏已接线 → 先取归属（没有已认证身份 → `ERR_PERMISSION_DENIED`），再征一次系统同意；
     *   同意口未接线 → `ERR_CAPTURE_DENIED`（有人能问用户才开得了会话）；
     * - 投屏未接线 → 兼容路径（[FrameSource.openSession]，open 时即做策略判定）。
     */
    private suspend fun startCapturer(request: BridgeRequest): BridgeResponse {
        val size = optSize(request.payload)
        val capturer = projection ?: return startCompatSession(request, size)
        val owner = mediaProjectionOwnerOrThrow()
        val broker = consent
            ?: return err(request, ErrorCode.ERR_CAPTURE_DENIED, "没有可用的系统投屏同意入口（界面未接线）")
        val handle = capturer.start(broker, owner)
        val id = synchronized(guard) {
            val nid = nextSessionId++
            live[nid] = ProjectionSession(handle.generation, owner, handle)
            nid
        }
        // **连接级收口登记**（§9.2）：脚本崩了/被掐了/socket 断了，这条连接上的投屏会话
        // 不能继续挂着（`VirtualDisplay` + mediaProjection 前台会一直留着）。撤销回调里
        // 先从本 handler 的在场面表摘掉自己那条，再让语义层按归属收 —— 两步都幂等。
        connectionResourceRegistration(owner, id)
        return ok(request, sessionPayload(id, handle.generation))
    }

    /**
     * 把"这条连接没了要收什么"登记进连接身份的资源收口口。
     *
     * 取不到身份（宿主直调/无桥上下文）就**不登记**：那种调用没有连接可言，
     * 收口由调用方自己负责（登记到一个没人撤销的注册表等于假装有兜底）。
     */
    private suspend fun connectionResourceRegistration(owner: MediaProjectionSessionOwner, id: Long) {
        val ctx = kotlin.coroutines.coroutineContext[com.autoscript.domain.bridge.AuthenticatedRunContext]
            ?: return
        ctx.registerConnectionResource {
            synchronized(guard) { live.remove(id) }
            projection?.releaseConnection(owner)
        }
    }



    /** 兼容路径（投屏未接线）：旧 a11y 会话语义逐字不变。 */
    private suspend fun startCompatSession(request: BridgeRequest, size: Pair<Int?, Int?>): BridgeResponse {
        val session = source.openSession(size.first, size.second)
        val id = synchronized(guard) {
            val nid = nextSessionId++
            live[nid] = CompatSession(1L, session)
            nid
        }
        return ok(request, sessionPayload(id, 1L))
    }

    private suspend fun nextFrame(request: BridgeRequest): BridgeResponse {
        val ref = requiredRef(decodePayload(request.payload), "session")
        val entry = synchronized(guard) { live[ref.refId] }
            ?: return err(request, ErrorCode.ERR_NOT_FOUND, "未知截图会话 ${ref.refId}")
        if (entry.generation != ref.generation) {
            return err(
                request,
                ErrorCode.ERR_STALE_HANDLE,
                "截图会话句柄跨代 ${ref.refId} gen=${ref.generation}（当前 ${entry.generation}）",
            )
        }
        return when (entry) {
            is CompatSession -> ok(request, framePayload(entry.session.nextFrame()))
            is ProjectionSession -> {
                val owner = entry.owner
                if (owner != mediaProjectionOwnerOrThrow()) {
                    return err(
                        request,
                        ErrorCode.ERR_PERMISSION_DENIED,
                        "投屏会话 ${ref.refId} 不属于本次执行，不能取帧",
                    )
                }
                val capturer = projection
                    ?: return err(request, ErrorCode.ERR_NOT_IMPLEMENTED, "投屏会话未接线")
                ok(request, framePayload(capturer.nextFrame(entry.handle.refId, entry.handle.generation, owner)))
            }
        }
    }

    private suspend fun closeSession(request: BridgeRequest): BridgeResponse {
        val ref = requiredRef(decodePayload(request.payload), "session")
        val entry = synchronized(guard) { live[ref.refId] }
            ?: return err(request, ErrorCode.ERR_NOT_FOUND, "未知截图会话 ${ref.refId}")
        if (entry.generation != ref.generation) {
            return err(
                request,
                ErrorCode.ERR_STALE_HANDLE,
                "截图会话句柄跨代 ${ref.refId} gen=${ref.generation}（当前 ${entry.generation}）",
            )
        }
        when (entry) {
            is CompatSession -> {
                synchronized(guard) { live.remove(ref.refId) }
                entry.session.close()
            }
            is ProjectionSession -> {
                val owner = entry.owner
                if (owner != mediaProjectionOwnerOrThrow()) {
                    // **越权不动手**：关掉别人的会话等于替他断流。
                    return err(
                        request,
                        ErrorCode.ERR_PERMISSION_DENIED,
                        "投屏会话 ${ref.refId} 不属于本次执行，不能关闭",
                    )
                }
                synchronized(guard) { live.remove(ref.refId) }
                projection?.stop(entry.handle.refId, entry.handle.generation, owner)
            }
        }
        return ok(request, "true")
    }

    // ── 载荷 ─────────────────────────────────────────────────────────

    private fun sessionPayload(id: Long, generation: Long): String =
        DomainJson.encode(mapOf("session" to mapOf("refId" to id, "generation" to generation)))

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
