package com.autoscript.bridge

import com.autoscript.domain.bridge.BridgeRequest
import com.autoscript.domain.bridge.BridgeResponse
import com.autoscript.domain.json.DomainJson
import java.nio.charset.StandardCharsets

/**
 * 桥信封 JSON 编码（控制面小对象）。主结构：
 *
 * 请求 {"t":"req","id":1,"ns":"a11y","m":"findOne","ttl":5000,"payload":<json|null>,"side":<long|null>}
 * 成功 {"t":"ok","id":1,"payload":<json|null>,"side":<long|null>}
 * 错误 {"t":"err","id":1,"code":"ERR_*","detail":<string|null>}
 *
 * "side" 为传输层扩展：大二进制（Bitmap/像素）走 §7.4 side-channel 时携带句柄引用；
 * 领域类型 BridgeRequest/BridgeResponse 不感知（payload 内自持，由实现方约定）。
 *
 * 编解码走 `:domain` [DomainJson]（仓内唯一 codec —— 原 TinyJson 已删）；
 * [decodeFlat] 的 allowed 白名单是**传输层协议纪律**（未知字段如实拒绝，防乱码注入），
 * 不是 codec 的一部分。
 */
class JsonTransport : BridgeTransport {

    private val allowedReq = setOf("t", "id", "ns", "m", "ttl", "payload", "side")

    override fun encodeRequest(request: BridgeRequest): ByteArray = DomainJson.encode(
        mapOf(
            "t" to "req",
            "id" to request.id,
            "ns" to request.namespace,
            "m" to request.method,
            "ttl" to request.ttlMillis,
            "payload" to request.payload,
            "side" to null,
        ),
    ).toByteArray(StandardCharsets.UTF_8)

    override fun decodeRequest(bytes: ByteArray): BridgeRequest {
        val m = decodeFlat(String(bytes, StandardCharsets.UTF_8), allowedReq)
        require(m["t"] == DomainJson.Value.S("req")) { "非请求信封" }
        return BridgeRequest(
            id = num(m, "id").toLong(),
            namespace = str(m, "ns"),
            method = str(m, "m"),
            payload = payloadOrNull(m),
            ttlMillis = num(m, "ttl").toLong(),
        )
    }

    override fun encodeResponse(response: BridgeResponse): ByteArray = when (response) {
        is BridgeResponse.Ok -> DomainJson.encode(
            mapOf(
                "t" to "ok",
                "id" to response.id,
                "payload" to response.payload,
                "side" to null,
            ),
        )
        is BridgeResponse.Err -> DomainJson.encode(
            mapOf(
                "t" to "err",
                "id" to response.id,
                "code" to response.errorCode,
                "detail" to response.detail,
            ),
        )
    }.toByteArray(StandardCharsets.UTF_8)

    override fun decodeResponse(bytes: ByteArray): BridgeResponse {
        val m = decodeFlat(String(bytes, StandardCharsets.UTF_8), setOf("t", "id", "payload", "side", "code", "detail"))
        val id = num(m, "id").toLong()
        return when ((m["t"] as? DomainJson.Value.S)?.v) {
            "ok" -> BridgeResponse.Ok(id, payloadOrNull(m))
            "err" -> BridgeResponse.Err(id, str(m, "code"), detailOrNull(m))
            else -> throw IllegalArgumentException("未知响应类型")
        }
    }

    /** payload 契约：字符串（JSON 编码参数）或 null。非字符串值显式拒绝 —— 静默丢 null 会让 handler 收到无参请求。 */
    private fun payloadOrNull(m: Map<String, DomainJson.Value>): String? = when (val f = m["payload"]) {
        null, DomainJson.Value.Null -> null
        is DomainJson.Value.S -> f.v
        is DomainJson.Value.N -> throw IllegalArgumentException("payload 必须是 JSON 字符串或 null（收到数字 ${f.raw}）")
        else -> throw IllegalArgumentException("payload 必须是 JSON 字符串或 null（收到 ${f::class.simpleName}）")
    }

    /** detail 同理：字符串或 null，绝不静默丢弃。 */
    private fun detailOrNull(m: Map<String, DomainJson.Value>): String? = when (val f = m["detail"]) {
        null, DomainJson.Value.Null -> null
        is DomainJson.Value.S -> f.v
        is DomainJson.Value.N -> throw IllegalArgumentException("detail 必须是字符串或 null（收到数字 ${f.raw}）")
        else -> throw IllegalArgumentException("detail 必须是字符串或 null（收到 ${f::class.simpleName}）")
    }

    private fun str(m: Map<String, DomainJson.Value>, k: String): String =
        (m[k] as? DomainJson.Value.S)?.v ?: throw IllegalArgumentException("缺少字符串字段 $k")

    private fun num(m: Map<String, DomainJson.Value>, k: String): String =
        (m[k] as? DomainJson.Value.N)?.raw ?: throw IllegalArgumentException("缺少数字字段 $k")
}

/**
 * 扁平信封解码 + allowed 白名单（原 `TinyJson.decode(text, allowed)` 的语义逐字保留：
 * 未知字段即拒，防乱码注入）。值域是 [DomainJson] 的六值族 —— 取用点各自按 S/N 严格取型，
 * 信封外层不做值型裁剪。
 */
internal fun decodeFlat(text: String, allowed: Set<String>): Map<String, DomainJson.Value> {
    val m = DomainJson.decodeObject(text)
    for (k in m.keys) {
        if (k !in allowed) throw IllegalArgumentException("未知字段: $k")
    }
    return m
}
