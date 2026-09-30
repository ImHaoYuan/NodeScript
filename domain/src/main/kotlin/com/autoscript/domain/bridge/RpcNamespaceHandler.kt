package com.autoscript.domain.bridge

import com.autoscript.domain.core.AutojsException
import com.autoscript.domain.core.ErrorCode

/**
 * RPC 处理器基类（审查步骤 4）：**错误映射收口在这里**，子类只写业务分发。
 *
 * 折叠规则与全仓 60 处手写 catch 逐字同语义，只收两类：
 * - [AutojsException] → `ERR`（SPI 原码透传，`e.error.code`）；
 * - [IllegalArgumentException] → `ERR_INVALID_PARAM`（解码/参数校验的统一出口，
 *   `Decode.kt` 的 helpers 全抛它）。
 *
 * **未知异常不接**：照旧穿出 = bug 显形（测试红/进程炸），不伪造参数错掩盖。
 * 未知方法不在这里兜：`dispatch` 的 `else` 分支按各自命名空间回
 * `ERR_NOT_IMPLEMENTED`（消息带方法名，§7.5 不猜别名）。
 *
 * [methods] 是步骤 7（schema 单源生成 Kotlin 方法表）的挂点，先留位。
 */
abstract class RpcNamespaceHandler : NamespaceHandler {

    final override suspend fun handle(request: BridgeRequest): BridgeResponse = try {
        dispatch(request)
    } catch (e: AutojsException) {
        BridgeResponse.Err(request.id, e.error.code, e.message)
    } catch (e: IllegalArgumentException) {
        BridgeResponse.Err(request.id, ErrorCode.ERR_INVALID_PARAM.code, e.message)
    }

    /** 业务分发：`when (request.method)` + 业务 + 参数校验；错误处理不再出现在本函数。 */
    protected abstract suspend fun dispatch(request: BridgeRequest): BridgeResponse

    /** 本命名空间申报的方法表（schema 对账挂点；缺省空 = 未申报，对账层按未接入处理）。 */
    open fun methods(): Set<String> = emptySet()

    protected fun ok(request: BridgeRequest, payload: String?): BridgeResponse =
        BridgeResponse.Ok(request.id, payload)

    protected fun err(request: BridgeRequest, code: ErrorCode, detail: String?): BridgeResponse =
        BridgeResponse.Err(request.id, code.code, detail)

    protected fun notImplemented(request: BridgeRequest, namespace: String): BridgeResponse =
        err(request, ErrorCode.ERR_NOT_IMPLEMENTED, "未知 $namespace 方法: ${request.method}")
}
