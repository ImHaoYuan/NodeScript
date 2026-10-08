package com.autoscript.domain.bridge

import com.autoscript.domain.engine.EngineId
import kotlin.coroutines.AbstractCoroutineContextElement
import kotlin.coroutines.CoroutineContext

/**
 * 宿主认证的执行归属（§7.5）：只能由接入端或可信本地调用方建立，不是 wire 字段。
 * handler 全局共享，归属随协程而非 handler 字段传递；连接号由宿主发放，不采用客户端 id。
 *
 * [resources] 是**这条连接的资源收口口**（§9.2 会话资源）：谁在这条连接上开了进程级
 * 资源（投屏会话、将来的录屏/独占设备句柄），就在开的时候往这里登记一份"怎么还"。
 * 连接 abort / 正常结束 / 宿主 `close()` 时由 `NewlineFrameServer` 统一撤销 ——
 * 于是"脚本崩了但投屏还挂着"这条漏在结构上不成立，而不是靠每个调用方记得收。
 *
 * **为什么是可变字段而不是构造参数**：它由**接入端**（`NewlineFrameServer` 的连接对象）
 * 在认证成功后装填，而构造点（`RunIdentityRegistry.authenticate`）不该认识桥的资源形状。
 * 默认给一个空注册表，宿主直调/JVM 测试照旧三参数构造，不需要为"没有桥连接"编一个。
 */
class AuthenticatedRunContext(
    val engineId: EngineId,
    val engineRunId: Long,
    val connectionId: Long,
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
