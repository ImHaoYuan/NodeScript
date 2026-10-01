package com.autoscript.platform.system.floatingWindow

import com.autoscript.domain.bridge.RpcNamespaceHandler
import com.autoscript.domain.bridge.BridgeResponse
import com.autoscript.domain.bridge.BridgeRequest
import com.autoscript.domain.bridge.decodeObject
import com.autoscript.domain.bridge.optLong
import com.autoscript.domain.bridge.optStr
import com.autoscript.domain.bridge.requiredRef
import com.autoscript.domain.bridge.generated.WireMethods
import com.autoscript.domain.core.ErrorCode
import com.autoscript.domain.json.DomainJson

/**
 * `floatingWindow` 命名空间的桥处理器（`create`/`close`，§9.4）
 *
 * **来历**：原 `SystemNamespaces.kt` 的「四内」之一 —— 2026-10-01 D3「子包按命名空间
 * 对齐」把四个内联 handler 拆到各自命名空间的子包（与 capabilities 的
 * `a11y/A11yNamespaceHandler` 同形：handler 与它服务的 SPI/实现同包），
 * `SystemNamespaces.kt` 只留十一工厂束。语义逐字未改。
 *
 * **能力门禁不在这里**（§9.5）：门禁归 `:app-service:permission-center` 的
 * `PermissionFacade`（本模块 archUnit 黑名单含 `com.autoscript.appservice..`），
 * 装配层先判后取；本类只负责「能力已保证之后」的参数校验与分类错误。
 */

// ── floatingWindow（§9.4）───────────────────────────────────────────

class FloatingWindowNamespaceHandler(
    private val host: FloatingWindowHost,
) : RpcNamespaceHandler() {

    /** 申报方法表（wire-schema 对账挂点）：单源指向生成物 [WireMethods.BY_NS]，不手抄。 */
    override fun methods(): Set<String> = WireMethods.BY_NS.getValue("floatingWindow")

    override suspend fun dispatch(request: BridgeRequest): BridgeResponse {
        return when (request.method) {
            "create" -> {
                val fields = request.decodeObject()
                val spec = run {
                    FloatingWindowSpec(
                        title = request.optStr(fields, "title"),
                        width = request.optLong(fields, "width")?.toInt(),
                        height = request.optLong(fields, "height")?.toInt(),
                    )
                }
                val ref = host.create(spec)
                ok(request, DomainJson.encode(mapOf("refId" to ref.refId, "generation" to ref.generation)),
                )
            }
            "close" -> {
                val fields = request.decodeObject()
                val ref = request.requiredRef(fields)
                run {
                    host.close(ref)
                }
                ok(request, "true")
            }
            else -> err(request, ErrorCode.ERR_NOT_IMPLEMENTED,
                "未知 floatingWindow 方法: ${request.method}",
            )
        }
    }
}
