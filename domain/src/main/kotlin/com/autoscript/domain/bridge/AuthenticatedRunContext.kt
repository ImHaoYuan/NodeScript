package com.autoscript.domain.bridge

import com.autoscript.domain.engine.EngineId
import kotlin.coroutines.AbstractCoroutineContextElement
import kotlin.coroutines.CoroutineContext

/**
 * 宿主认证的执行归属（§7.5）：只能由接入端或可信本地调用方建立，不是 wire 字段。
 * handler 全局共享，归属随协程而非 handler 字段传递；连接号由宿主发放，不采用客户端 id。
 */
class AuthenticatedRunContext(
    val engineId: EngineId,
    val engineRunId: Long,
    val connectionId: Long,
) : AbstractCoroutineContextElement(Key) {
    init {
        require(engineRunId > 0) { "engineRunId 必须 > 0（0 仅供宿主直写日志）" }
        require(connectionId > 0) { "connectionId 必须 > 0" }
    }

    companion object Key : CoroutineContext.Key<AuthenticatedRunContext>
}
