package com.autoscript.appservice.runtime
import com.autoscript.domain.bridge.generated.WireMethods

import com.autoscript.domain.bridge.BridgeRequest
import com.autoscript.domain.bridge.BridgeResponse
import com.autoscript.domain.bridge.RpcNamespaceHandler
import com.autoscript.domain.core.ErrorCode
import com.autoscript.domain.json.DomainJson

/**
 * `engines` namespace 桥处理器（docs §8 / §12.3）：JS `engines.*` 面的 Kotlin 对偶。
 *
 * 归属说明：本类住在 `:app-service:runtime`（不是 `:bridge:java`），因为它直接驱动
 * [RuntimeController]/[EnginePool]；本类即 `NamespaceHandler`（承 [RpcNamespaceHandler]），
 * `:app` 装配层 `router.register("engines", it)` 直挂 `BridgeRouter`。
 * 载荷编解码用 `:domain` 的 [DomainJson]（审查步骤 3 合一后的仓内唯一 codec ——
 * 原 EngineBridgeJson 已删，不再有「各模块自带一份」的形状）。
 *
 * 方法表（与 `bridge/js` engines.ts 一一对应）：
 * - `exec`：payload `{projectId,scriptPath,args?,runNonce?,timeoutMillis?,waitTimeoutMillis?}` →
 *   [RuntimeController.start]；Started → Ok `{runId,handle:{refId,generation}}`，
 *   QueueTimeout → Err ERR_TIMEOUT，StartFailed → Err ERR_ENGINE_CRASHED；
 *   排队上限 = payload `waitTimeoutMillis` 优先，否则桥侧 [BridgeRequest.ttlMillis]
 *   （§7.4 每次跨进程操作必有 TTL）——没有上限时满池即无限等，只能靠调用方取消兜底，
 *   那条路径无法诚实回 ERR_TIMEOUT，故必须把 TTL 递进池；
 *   **执行时限必须显式声明**：桥这条路拿到句柄就返回，没人 await 终结，所以
 *   `timeoutMillis` 缺席不是"用缺省"而是**构造即拒**（[ErrorCode.ERR_INVALID_PARAM]）——
 *   否则这条 run 落回"没人收尾"那一类，只能等看门狗按心跳/CPU/RSS 判死，而心跳正常的
 *   长跑脚本永远判不到。声明了期限的走 [TimeoutEnforcer.WATCHDOG]：到点由看门狗落
 *   `KillCause.TIMEOUT`（§8.6「没人 await 的必须自带期限」）；
 * - `stop`：payload `{runId}` → StoppedClean → Ok `true`；
 *   StoppedTimeout → Err ERR_TIMEOUT（软停未干净完成，已 kill 兜底，如实报错不伪造成功）；
 *   AlreadyGone → Err ERR_NOT_FOUND（未知 runId 不静默吞掉）；
 * - `poolStats`：无参 → Ok `{capacity,free,busy}`；
 * - `status`：payload `{runId}` → 在途则 Ok 引擎状态名字符串（`"RUNNING"` 等，
 *   与 `:domain EngineStatus` 枚举名逐字一致，JS `EngineStatus` 字面量对齐）；
 *   不在途（已结算/从未存在）→ Err ERR_NOT_FOUND —— 与 `stop` 的 AlreadyGone 同一条
 *   诚实口径：结算后无状态可读，不得伪造一个 `"STOPPED"`（那会把"查不到"伪装成
 *   "正常结束"，`onExit` 的终态判断会因此错过 CRASHED）。JS `onExit` 的轮询地基；
 * - `heartbeat`：payload `{runId,seq}` → [RuntimeController.heartbeat]（§8.4 缺口②的
 *   宿主侧收单方；JS 侧定时打点）。同/旧 seq 不刷时间戳 → Ok `false`（如实告知未被采纳，
 *   不是错误）；未知 runId（不在途/已结算/从未存在）→ 仍 Ok `false` **且不记账** ——
 *   "不知道这个 run"本身不是调用方错误，但也不得把它伪装成一次有效心跳，更不得让无主
 *   条目堆积顶出活 run 的账（[RuntimeController.heartbeat] 先验在途再落账本）；
 * - `channel`：payload `{name}` → 创建或复用命名通道 → Ok `{name,channelId}`；
 * - `channelEmit`：payload `{channelId,event,payload?}` → 记入通道事件缓冲 → Ok null；
 * - `channelDrain`：payload `{channelId,sinceSeq?,max?}` → 游标拉取（供 :app 层经
 *   EventBus/TSF 转发给订阅端，见领域注释：退出/事件推送走桥 EventBus）；
 * - `channelClose`：payload `{channelId}` → 关闭并丢弃缓冲 → Ok `true`；
 * - 未知方法 → Err ERR_NOT_IMPLEMENTED；非法载荷 → Err ERR_INVALID_PARAM。
 */
class EnginesNamespaceHandler(
    private val controller: RuntimeController,
    private val channelCapacity: Int = DEFAULT_CHANNEL_CAPACITY,
) : RpcNamespaceHandler() {

    /** 申报方法表（wire-schema 对账挂点）：单源指向生成物 [WireMethods.BY_NS]，不手抄。 */
    override fun methods(): Set<String> = WireMethods.BY_NS.getValue("engines")

    override suspend fun dispatch(request: BridgeRequest): BridgeResponse = when (request.method) {
        "exec" -> exec(request)
        "stop" -> stop(request)
        "poolStats" -> ok(request, DomainJson.encode(poolStatsPayload()))
        "status" -> status(request)
        "heartbeat" -> heartbeat(request)
        "channel" -> channel(request)
        "channelEmit" -> channelEmit(request)
        "channelDrain" -> channelDrain(request)
        "channelClose" -> channelClose(request)
        else -> err(request, ErrorCode.ERR_NOT_IMPLEMENTED, "未知 engines 方法: ${request.method}")
    }

    // ── exec / stop / poolStats ──────────────────────────────────────────

    private suspend fun exec(request: BridgeRequest): BridgeResponse {
        val p = parseExec(request.payload)
        val bounded = p.copy(waitTimeoutMillis = p.waitTimeoutMillis ?: request.ttlMillis)
        return when (val outcome = controller.start(bounded)) {
            is RuntimeController.StartOutcome.Started -> ok(
                request,
                DomainJson.encode(
                    mapOf(
                        "runId" to outcome.runId,
                        // 会话句柄身份 = runId（单次 exec→stop 生命周期，无 re-acquire，
                        // 故 generation 恒 1；与 bridge HandleRegistry 的跨代语义不冲突——
                        // 本句柄从不复用，旧引用无"新资源"可误操作）。
                        "handle" to mapOf("refId" to outcome.runId, "generation" to 1L),
                    ),
                ),
            )
            RuntimeController.StartOutcome.QueueTimeout ->
                err(request, ErrorCode.ERR_TIMEOUT, "引擎池排队超时")
            is RuntimeController.StartOutcome.StartFailed ->
                err(request, ErrorCode.ERR_ENGINE_CRASHED, outcome.message)
        }
    }

    private suspend fun stop(request: BridgeRequest): BridgeResponse {
        val runId = requiredLong(decodePayload(request.payload), "runId")
        return when (val outcome = controller.stop(runId)) {
            RuntimeController.StopOutcome.StoppedClean -> ok(request, "true")
            is RuntimeController.StopOutcome.StoppedTimeout ->
                err(request, ErrorCode.ERR_TIMEOUT, "软停超时(partial=${outcome.partial})，已 kill 兜底")
            RuntimeController.StopOutcome.AlreadyGone ->
                err(request, ErrorCode.ERR_NOT_FOUND, "未知 runId: $runId")
        }
    }

    /**
     * 引擎侧状态快照（JS `onExit` 轮询的地基，见方法表 `status` 条）。
     *
     * 只读在途表（[RuntimeController.probeStatus]）：不在途 → null → 如实 NOT_FOUND。
     * 宿主探针抛错同样落 null（引擎已死/实现未接线）—— 那是"量不到"，与"已结算"
     * 在本方法不区分：两者都意味着"没有可读的活状态"，调用方按"本次轮询无结果、
     * 下一轮再问"处理，不得把 null 翻译成任何终态。
     */
    private suspend fun status(request: BridgeRequest): BridgeResponse {
        val runId = requiredLong(decodePayload(request.payload), "runId")
        val st = controller.probeStatus(runId)
            ?: return err(request, ErrorCode.ERR_NOT_FOUND, "未知 runId: $runId")
        return ok(request, DomainJson.encode(st.name))
    }

    private fun poolStatsPayload(): Map<String, Any?> {
        val s = controller.stats()
        return mapOf("capacity" to s.capacity.toLong(), "free" to s.free.toLong(), "busy" to s.busy.toLong())
    }

    /**
     * 心跳打点（§8.4 缺口②）。seq 由引擎侧自增：落后/重复的帧被账本拒收
     * （不刷时间戳），否则宿主张力下积压的旧心跳会让死掉的 run 一直"活着"。
     * 不在途 runId（已结算/从未存在）→ 同样 Ok `false` 且不记账（见
     * [RuntimeController.heartbeat]）：那不是调用方错误，但也不得伪装成有效心跳。
     */
    private suspend fun heartbeat(request: BridgeRequest): BridgeResponse {
        val o = decodePayload(request.payload)
        val runId = requiredLong(o, "runId")
        val seq = requiredLong(o, "seq")
        val accepted = controller.heartbeat(runId, seq)
        return ok(request, DomainJson.encode(accepted))
    }

    private fun parseExec(payload: String?): PoolAcquireRequest {
        val o = decodePayload(payload)
        val projectId = requiredStr(o, "projectId")
        val scriptPath = requiredStr(o, "scriptPath")
        if (projectId.isBlank()) throw IllegalArgumentException("projectId 不得为空")
        if (scriptPath.isBlank()) throw IllegalArgumentException("scriptPath 不得为空")
        // 桥这条路的执行时限**必须显式给**（见方法表 `exec` 条）：拿到句柄就返回、没人 await
        // 终结，缺省值在这里没有诚实来源 —— 编一个（30s？5min？）等于替脚本决定它能跑多久，
        // 而且是静默的。与 §8.6 queueTimeoutMillis 传 0 视为漏配同一条纪律：响亮失败。
        val timeout = optLong(o, "timeoutMillis")
            ?: throw IllegalArgumentException("exec 必须显式给 timeoutMillis（桥路径无人 await 终结，期限须由调用方声明；§8.6）")
        if (timeout <= 0) throw IllegalArgumentException("timeoutMillis 必须 > 0：$timeout")
        return PoolAcquireRequest(
            projectId = projectId,
            scriptPath = scriptPath,
            args = optStrList(o, "args"),
            runNonce = optStr(o, "runNonce"),
            scriptTimeoutMillis = timeout,
            waitTimeoutMillis = optLong(o, "waitTimeoutMillis"),
            timeoutEnforcer = TimeoutEnforcer.WATCHDOG,
        )
    }

    // ── 命名通道 ─────────────────────────────────────────────────────────
    //
    // 通道是脚本↔宿主 JSON 事件的命名缓冲（§8 RuntimeChannel）。订阅推送（host→script）
    // 走桥 EventBus/TSF（:app 层经 channelDrain 拉取后转发）；本层只做缓冲 + 游标，
    // 不做回调推送（与 ConsoleCollector/EventBus 的"节流拉取"语义一致）。

    private data class ChannelState(
        val channelId: Long,
        val name: String,
        val events: ArrayDeque<ChannelEvent> = ArrayDeque(),
        var nextSeq: Long = 1,
        var dropped: Long = 0,
        var closed: Boolean = false,
    )

    data class ChannelEvent(val seq: Long, val event: String, val payload: String?)

    private val channels = HashMap<Long, ChannelState>()
    private val byName = HashMap<String, Long>()
    private var nextChannelId = 1L
    private val channelGuard = Any()

    private fun channel(request: BridgeRequest): BridgeResponse {
        val o = decodePayload(request.payload)
        val name = requiredStr(o, "name")
        if (name.isBlank()) return err(request, ErrorCode.ERR_INVALID_PARAM, "通道名不得为空")
        val id = synchronized(channelGuard) {
            val existing = byName[name]
            if (existing != null && channels[existing]?.closed == false) {
                existing
            } else {
                val nid = nextChannelId++
                channels[nid] = ChannelState(channelId = nid, name = name)
                byName[name] = nid
                nid
            }
        }
        return ok(request, DomainJson.encode(mapOf("name" to name, "channelId" to id)))
    }

    private fun channelEmit(request: BridgeRequest): BridgeResponse {
        val o = decodePayload(request.payload)
        val channelId = requiredLong(o, "channelId")
        val event = requiredStr(o, "event")
        val payload = optStr(o, "payload")
        if (event.isBlank()) return err(request, ErrorCode.ERR_INVALID_PARAM, "事件名不得为空")
        synchronized(channelGuard) {
            val state = channels[channelId]
                ?: return err(request, ErrorCode.ERR_NOT_FOUND, "未知 channelId: $channelId")
            if (state.closed) return err(request, ErrorCode.ERR_NOT_FOUND, "通道已关闭: $channelId")
            state.events.addLast(ChannelEvent(seq = state.nextSeq++, event = event, payload = payload))
            while (state.events.size > channelCapacity) {
                state.events.removeFirst()
                state.dropped++
            }
        }
        return ok(request, null)
    }

    private fun channelDrain(request: BridgeRequest): BridgeResponse {
        val o = decodePayload(request.payload)
        val channelId = requiredLong(o, "channelId")
        val sinceSeq = optLong(o, "sinceSeq") ?: 0L
        val max = (optLong(o, "max") ?: 128L).toInt()
        require(max > 0) { "max 必须 > 0" }
        val picked: List<ChannelEvent>
        var last = sinceSeq
        synchronized(channelGuard) {
            val state = channels[channelId]
                ?: return err(request, ErrorCode.ERR_NOT_FOUND, "未知 channelId: $channelId")
            picked = state.events.filter { it.seq > sinceSeq }.take(max)
            for (e in picked) last = e.seq
        }
        return ok(
            request,
            DomainJson.encode(
                mapOf(
                    "last" to last,
                    "events" to picked.map {
                        mapOf("seq" to it.seq, "event" to it.event, "payload" to it.payload)
                    },
                ),
            ),
        )
    }

    private fun channelClose(request: BridgeRequest): BridgeResponse {
        val channelId = requiredLong(decodePayload(request.payload), "channelId")
        synchronized(channelGuard) {
            val state = channels.remove(channelId)
                ?: return err(request, ErrorCode.ERR_NOT_FOUND, "未知 channelId: $channelId")
            state.closed = true
            byName.remove(state.name)
        }
        return ok(request, "true")
    }

    // ── 载荷读取 ─────────────────────────────────────────────────────────

    private fun decodePayload(payload: String?): Map<String, DomainJson.Value> {
        if (payload == null) throw IllegalArgumentException("缺 payload")
        return DomainJson.decodeObject(payload)
    }

    private fun requiredStr(o: Map<String, DomainJson.Value>, key: String): String =
        (o[key] as? DomainJson.Value.S)?.v ?: throw IllegalArgumentException("缺字符串字段 $key")

    private fun optStr(o: Map<String, DomainJson.Value>, key: String): String? {
        val v = o[key] ?: return null
        if (v is DomainJson.Value.Null) return null
        return (v as? DomainJson.Value.S)?.v ?: throw IllegalArgumentException("字段 $key 必须是字符串")
    }

    private fun requiredLong(o: Map<String, DomainJson.Value>, key: String): Long =
        (o[key] as? DomainJson.Value.N)?.raw?.toLongOrNull()
            ?: throw IllegalArgumentException("缺数字字段 $key")

    private fun optLong(o: Map<String, DomainJson.Value>, key: String): Long? {
        val v = o[key] ?: return null
        if (v is DomainJson.Value.Null) return null
        return (v as? DomainJson.Value.N)?.raw?.toLongOrNull()
            ?: throw IllegalArgumentException("字段 $key 必须是数字")
    }

    private fun optStrList(o: Map<String, DomainJson.Value>, key: String): List<String> {
        val v = o[key] ?: return emptyList()
        if (v is DomainJson.Value.Null) return emptyList()
        if (v !is DomainJson.Value.Arr) throw IllegalArgumentException("字段 $key 必须是数组")
        return v.items.map {
            (it as? DomainJson.Value.S)?.v ?: throw IllegalArgumentException("字段 $key 数组元素必须是字符串")
        }
    }

    companion object {
        const val DEFAULT_CHANNEL_CAPACITY = 256
    }
}
