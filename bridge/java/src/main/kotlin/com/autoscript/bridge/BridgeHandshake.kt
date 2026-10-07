package com.autoscript.bridge

import com.autoscript.domain.core.ErrorCode
import com.autoscript.domain.json.DomainJson

/** 独立控制帧（§7.5）：不占业务请求号，codec 仍只用 DomainJson，不改方法表 schema。 */
object BridgeHandshake {
    const val VERSION = 1
    const val MAX_BYTES = 1024
    const val TIMEOUT_MILLIS = 10_000L
    private val tokenPattern = Regex("[0-9a-f]{64}")

    fun hello(token: String): ByteArray {
        require(tokenPattern.matches(token)) { "桥凭据格式无效" }
        return line(mapOf("t" to "hello", "v" to VERSION, "token" to token))
    }

    fun decodeHello(bytes: ByteArray): String {
        require(bytes.size <= MAX_BYTES) { "握手帧过大" }
        val m = decodeFlat(bytes.toString(Charsets.UTF_8), setOf("t", "v", "token"))
        require(m["t"] == DomainJson.Value.S("hello") && (m["v"] as? DomainJson.Value.N)?.raw == "1") { "握手协议不符" }
        val token = (m["token"] as? DomainJson.Value.S)?.v
        require(token != null && tokenPattern.matches(token)) { "桥凭据格式无效" }
        return token
    }

    // native main.cpp 精确读取这一行；跨端金样测试保护字段顺序/版本，不交 addon 的 TSF 消费。
    fun ack(): ByteArray = line(mapOf("t" to "helloAck", "v" to VERSION))
    fun error(code: ErrorCode): ByteArray = line(mapOf("t" to "helloErr", "v" to VERSION, "code" to code.code))
    private fun line(fields: Map<String, Any>): ByteArray = (DomainJson.encode(fields) + "\n").toByteArray(Charsets.UTF_8)
}
