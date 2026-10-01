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
 * **来历**：原 `SystemNamespaces.kt` 的「四内」之一 —— 2026-10-01 D3「子包按命名空间
 * 对齐」把四个内联 handler 拆到各自命名空间的子包（与 capabilities 的
 * `a11y/A11yNamespaceHandler` 同形：handler 与它服务的 SPI/实现同包），
 * `SystemNamespaces.kt` 只留十一工厂束。语义逐字未改。
 *
 * **能力门禁不在这里**（§9.5）：门禁归 `:app-service:permission-center` 的
 * `PermissionFacade`（本模块 archUnit 黑名单含 `com.autoscript.appservice..`），
 * 装配层先判后取；本类只负责「能力已保证之后」的参数校验与分类错误。
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
