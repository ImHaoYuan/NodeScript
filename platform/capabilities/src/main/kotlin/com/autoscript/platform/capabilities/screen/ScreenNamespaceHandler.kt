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
import com.autoscript.domain.automation.RecordingHandle
import com.autoscript.domain.automation.RecordingOutcome
import com.autoscript.domain.automation.ScreenRecordingController
import com.autoscript.domain.automation.screenRecordingContextOrThrow
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
 * - `startRecording`：payload 可选 `{width?,height?}` → Ok `{session:{refId,generation},path}`
 *   （**录屏腿**，§9.2：同一条 MediaProjection 会话账、同一个 mediaProjection 前台类型，
 *   输出汇是 `MediaRecorder` 的 Surface）。首次征一次系统同意（与 `startCapturer` **同一口**，
 *   用户取消 → `ERR_CAPTURE_DENIED`）。尺寸是**请求提示**（透传），非法回 ERR_INVALID_PARAM。
 *   `path` **开的时候就回**：脚本崩了之后文件仍会被框架收口 finalize，路径若只在 stop 回，
 *   那条产物就成了"存在但没人知道在哪"；
 * - `stopRecording`：payload `{session}` → Ok `{path,sizeBytes,completed,detail?}`
 *   （**幂等**：第二次回同一次结果 —— 框架收口（熄屏裁剪/连接撤销）之后仍答得出产物）；
 * - 未知方法 → ERR_NOT_IMPLEMENTED；非法载荷 → ERR_INVALID_PARAM。
 */
class ScreenNamespaceHandler(
    private val source: FrameSource,
    private val projection: MediaProjectionCapturer? = null,
    private val consent: ScreenConsentBroker? = null,
    /**
     * 录屏腿（§9.2）：与 [projection] **并列的可选缝**，缺省 null = 录屏未接线
     * （`startRecording` 如实 `ERR_NOT_IMPLEMENTED`，绝不退化成"录一段帧当视频"）。
     * 生产装配给的是同一个设备对象（两条腿共用一条 MediaProjection 会话账）。
     */
    private val recorder: ScreenRecordingController? = null,
) : RpcNamespaceHandler() {

    /** 申报方法表（wire-schema 对账挂点）：单源指向生成物 [WireMethods.BY_NS]，不手抄。 */
    override fun methods(): Set<String> = WireMethods.BY_NS.getValue("screen")

    private val guard = Any()

    /** 在场面表：一个 id 空间（id 单调递增、绝不复用），条目自己知道该走哪条缝。 */
    private val live = HashMap<Long, LiveSession>()
    private var nextSessionId = 1L

    /**
     * **录屏**条目按开启先后排的队（见 [trimRecordings]）—— 这是它唯一的在册凭据。
     *
     * 为什么录屏需要单独一条队、而取帧不需要：取帧条目收口即摘表（帧死了就是死了），
     * 而录屏条目**收口后要留着**（脚本随后 `stopRecording` 仍要答得出产物）。留着就得
     * 有界，否则一个长跑脚本开上万次录屏就是一条无界增长。
     *
     * 队里含**所有**录屏条目（不只是已收口的）：熄屏裁剪那条路上连接还活着，脚本
     * 可能再也没来 `stopRecording`，那种条目若不入队就永远没人回收。
     */
    private val recordingOrder = LinkedHashSet<Long>()

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

    /**
     * 录屏会话（§9.2 录屏腿）：与 [ProjectionSession] 并列而不是复用同一个类型。
     *
     * 为什么分型：两条腿的**收口语义不同** —— 取帧会话 `closeSession` 收完就摘表
     * （帧死了就是死了），录屏会话收口之后**必须仍答得出产物**（`stopRecording` 回
     * 路径/大小/完整性）。同一个类型会让"收口后摘不摘表"变成一个需要分支判断的问题，
     * 而分型之后它由 `when (entry)` 结构性回答。
     */
    private data class RecordingSession(
        override val generation: Long,
        override val owner: MediaProjectionSessionOwner,
        val handle: RecordingHandle,
    ) : LiveSession

    override suspend fun dispatch(request: BridgeRequest): BridgeResponse = when (request.method) {
        "capture" -> capture(request)
        "recycle" -> recycle(request)
        "startCapturer" -> startCapturer(request)
        "nextFrame" -> nextFrame(request)
        "closeSession" -> closeSession(request)
        "startRecording" -> startRecording(request)
        "stopRecording" -> stopRecording(request)
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

    /**
     * 录屏腿的连接级收口登记（与取帧腿同一条路，见 [connectionResourceRegistration]）。
     *
     * 与取帧腿**唯一**的差别是收口之后还多摘一次 [recordingOrder] —— 那边条目收口即摘表，
     * 这边条目收口后要留着（`stopRecording` 幂等），所以队列是它唯一的在册凭据，
     * 摘表不摘队就是一条只会增长、永远回收不到的漏。
     *
     * **这里摘表不丢产物信息**：撤销意味着**这条连接没了**，没人能再打 `stopRecording`
     * 了（bridge 的绑定随连接一起死）—— 留着条目只是留着一份没人问得到的信息。语义层
     * 那边同样按归属收口（`releaseConnection`），产物已经在收口时 finalize 落盘。
     * 与**熄屏裁剪**的分工见 [AppShellApplication.dropProjectionSession] 的 KDoc：
     * 那条路上连接还活着，脚本随后 `stopRecording` 仍要答得出产物，所以本表条目必须留着。
     */
    private suspend fun recordingResourceRegistration(owner: MediaProjectionSessionOwner, id: Long) {
        val ctx = kotlin.coroutines.coroutineContext[com.autoscript.domain.bridge.AuthenticatedRunContext]
            ?: return
        ctx.registerConnectionResource {
            // 连接没了 → 本表这条也摘掉（没人能再打它了）。**注意与熄屏裁剪的分工**：
            // 熄屏裁剪走的是 `AppShellApplication.dropProjectionSession` 直调设备层，
            // 不经这里 —— 那条路上连接还活着，脚本随后 `stopRecording` 仍要答得出产物，
            // 所以本表条目必须留着（见 [RecordingSession] 的 KDoc）。
            synchronized(guard) {
                live.remove(id)
                recordingOrder.remove(id)
            }
            recorder?.releaseConnection(owner)
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
            // 录屏会话没有帧可取（它的输出汇是文件，不是 ImageReader）。
            // **如实分类**而不是"回一帧看看"：录屏会话上取帧在设备层根本没有对应的资源。
            is RecordingSession -> err(
                request,
                ErrorCode.ERR_INVALID_PARAM,
                "会话 ${ref.refId} 是录屏会话，不能取帧（录屏产物是文件，用 stopRecording 收）",
            )
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
            // 录屏会话**不走这条**：`closeSession` 只回 `true`，而录屏收口要回
            // 路径/大小/完整性（`{path,sizeBytes,completed}`）—— 用它能收掉文件却把结果
            // 丢掉，等于让脚本"知道录完了但不知道录成了什么"。如实指向 `stopRecording`。
            is RecordingSession -> return err(
                request,
                ErrorCode.ERR_INVALID_PARAM,
                "会话 ${ref.refId} 是录屏会话，请用 stopRecording 收口（closeSession 不回产物信息）",
            )
        }
        return ok(request, "true")
    }

    /**
     * 开一条录屏会话（§9.2 录屏腿）。
     *
     * 归属与项目号**一次读齐**（[screenRecordingContextOrThrow]）：落点由项目号算，
     * 而项目号不能从 payload 取（脚本可影响 —— 拿它拼路径等于让脚本决定往哪个项目目录
     * 写文件）。没有已认证身份、或身份上没装填项目号 → `ERR_PERMISSION_DENIED`。
     */
    private suspend fun startRecording(request: BridgeRequest): BridgeResponse {
        val size = optSize(request.payload)
        val controller = recorder
            ?: return err(request, ErrorCode.ERR_NOT_IMPLEMENTED, "录屏未接线（装配期未提供录屏实现）")
        val broker = consent
            ?: return err(request, ErrorCode.ERR_CAPTURE_DENIED, "没有可用的系统投屏同意入口（界面未接线）")
        val ctx = screenRecordingContextOrThrow()
        val handle = controller.start(broker, ctx.owner, ctx.projectId, size.first ?: 0, size.second ?: 0)
        val id = synchronized(guard) {
            val nid = nextSessionId++
            live[nid] = RecordingSession(handle.generation, ctx.owner, handle)
            recordingOrder.add(nid)
            // 开新的就顺手回收最老的：**必须在登记之后**才可能超限，也顺手覆盖了
            // "熄屏裁剪收口了但脚本再没来 stopRecording"那种永远没人回收的条目。
            trimRecordings()
            nid
        }
        // 尺寸提示**只透传**（与 startCapturer 同一条口径）：设备层拿它当提示，不是承诺
        // —— 回包里不含尺寸，真正的尺寸由设备层按屏幕真值决定。
        recordingResourceRegistration(ctx.owner, id)
        return ok(request, recordingPayload(id, handle))
    }

    /**
     * 收一条录屏会话并 finalize（**幂等**：第二次回同一次结果）。
     *
     * 三道校验的顺序与取帧腿逐字同源：id 在场（`ERR_NOT_FOUND`）→ 代际一致
     * （`ERR_STALE_HANDLE`）→ **归属一致**（`ERR_PERMISSION_DENIED` —— 关别人的录屏
     * 等于替他断流并决定他的文件收在哪，与"找不到"对调用方含义完全不同）。
     *
     * **收口之后条目仍留在场面表**（见 [RecordingSession] 的 KDoc）：熄屏裁剪之后
     * 连接还活着，脚本再来一次仍答得出产物，而不是 `ERR_NOT_FOUND`。留着的条目由
     * [trimRecordings] 有界（连接撤销那条路由 [recordingResourceRegistration]
     * 直接摘掉 —— 连接没了就没人能再打它）。
     */
    private suspend fun stopRecording(request: BridgeRequest): BridgeResponse {
        val ref = requiredRef(decodePayload(request.payload), "session")
        val entry = synchronized(guard) { live[ref.refId] }
            ?: return err(request, ErrorCode.ERR_NOT_FOUND, "未知录屏会话 ${ref.refId}")
        if (entry.generation != ref.generation) {
            return err(
                request,
                ErrorCode.ERR_STALE_HANDLE,
                "录屏会话句柄跨代 ${ref.refId} gen=${ref.generation}（当前 ${entry.generation}）",
            )
        }
        if (entry !is RecordingSession) {
            return err(request, ErrorCode.ERR_INVALID_PARAM, "会话 ${ref.refId} 不是录屏会话")
        }
        val owner = entry.owner
        if (owner != mediaProjectionOwnerOrThrow()) {
            // **越权不动手**：关掉别人的录屏等于替他断流并决定他的文件收在哪。
            return err(
                request,
                ErrorCode.ERR_PERMISSION_DENIED,
                "录屏会话 ${ref.refId} 不属于本次执行，不能关闭",
            )
        }
        val controller = recorder
            ?: return err(request, ErrorCode.ERR_NOT_IMPLEMENTED, "录屏未接线")
        val outcome = controller.stop(entry.handle.refId, entry.handle.generation, owner)
        // **不摘条目**：它是幂等 `stopRecording` 的凭据 —— 第二次调用要回**同一次**结果
        // （框架收口之后脚本才来问的那条路也走这里）。回收交给 [trimRecordings]。
        return ok(request, recordingOutcomePayload(outcome))
    }

    /**
     * 丢弃最老的录屏条目（**必须在持有 [guard] 时调用**）。
     *
     * 上限与语义层的 `MediaProjectionRecorder.FINISHED_KEEP` **同值同义**：两边各留一份
     * 有界留档。丢早了那一侧回 `ERR_NOT_FOUND` —— 那是"产物信息不再在册"的如实回答，
     * 不是编一份出来。同值是为了让"还能问得到"这个窗口在两侧一致，否则会出现
     * "语义层答得出、handler 先回 NOT_FOUND"这种只能靠读代码才能解释的错位。
     *
     * **不会丢到活的那条**：语义层同一时刻只允许一条未收口的录屏会话（`ERR_CAPTURE_DENIED`），
     * 而它必然是队里最新的那条 —— 从队首丢够不到它。
     */
    private fun trimRecordings() {
        while (recordingOrder.size > RECORDINGS_KEEP) {
            val oldest = recordingOrder.first()
            recordingOrder.remove(oldest)
            live.remove(oldest)
        }
    }

    // ── 载荷 ─────────────────────────────────────────────────────────

    private fun sessionPayload(id: Long, generation: Long): String =
        DomainJson.encode(mapOf("session" to mapOf("refId" to id, "generation" to generation)))

    /**
     * 录屏回包：`{session:{refId,generation}, path}`。
     *
     * [path] 在这里（开的时候）就回，不是等 `stopRecording` —— 脚本崩了/被掐了时文件仍会被
     * 框架收口 finalize，那时脚本已经问不到 stop；路径只在 stop 回的话，那条产物就是
     * "存在但没人知道在哪"（见 `RecordingHandle` 的 KDoc）。
     */
    private fun recordingPayload(id: Long, handle: RecordingHandle): String =
        DomainJson.encode(
            mapOf(
                "session" to mapOf("refId" to id, "generation" to handle.generation),
                "path" to handle.path,
            ),
        )

    /**
     * 收口结果回包：`{path,sizeBytes,completed,detail?}`。
     *
     * [RecordingOutcome.detail] 只在 `completed = false` 时给 —— `null` 时**不发这个字段**
     * （而不是发一个 null）：JS 侧 `detail === undefined` 与 `detail === null` 对脚本是
     * 两个意思，缺省不发让"没有失败原因"只有一种表示。
     */
    private fun recordingOutcomePayload(o: RecordingOutcome): String {
        val m = LinkedHashMap<String, Any?>()
        m["path"] = o.path
        m["sizeBytes"] = o.sizeBytes
        m["completed"] = o.completed
        if (o.detail != null) m["detail"] = o.detail
        return DomainJson.encode(m)
    }

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

    private companion object {
        /**
         * 录屏条目的保留上限（见 [trimRecordings]）。
         *
         * 64 = `MediaProjectionRecorder.FINISHED_KEEP`：迟到的"再问一次"只可能是最近那几次
         * （脚本在收口之后紧接着读结果），不是几十次之前。
         */
        const val RECORDINGS_KEEP = 64
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
