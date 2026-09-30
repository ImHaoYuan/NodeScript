package com.autoscript.platform.capabilities.dialogs

import com.autoscript.domain.bridge.BridgeRequest
import com.autoscript.domain.bridge.BridgeResponse
import com.autoscript.domain.bridge.RpcNamespaceHandler
import com.autoscript.domain.bridge.decodeObject
import com.autoscript.domain.bridge.enumOrNull
import com.autoscript.domain.bridge.optStr
import com.autoscript.domain.bridge.requiredStr
import com.autoscript.domain.bridge.requiredStrList
import com.autoscript.domain.core.ErrorCode
import com.autoscript.domain.json.DomainJson
import com.autoscript.domain.system.DialogChooseRequest
import com.autoscript.domain.system.DialogHost
import com.autoscript.domain.system.DialogMode
import com.autoscript.domain.system.DialogPromptRequest

/**
 * `dialogs` 命名空间桥处理器（docs §9.4 / §12.2；JS 对偶 `extras.ts`）。
 *
 * 归属（审查步骤 6）：住 `:platform:capabilities` 的 dialogs 面 —— 语义层与
 * [com.autoscript.platform.capabilities.AndroidDialogHost]（BAL 安全路径编排）同模块；
 * 自 SystemNamespaces.kt 拆出（原与 shell/device/app/floatingWindow 同文件，那四件
 * 2026-09-30 已随实现迁去 `:platform:system`）。
 *
 * 能力门禁不在这里：`SYSTEM_ALERT_WINDOW` 判定在 `AndroidDialogHost`/装配层，
 * handler 只做参数校验与分类错误。
 */
// ── dialogs（§9.4）──────────────────────────────────────────────────

class DialogsNamespaceHandler(private val host: DialogHost) : RpcNamespaceHandler() {
    override suspend fun dispatch(request: BridgeRequest): BridgeResponse = when (request.method) {
        "prompt" -> {
            val fields = request.decodeObject()
            val req = run {
                DialogPromptRequest(
                    title = request.requiredStr(fields, "title"),
                    placeholder = request.optStr(fields, "placeholder"),
                    mode = request.enumOrNull(fields, "mode", DialogMode.AUTO) { DialogMode.valueOf(it.uppercase()) },
                )
            }
            val out = host.prompt(req)
            ok(request, DomainJson.encode(mapOf("value" to out.value, "confirmed" to out.confirmed)),
            )
        }
        "choose" -> {
            val fields = request.decodeObject()
            val req = run {
                DialogChooseRequest(
                    title = request.requiredStr(fields, "title"),
                    options = request.requiredStrList(fields, "options"),
                    mode = request.enumOrNull(fields, "mode", DialogMode.AUTO) { DialogMode.valueOf(it.uppercase()) },
                )
            }
            val choice = host.choose(req)
            // 下标直出（JS facade `?? -1`）；取消即 -1，不套 null
            ok(request, DomainJson.encode(choice.index.toLong()))
        }
        else -> err(request, ErrorCode.ERR_NOT_IMPLEMENTED, "未知 dialogs 方法: ${request.method}")
    }
}
