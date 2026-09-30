package com.autoscript.platform.capabilities

import com.autoscript.domain.bridge.BridgeRequest

/**
 * 测试请求工厂（审查步骤 4：handler 直接吃 `:domain` 的 [BridgeRequest]，自定义 Request 形状退役）。
 * 参数面与旧 `XxxNamespaceHandler.Request(id, method, payload[, ttlMillis])` 逐位对齐 ——
 * 存量调用点只改名不改参。
 */
internal fun a11yReq(id: Long, method: String, payload: String?, ttlMillis: Long = 5_000) =
    BridgeRequest(id, "a11y", method, payload, ttlMillis)

internal fun screenReq(id: Long, method: String, payload: String?, ttlMillis: Long = 5_000) =
    BridgeRequest(id, "screen", method, payload, ttlMillis)
