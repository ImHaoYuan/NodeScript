package com.autoscript.bridge

import com.autoscript.domain.bridge.BridgeRequest
import com.autoscript.domain.bridge.BridgeResponse

/** 传输层契约（docs §7.5 transports）：控制面小对象无损编解码。 */
interface BridgeTransport {
    fun encodeRequest(request: BridgeRequest): ByteArray
    fun decodeRequest(bytes: ByteArray): BridgeRequest
    fun encodeResponse(response: BridgeResponse): ByteArray
    fun decodeResponse(bytes: ByteArray): BridgeResponse

    /**
     * 尽力取回包所需的 requestId —— [decodeRequest] 抛错后**回错误帧**用。
     *
     * 契约：**不抛异常**（尽力而为），取不到 id 就回 null（调用方只能丢弃）。
     * 缺省 null = 该传输没有「帧坏了但信封 id 还在」的概念（如纯二进制传输）。
     */
    fun probeRequestId(bytes: ByteArray): Long? = null
}