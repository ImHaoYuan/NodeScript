package com.autoscript.platform.capabilities.a11y
import com.autoscript.domain.bridge.generated.WireMethods

import com.autoscript.domain.bridge.RpcNamespaceHandler
import com.autoscript.domain.bridge.BridgeResponse
import com.autoscript.domain.bridge.BridgeRequest
import com.autoscript.domain.json.DomainJson
import com.autoscript.domain.automation.GestureInput
import com.autoscript.domain.automation.GesturePoint
import com.autoscript.domain.automation.GestureStroke
import com.autoscript.domain.automation.InputProvider
import com.autoscript.domain.automation.ScrollDirection
import com.autoscript.domain.automation.UiActionExecutor
import com.autoscript.domain.automation.UiBounds
import com.autoscript.domain.automation.UiEventStream
import com.autoscript.domain.automation.UiNodeTreeReader
import com.autoscript.domain.automation.UiSelectorDsl
import com.autoscript.domain.bridge.HandleRef
import com.autoscript.domain.core.AutojsException
import com.autoscript.domain.core.ErrorCode
import com.autoscript.platform.capabilities.CapabilityNamespaces
import com.autoscript.platform.capabilities.screen.AndroidGestureInput
import com.autoscript.platform.capabilities.screen.InMemoryInputProvider

/**
 * `a11y` namespace 桥处理器（docs §9.1 / §12.3）：JS `a11y.*` 面的 Kotlin 对偶。
 *
 * 归属：住 `:platform:capabilities`；构造只收 `:domain` SPI
 *（[UiNodeTreeReader]/[UiActionExecutor]/[InputProvider]，事件流可选 [UiEventStream]，
 * 缺省走 `tree.events()`）。Android 真实现（AccessibilityNodeInfo 遍历 / dispatchGesture）
 * 只需实现这三块 SPI 即可替换内存树/输入 —— 本类逐行逻辑不变（替换 = 调
 * `CapabilityNamespaces.a11y` 时换 tree/actions/input/events 四个参数）。
 *
 * `:app` 装配层薄转接挂 BridgeRouter。载荷用 `:domain` 的 [DomainJson]（审查步骤 3
 * 合一后的仓内唯一 codec —— 各模块自带 codec 的旧形状已全部删除）。
 *
 * 方法表（与 `bridge/js` a11y.ts 一一对应）：
 * - `findOne`：payload `{conditions:{text?,desc?,id?,className?,packageName?,clickable?},
 *   timeout?,interval?}` → 首个匹配 `Ok {ref:{refId,generation},...attrs}`；
 *   无匹配 → Err ERR_NOT_FOUND（JS findOne 抛 NotFoundError，findOneOrNull 收 null——
 *   JS 侧按"Err NOT_FOUND → null"折叠，见 facade 注释）；
 * - `findAll`：payload `{conditions,max?}` → Ok `[{ref,...},...]`（max 截断，缺省全量）；
 * - `waitFor`：payload `{conditions:{...条件},timeout?,interval?}` → Ok `"true"`/`"false"`
 *   （命中/无匹配；**不回 `{ref}`** —— JS facade 的 `waitFor` 是 `Promise<boolean>`，
 *   见 §12.3 `const ok = await auto.a11y.waitFor(...)`。轮询是宿主责任：Android 侧
 *   监听事件流；这里是单次快照判定，timeout/interval 透传不生效，不伪造等待。
 *   参数错误仍是 Err ERR_INVALID_PARAM，不折成 false）；
 * - `click/longClick/scroll/copy/paste/setText/bounds/text/desc/children/parent/dispose`：
 *   payload `{ref:{refId,generation},...}` → 句柄动作；跨代/已释放 →
 *   Err ERR_STALE_HANDLE；非法载荷 → Err ERR_INVALID_PARAM；未知方法 →
 *   Err ERR_NOT_IMPLEMENTED；
 * - `events`：payload `{sinceSeq?,batch?}` → Ok `{first,last,events:[{seq,type,
 *   node:{refId,generation}|null,payload}]}`（节流拉取式事件流，seq 游标；
 *   batch 缺省 32，必须 > 0，否则 ERR_INVALID_PARAM）；
 * - `gesture`：payload `{strokes:[{points:[{x,y}],startDelayMillis?,durationMillis?}]}`
 *   → Ok `"true"/"false"`（关门 canPerformGestures=false → false，调用方走能力中心引导；
 *   服务未连 → Err ERR_SERVICE_DISABLED 原码，不折 false）；
 *   非法手势 → ERR_INVALID_PARAM，绝不发往系统服务）；
 * - `canPerformGestures`：无参 → Ok `"true"/"false"`。
 */
class A11yNamespaceHandler(
    private val tree: UiNodeTreeReader,
    private val actions: UiActionExecutor,
    private val input: InputProvider = InMemoryInputProvider(),
    /**
     * 事件流覆写（缺省 null = 走 `tree.events()`）。内存树自带流；Android 真实现若把
     * 事件监听做在树之外，可显式注入 —— handler 不关心事件从哪来，只认游标契约。
     */
    private val events: UiEventStream? = null,
) : RpcNamespaceHandler() {


    /** 申报方法表（wire-schema 对账挂点）：单源指向生成物 [WireMethods.BY_NS]，不手抄。 */
    override fun methods(): Set<String> = WireMethods.BY_NS.getValue("a11y")

    override suspend fun dispatch(request: BridgeRequest): BridgeResponse = when (request.method) {
        "findOne" -> findOne(request, single = true)
        "findOneOrNull" -> findOne(request, single = true)
        "findAll" -> findAll(request)
        "waitFor" -> waitFor(request)
        "click" -> boolAction(request) { ref -> actions.click(ref) }
        "longClick" -> boolAction(request) { ref -> actions.longClick(ref) }
        "scroll" -> scroll(request)
        "copy" -> boolAction(request) { ref -> actions.copy(ref) }
        "paste" -> boolAction(request) { ref -> actions.paste(ref) }
        "setText" -> setText(request)
        "bounds" -> bounds(request)
        "text" -> attr(request, "text")
        "desc" -> attr(request, "desc")
        "children" -> children(request)
        "parent" -> parent(request)
        "dispose" -> dispose(request)
        "events" -> events(request)
        "gesture" -> gesture(request)
        // 服务未连时 AndroidGestureInput 抛 ERR_SERVICE_DISABLED：原码回桥（不折 false ——
        // "没服务"与"手势关门"是两回事，后者才走能力中心引导）。
        "canPerformGestures" -> run {
            ok(request, if (input.canPerformGestures) "true" else "false")
        }
        else -> err(request, ErrorCode.ERR_NOT_IMPLEMENTED, "未知 a11y 方法: ${request.method}")
    }

    // ── 查找 ─────────────────────────────────────────────────────────

    private suspend fun findOne(request: BridgeRequest, single: Boolean): BridgeResponse {
        val o = decodePayload(request.payload)
        val selector = selectorOf(o["conditions"])
        val matched = tree.findBySelector(selector)
        if (matched.isEmpty()) {
            return err(request, ErrorCode.ERR_NOT_FOUND, "选择器无匹配")
        }
        if (!single) {
            return ok(request, DomainJson.encode(matched.map { nodePayload(it.handle, null) }))
        }
        val first = matched.first()
        return ok(request, DomainJson.encode(nodePayload(first.handle, null)))
    }

    /**
     * 等待条件出现（§9.1「轮询是宿主责任」）：与 [findOne] 共用选择器解析与树读路径，
     * 但**返回形状不同** —— JS facade `a11y.ts` 的 `waitFor` 是 `Promise<boolean>`
     * （§12.3 `const ok = await auto.a11y.waitFor(...)`），所以命中回 `Ok "true"`、
     * 无匹配回 `Ok "false"`（与 click/gesture 的 boolean 口径一致），**不回** `{ref}`。
     *
     * 仍不伪造等待：本方法是单次快照判定，`timeout`/`interval` 只透传不生效 ——
     * 真正的轮询在宿主（Android 侧监听事件流后重发）。把「没等到」折成 `false`
     * 而不是 `Err NOT_FOUND`，是因为调用方拿它做分支判断（`if (await waitFor(...))`），
     * 不是当异常处理；**参数错误仍是 Err**（ERR_INVALID_PARAM），不一并折成 false。
     */
    private suspend fun waitFor(request: BridgeRequest): BridgeResponse {
        val o = decodePayload(request.payload)
        val selector = selectorOf(o["conditions"])
        val matched = tree.findBySelector(selector)
        return ok(request, if (matched.isEmpty()) "false" else "true")
    }

    private suspend fun findAll(request: BridgeRequest): BridgeResponse {
        val o = decodePayload(request.payload)
        val selector = selectorOf(o["conditions"])
        val max = optLong(o, "max")?.toInt() ?: Int.MAX_VALUE
        if (max < 0) return err(request, ErrorCode.ERR_INVALID_PARAM, "max 不得为负")
        val matched = tree.findBySelector(selector)
        return ok(request, DomainJson.encode(matched.take(max).map { nodePayload(it.handle, null) }))
    }

    // ── 动作 ─────────────────────────────────────────────────────────

    private suspend fun boolAction(request: BridgeRequest, run: suspend (HandleRef) -> Boolean): BridgeResponse {
        val ref = requiredRef(decodePayload(request.payload))
        return ok(request, if (run(ref)) "true" else "false")
    }

    private suspend fun setText(request: BridgeRequest): BridgeResponse {
        val o = decodePayload(request.payload)
        val ref: HandleRef
        val text: String
        run {
            ref = requiredRef(o)
            text = requiredStr(o, "text")
        }
        return ok(request, if (actions.setText(ref, text)) "true" else "false")
    }

    /**
     * 滚动：payload `{ref,direction?}`（direction 缺省 FORWARD；非法方向名 →
     * ERR_INVALID_PARAM）。不可滚动容器回 `"false"`（不抛错，与 click 同口径）。
     */
    private suspend fun scroll(request: BridgeRequest): BridgeResponse {
        val o = decodePayload(request.payload)
        val ref: HandleRef
        val direction: ScrollDirection
        run {
            ref = requiredRef(o)
            direction = optDirection(o, "direction") ?: ScrollDirection.FORWARD
        }
        return ok(request, if (actions.scroll(ref, direction)) "true" else "false")
    }

    private suspend fun bounds(request: BridgeRequest): BridgeResponse {
        val ref = requiredRef(decodePayload(request.payload))
        val b: UiBounds? = actions.bounds(ref)
        if (b == null) return ok(request, null)
        return ok(request, DomainJson.encode(mapOf("left" to b.left.toLong(), "top" to b.top.toLong(), "right" to b.right.toLong(), "bottom" to b.bottom.toLong())),
        )
    }

    private suspend fun attr(request: BridgeRequest, name: String): BridgeResponse {
        val ref = requiredRef(decodePayload(request.payload))
        val v = actions.attribute(ref, name)
        return ok(request, if (v == null) null else DomainJson.encode(v))
    }

    private suspend fun children(request: BridgeRequest): BridgeResponse {
        val ref = requiredRef(decodePayload(request.payload))
        val kids = actions.children(ref)
        return ok(request, DomainJson.encode(kids.map { nodePayload(it.handle, null) }))
    }

    private suspend fun parent(request: BridgeRequest): BridgeResponse {
        val ref = requiredRef(decodePayload(request.payload))
        val p = actions.parent(ref)
        return ok(request, if (p == null) null else DomainJson.encode(nodePayload(p.handle, null)))
    }

    private suspend fun dispose(request: BridgeRequest): BridgeResponse {
        val ref = requiredRef(decodePayload(request.payload))
        return run {
            actions.dispose(ref)
            ok(request, "true")
        }
    }

    /**
     * 事件流拉取（§9.1 节流拉取式，seq 游标）：payload `{sinceSeq?,batch?}`。
     * 空增量回 `{first:sinceSeq,last:sinceSeq,events:[]}`（调用方以前进游标为准，
     * 不以空数组为终结——事件是开放流）。
     */
    private suspend fun events(request: BridgeRequest): BridgeResponse {
        val o = if (request.payload == null) emptyMap() else decodePayload(request.payload)
        val sinceSeq: Long
        val batch: Int
        run {
            sinceSeq = optLong(o, "sinceSeq") ?: 0L
            batch = (optLong(o, "batch") ?: 32L).toInt()
            require(batch > 0) { "batch 必须 > 0" }
        }
        val got = (events ?: tree.events()).next(sinceSeq, batch)
        return ok(request, DomainJson.encode(
                mapOf(
                    "first" to got.firstSeq,
                    "last" to got.lastSeq,
                    "events" to got.events.map {
                        mapOf(
                            "seq" to it.seq,
                            "type" to it.type,
                            "node" to (it.nodeHandle?.let { h ->
                                mapOf("refId" to h.refId, "generation" to h.generation)
                            }),
                            "payload" to it.payload,
                        )
                    },
                ),
            ),
        )
    }

    /**
     * 手势派发：payload `{strokes:[{points:[{x,y}],startDelayMillis?,durationMillis?}]}`。
     * 构造器校验非法（空笔画/负坐标/非正 duration）→ ERR_INVALID_PARAM；
     * 关门（canPerformGestures=false）→ `"false"`（不抛错，走能力中心引导）；
     * 服务未连 → ERR_SERVICE_DISABLED 原码（不折 false，与"关门"区分）。
     */
    private suspend fun gesture(request: BridgeRequest): BridgeResponse {
        val o = decodePayload(request.payload)
        val gesture = gestureOf(o["strokes"])
        return ok(request, if (input.dispatchGesture(gesture)) "true" else "false")
    }

    private fun gestureOf(v: DomainJson.Value?): GestureInput {
        if (v !is DomainJson.Value.Arr) throw IllegalArgumentException("strokes 必须是数组")
        return GestureInput(
            v.items.map { s ->
                val o = (s as? DomainJson.Value.Obj)?.fields
                    ?: throw IllegalArgumentException("stroke 必须是对象")
                val points = (o["points"] as? DomainJson.Value.Arr)
                    ?: throw IllegalArgumentException("stroke.points 必须是数组")
                GestureStroke(
                    points = points.items.map { p ->
                        val f = (p as? DomainJson.Value.Obj)?.fields
                            ?: throw IllegalArgumentException("point 必须是 {x,y} 对象")
                        val x = (f["x"] as? DomainJson.Value.N)?.raw?.toIntOrNull()
                            ?: throw IllegalArgumentException("point.x 必须是非负整数")
                        val y = (f["y"] as? DomainJson.Value.N)?.raw?.toIntOrNull()
                            ?: throw IllegalArgumentException("point.y 必须是非负整数")
                        GesturePoint(x, y)
                    },
                    startDelayMillis = optLong(o, "startDelayMillis") ?: 0L,
                    durationMillis = optLong(o, "durationMillis") ?: 100L,
                )
            },
        )
    }

    // ── 载荷 ─────────────────────────────────────────────────────────

    private fun nodePayload(ref: HandleRef, extra: Map<String, Any?>?): Map<String, Any?> =
        mapOf("ref" to mapOf("refId" to ref.refId, "generation" to ref.generation))

    private fun decodePayload(payload: String?): Map<String, DomainJson.Value> {
        if (payload == null) throw IllegalArgumentException("缺 payload")
        return DomainJson.decodeObject(payload)
    }

    private fun selectorOf(v: DomainJson.Value?): UiSelectorDsl {
        if (v == null || v is DomainJson.Value.Null) return UiSelectorDsl.builder()
        if (v !is DomainJson.Value.Obj) throw IllegalArgumentException("conditions 必须是对象")
        val o = v.fields
        // 白名单字段：未知键如实拒绝（防拼写错误静默变全量匹配）。
        for (k in o.keys) {
            if (k !in SELECTOR_KEYS) throw IllegalArgumentException("未知选择器条件 $k")
        }
        return UiSelectorDsl.builder().copyWith(
            text = optStr(o, "text"),
            desc = optStr(o, "desc"),
            className = optStr(o, "className"),
            packageName = optStr(o, "packageName"),
            id = optStr(o, "id"),
            clickable = optBool(o, "clickable"),
        )
    }

    private fun requiredRef(o: Map<String, DomainJson.Value>): HandleRef {
        val v = o["ref"] ?: throw IllegalArgumentException("缺 ref 字段")
        if (v !is DomainJson.Value.Obj) throw IllegalArgumentException("ref 必须是对象")
        val refId = (v.fields["refId"] as? DomainJson.Value.N)?.raw?.toLongOrNull()
            ?: throw IllegalArgumentException("缺数字 ref.refId")
        val gen = (v.fields["generation"] as? DomainJson.Value.N)?.raw?.toLongOrNull()
            ?: throw IllegalArgumentException("缺数字 ref.generation")
        return HandleRef(refId, gen)
    }

    private fun requiredStr(o: Map<String, DomainJson.Value>, key: String): String =
        (o[key] as? DomainJson.Value.S)?.v ?: throw IllegalArgumentException("缺字符串字段 $key")

    private fun optStr(o: Map<String, DomainJson.Value>, key: String): String? {
        val v = o[key] ?: return null
        if (v is DomainJson.Value.Null) return null
        return (v as? DomainJson.Value.S)?.v ?: throw IllegalArgumentException("字段 $key 必须是字符串")
    }

    private fun optBool(o: Map<String, DomainJson.Value>, key: String): Boolean? {
        val v = o[key] ?: return null
        if (v is DomainJson.Value.Null) return null
        return (v as? DomainJson.Value.B)?.v ?: throw IllegalArgumentException("字段 $key 必须是布尔")
    }

    private fun optLong(o: Map<String, DomainJson.Value>, key: String): Long? {
        val v = o[key] ?: return null
        if (v is DomainJson.Value.Null) return null
        return (v as? DomainJson.Value.N)?.raw?.toLongOrNull()
            ?: throw IllegalArgumentException("字段 $key 必须是数字")
    }

    private fun optDirection(o: Map<String, DomainJson.Value>, key: String): ScrollDirection? {
        val v = o[key] ?: return null
        if (v is DomainJson.Value.Null) return null
        val name = (v as? DomainJson.Value.S)?.v
            ?: throw IllegalArgumentException("字段 $key 必须是方向名字符串")
        return try {
            ScrollDirection.valueOf(name.uppercase())
        } catch (_: IllegalArgumentException) {
            throw IllegalArgumentException("未知滚动方向 $name（FORWARD/BACKWARD/UP/DOWN/LEFT/RIGHT）")
        }
    }

    companion object {
        val SELECTOR_KEYS: Set<String> = setOf("text", "desc", "id", "className", "packageName", "clickable")
    }
}
