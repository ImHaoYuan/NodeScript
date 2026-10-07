package com.autoscript.domain.bridge

import com.autoscript.domain.engine.EngineId
import com.autoscript.domain.permission.CapabilityMask
import kotlin.coroutines.AbstractCoroutineContextElement
import kotlin.coroutines.CoroutineContext

/**
 * 宿主认证的执行归属（§7.5）：只能由接入端或可信本地调用方建立，不是 wire 字段。
 * handler 全局共享，归属随协程而非 handler 字段传递；连接号由宿主发放，不采用客户端 id。
 *
 * **[capabilityMask] 是这次执行的能力票（A5，§11）**：由宿主在 spawn 前定死
 * （`RunIdentityIssuer.issue` 时经来源策略算出，随 lease 带到连接），运行期不可变，
 * 也不从 payload/side 解码 —— 与身份三字段同一条「不可自报」纪律。
 * 桥路由按它做 deny-by-default 过滤（`BridgeCapabilityCatalog`）。
 *
 * 掩码**只约束桥面命名空间**：不限制 Node 内建 `fs`/`http`，也不是沙箱
 * （§11.3 第 1 条：同 UID 同权这一事实不变）。
 */
class AuthenticatedRunContext(
    val engineId: EngineId,
    val engineRunId: Long,
    val connectionId: Long,
    /**
     * 本次执行的桥面能力掩码。**刻意不给缺省值**：缺省会让「忘了接授权」编译通过并
     * 静默退化成某一档（宽 = 白名单失效，窄 = 功能全灭），两种都难查。
     * 生产链上它只能来自 `RunIdentityIssuer.issue`（来源策略算出，随 lease 到连接）。
     */
    val capabilityMask: CapabilityMask,
) : AbstractCoroutineContextElement(Key) {
    init {
        require(engineRunId > 0) { "engineRunId 必须 > 0（0 仅供宿主直写日志）" }
        require(connectionId > 0) { "connectionId 必须 > 0" }
    }

    companion object Key : CoroutineContext.Key<AuthenticatedRunContext>
}
