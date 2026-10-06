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
 * 沿革与「门禁不在这里」的边界见 `SystemNamespaces` 的类注释（同一条纪律，此处不重复）。
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
