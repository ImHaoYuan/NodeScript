package com.autoscript.platform.system.device

import com.autoscript.domain.bridge.RpcNamespaceHandler
import com.autoscript.domain.bridge.BridgeResponse
import com.autoscript.domain.bridge.BridgeRequest
import com.autoscript.domain.bridge.generated.WireMethods
import com.autoscript.domain.core.ErrorCode
import com.autoscript.domain.json.DomainJson

/**
 * `device` 命名空间的桥处理器（`model`/`sdkInt`，P0 最小集 §12.3）
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

// ── device（§9.6）───────────────────────────────────────────────────

class DeviceNamespaceHandler(private val info: DeviceInfoProvider) : RpcNamespaceHandler() {

    /** 申报方法表（wire-schema 对账挂点）：单源指向生成物 [WireMethods.BY_NS]，不手抄。 */
    override fun methods(): Set<String> = WireMethods.BY_NS.getValue("device")

    override suspend fun dispatch(request: BridgeRequest): BridgeResponse = when (request.method) {
        "model" -> {
            val p = info.profile()   // 构造期已校验（空型号/SDK<1 即拒）
            ok(request, DomainJson.encode(p.model))
        }
        "sdkInt" -> ok(request, DomainJson.encode(info.profile().sdkInt.toLong()))
        else -> err(request, ErrorCode.ERR_NOT_IMPLEMENTED, "未知 device 方法: ${request.method}")
    }
}
