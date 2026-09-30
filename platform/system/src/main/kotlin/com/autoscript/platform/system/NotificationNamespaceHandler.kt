package com.autoscript.platform.system
import com.autoscript.domain.bridge.generated.WireMethods

import com.autoscript.domain.bridge.decodeObject
import com.autoscript.domain.bridge.optStr
import com.autoscript.domain.bridge.RpcNamespaceHandler
import com.autoscript.domain.bridge.BridgeResponse
import com.autoscript.domain.bridge.BridgeRequest
import com.autoscript.domain.json.DomainJson
import com.autoscript.domain.core.AutojsException
import com.autoscript.domain.core.ErrorCode

/**
 * `notification` 命名空间的桥处理器（§12.2；JS 对偶 `bridge/js/src/notification.ts`）。
 *
 * 与 [DatastoreNamespaceHandler] / [ZipNamespaceHandler] / [SettingsNamespaceHandler]
 * 同属系统面：**独立注入缝**（`AppShell.assemble` 的 `notificationHandler`），不入
 * `SystemHandlers` 束 —— 那五个共担 OVERLAY/ROOT/ADB_INPUT 门禁，通知的门禁是
 * `POST_NOTIFICATIONS`，判据在 SPI 自己身上（§12.2 接线表）。单独成文件同前三者：
 * 混进 `SystemNamespaces.kt` 会搅浑「接就五个一起接」的束语义。
 *
 * **三方法**：`canPost`/`post`/`cancel`（照抄 [NotificationPoster]，不提供别名）。
 *
 * **参数口径归本层、门禁归 SPI**（§12.2 两层分工）：id 是否为 Int 范围内的整数、
 * `text` 是否非空白、`title` 是否为串 —— 都在这里折 `ERR_INVALID_PARAM`，**一次投递
 * 都不发**；「通知能不能发」（`POST_NOTIFICATIONS`/应用通知开关）不在此判 —— 那是
 * SPI 的 `canPost` 门，handler 再问一遍就是两处判据，必然漂移（同 settings 的
 * `WRITE_SETTINGS` 纪律）。
 *
 * SPI 抛的 `AutojsException` 原码透传（`ERR_PERMISSION_DENIED` 不折叠成
 * `ERR_INVALID_PARAM` —— 授权问题要能被调用方识别并引导）。
 * 未知方法 → `ERR_NOT_IMPLEMENTED`（`notify`/`show` 这类没约定过的别名不猜）。
 */
class NotificationNamespaceHandler(
    private val poster: NotificationPoster,
) : RpcNamespaceHandler() {

    /** 申报方法表（wire-schema 对账挂点）：单源指向生成物 [WireMethods.BY_NS]，不手抄。 */
    override fun methods(): Set<String> = WireMethods.BY_NS.getValue("notification")

    override suspend fun dispatch(request: BridgeRequest): BridgeResponse = when (request.method) {
        "canPost" -> ok(request, poster.canPost().toString())
        "post" -> post(request)
        "cancel" -> cancel(request)
        else -> err(request, ErrorCode.ERR_NOT_IMPLEMENTED,
            "未知 notification 方法: ${request.method}",
        )
    }

    private fun post(request: BridgeRequest): BridgeResponse {
        val fields = request.decodeObject()
        val spec = run {
            NotificationSpec(
                id = idOf(fields),
                text = textOf(fields),
                title = request.optStr(fields, "title"),
            )
        }
        return run {
            poster.post(spec)
            ok(request, "true")
        }
    }

    private fun cancel(request: BridgeRequest): BridgeResponse {
        val fields = request.decodeObject()
        val id = idOf(fields)
        return run {
            poster.cancel(id)
            // 无回执（见 :domain NotificationPoster KDoc）—— 回裸 true 表示"这次调用发出去了"，
            // 不表示"系统真撤到了"；JS 侧也据此回 void，不编一个撤销成功的布尔。
            ok(request, "true")
        }
    }

    /** 取 `id` 字段：必须是 Int 范围内的整数（缺/非数/小数/越界抛 IAE → 折叠）。 */
    private fun idOf(fields: Map<String, DomainJson.Value>): Int {
        val n = fields["id"] as? DomainJson.Value.N
            ?: throw IllegalArgumentException("缺数字字段 id")
        val raw = n.raw.toLongOrNull()
            ?: throw IllegalArgumentException("id 必须是整数: ${n.raw}")
        if (raw < Int.MIN_VALUE || raw > Int.MAX_VALUE) {
            throw IllegalArgumentException("id 超出 Int 范围: ${n.raw}")
        }
        return raw.toInt()
    }

    /** 取 `text` 字段：必须是非空白字符串（空串/纯空白是参数错，不是空通知）。 */
    private fun textOf(fields: Map<String, DomainJson.Value>): String {
        val text = fields["text"] as? DomainJson.Value.S
            ?: throw IllegalArgumentException("缺字符串字段 text")
        if (text.v.isBlank()) throw IllegalArgumentException("notification text 不得为空白")
        return text.v
    }
}
