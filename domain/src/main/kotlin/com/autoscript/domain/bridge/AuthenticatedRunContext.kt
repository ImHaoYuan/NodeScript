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

    /**
     * 本次执行的项目号（§9.6 × §9.2）：**由认证点从 lease 装填**（`RunIdentityRegistry.authenticate`），
     * 与 [capabilityMask] 同一条「不可自报」纪律 —— 它**不是** wire 字段，脚本改不了。
     *
     * 为什么需要一个字段而不是让调用方从 payload 取：录屏产物的落点是
     * `ScriptPaths.recordingsDir(filesDir, projectId)`（§9.2 录屏腿），而 payload 里的
     * `projectId` 是**脚本可影响的字段** —— 拿它拼路径等于让脚本决定往哪个项目目录写文件
     * （写进别人的项目、或配合 `..` 越界）。落点必须由宿主一侧的事实决定，这个字段就是那份事实。
     *
     * **缺省空串 = 不知道**：调用方（录屏 handler）据此如实 `ERR_PERMISSION_DENIED`，
     * **绝不套一个默认项目名** —— 那会把产物静默写进某个项目（"写对了没人知道，写错了也
     * 没人知道"）。所以空串的失败方向是**拒**，与 [resources] 缺省空注册表同一条思路。
     *
     * 为什么是可变字段而不是构造参数：构造点（[RunIdentityRegistry.authenticate]）已经有
     * 项目号（lease 上就有），但把它做成必填参数会让 57 处测试/JVM 直调构造点全部改签名，
     * 而那些调用点**不开录屏会话**、也确实没有项目号可言。默认空串让它们照旧编译，
     * 代价只是"它们开不了录屏"—— 那正是诚实的结果。
     */
    @Volatile
    var projectId: String = ""

    init {
        require(engineRunId > 0) { "engineRunId 必须 > 0（0 仅供宿主直写日志）" }
        require(connectionId > 0) { "connectionId 必须 > 0" }
    }

    companion object Key : CoroutineContext.Key<AuthenticatedRunContext>
}
