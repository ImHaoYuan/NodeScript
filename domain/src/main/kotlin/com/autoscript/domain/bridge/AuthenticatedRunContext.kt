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
 *
 * [resources] 是**这条连接的资源收口口**（§9.2 会话资源）：谁在这条连接上开了进程级
 * 资源（投屏会话、将来的录屏/独占设备句柄），就在开的时候往这里登记一份"怎么还"。
 * 连接 abort / 正常结束 / 宿主 `close()` 时由 `NewlineFrameServer` 统一撤销 ——
 * 于是"脚本崩了但投屏还挂着"这条漏在结构上不成立，而不是靠每个调用方记得收。
 *
 * **为什么 [resources] 是可变字段而不是构造参数**：它由**接入端**（`NewlineFrameServer`
 * 的连接对象）在认证成功后装填，而构造点（`RunIdentityRegistry.authenticate`）不该认识
 * 桥的资源形状。默认给一个空注册表，宿主直调/JVM 测试照旧四参数构造，不需要为
 * "没有桥连接"编一个。**与 [capabilityMask] 的分工**：掩码是"这次执行被授权用哪些桥面"
 * （不可自报、构造期定死），资源口是"这条连接欠了谁什么"（接入端装填、连接终结时清账）。
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

    /**
     * 本连接的资源收口口（接入端装填；未装填 = 一个没人登记的实例，登记即无人撤销 ——
     * 所以**只有接入端建的上下文才该开进程级资源**）。
     */
    @Volatile
    var resources: ConnectionResourceRegistry = ConnectionResourceRegistry()

    init {
        require(engineRunId > 0) { "engineRunId 必须 > 0（0 仅供宿主直写日志）" }
        require(connectionId > 0) { "connectionId 必须 > 0" }
    }

    companion object Key : CoroutineContext.Key<AuthenticatedRunContext>
}
