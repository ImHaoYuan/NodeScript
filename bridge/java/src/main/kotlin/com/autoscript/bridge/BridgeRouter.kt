package com.autoscript.bridge

import com.autoscript.domain.bridge.AuthenticatedRunContext
import com.autoscript.domain.bridge.BridgeRequest
import com.autoscript.domain.bridge.BridgeResponse
import com.autoscript.domain.bridge.NamespaceHandler
import com.autoscript.domain.core.Clock
import com.autoscript.domain.core.ErrorCode
import com.autoscript.domain.core.SystemClock
import kotlin.coroutines.cancellation.CancellationException
import kotlin.coroutines.coroutineContext
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeout
import java.util.concurrent.ConcurrentHashMap

/**
 * namespace → 请求处理器（实现方注册，如 a11y / images / npm）。
 *
 * 即 `:domain` 的 [com.autoscript.domain.bridge.NamespaceHandler]（§4.1/§6）：
 * 实现方（`:platform:capabilities` 的 a11y/screen handler）只允许依赖 `:domain`，
 * 因此挂载缝的类型必须住在 `:domain`，本名保留了「桥侧叫法」，两端是同一个函数类型。
 */
typealias RequestHandler = com.autoscript.domain.bridge.NamespaceHandler

/**
 * 桥路由器（docs §7.5）：
 * - 按 namespace 路由，走 TTL 注册表（去重 + 到期收割）；
 * - 同步 dispatch：handler 在 TTL 内返回即回，超时 → ERR_TIMEOUT；
 * - 后台扫描线程按 [SWEEP_INTERVAL_MILLIS] 收割过期请求。
 */
class BridgeRouter(
    private val registry: RequestRegistry,
    @Suppress("unused") private val clock: Clock = SystemClock,
    private val supervisor: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.Default),
) : AutoCloseable {

    private val handlers = ConcurrentHashMap<String, RequestHandler>()

    init {
        supervisor.launch {
            while (isActive) {
                delay(SWEEP_INTERVAL_MILLIS)
                registry.expireDue()
            }
        }
    }

    fun register(namespace: String, handler: RequestHandler): Boolean =
        handlers.putIfAbsent(namespace, handler) == null

    suspend fun dispatch(request: BridgeRequest): BridgeResponse = coroutineScope {
        val handler = handlers[request.namespace]
            ?: return@coroutineScope BridgeResponse.Err(request.id, ErrorCode.ERR_NOT_IMPLEMENTED.code, "未知 namespace: ${request.namespace}")
        // 0 仅为可信本地调用作用域；网络入口必须先认证，console/heartbeat 缺身份也会独立拒绝。
        val connectionId = coroutineContext[AuthenticatedRunContext]?.connectionId ?: 0L
        val result = CompletableDeferred<BridgeResponse>()
        val ticket = registry.register(connectionId, request) { result.complete(it) }
            ?: return@coroutineScope BridgeResponse.Err(
                request.id,
                if (registry.isClosed()) ErrorCode.ERR_ENGINE_STOPPED.code else ErrorCode.ERR_INVALID_PARAM.code,
                "桥已关闭或本连接 requestId 重复",
            )
        val ttl = request.ttlMillis.takeIf { it > 0 } ?: RequestRegistry.DEFAULT_TTL_MILLIS
        val work = launch(start = CoroutineStart.LAZY) {
            val response = try {
                withTimeout(ttl) { handler.handle(request) }
            } catch (e: TimeoutCancellationException) {
                BridgeResponse.Err(request.id, ErrorCode.ERR_TIMEOUT.code, "handler 处理超时 $ttl ms")
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                BridgeResponse.Err(request.id, ErrorCode.ERR_INVALID_PARAM.code, "handler 异常: ${e.message}")
            }
            registry.complete(ticket, response)
        }
        // TTL sweep/关闭与 handler 完成共用 ticket 的一次性结算；失败的一方不能回迟到成功。
        val completion = result.invokeOnCompletion { work.cancel() }
        work.invokeOnCompletion { cause ->
            if (cause is CancellationException && !result.isCompleted) result.cancel(cause)
        }
        try {
            work.start()
            result.await()
        } finally {
            registry.cancel(ticket)
            completion.dispose()
            work.cancel()
        }
    }

    fun closeConnection(connectionId: Long) { registry.finishConnection(connectionId) }

    override fun close() {
        registry.finishAll()
        supervisor.cancel()
    }

    companion object {
        const val SWEEP_INTERVAL_MILLIS: Long = 500
    }
}