package com.autoscript.platform.system.app

import com.autoscript.domain.bridge.RpcNamespaceHandler
import com.autoscript.domain.bridge.BridgeResponse
import com.autoscript.domain.bridge.BridgeRequest
import com.autoscript.domain.bridge.decodeObject
import com.autoscript.domain.bridge.requiredStr
import com.autoscript.domain.bridge.generated.WireMethods
import com.autoscript.domain.core.ErrorCode
import com.autoscript.domain.json.DomainJson

/**
 * `app` 命名空间的桥处理器（`launch`/`currentPackage`）
 *
 * 沿革与「门禁不在这里」的边界见 `SystemNamespaces` 的类注释（同一条纪律，此处不重复）。
 */

// ── app（§9.3/§12.2）────────────────────────────────────────────────

class AppNamespaceHandler(private val launcher: AppLauncher) : RpcNamespaceHandler() {

    /** 申报方法表（wire-schema 对账挂点）：单源指向生成物 [WireMethods.BY_NS]，不手抄。 */
    override fun methods(): Set<String> = WireMethods.BY_NS.getValue("app")

    override suspend fun dispatch(request: BridgeRequest): BridgeResponse = when (request.method) {
        "launch" -> {
            val fields = request.decodeObject()
            val pkg = request.requiredStr(fields, "packageName")
            // 起不来回 false（JS facade `=== true` 判成败），不抛错
            ok(request, DomainJson.encode(launcher.launch(pkg)))
        }
        "currentPackage" -> {
            val pkg = launcher.currentPackage()
            ok(request, DomainJson.encode(pkg))
        }
        else -> err(request, ErrorCode.ERR_NOT_IMPLEMENTED, "未知 app 方法: ${request.method}")
    }
}
