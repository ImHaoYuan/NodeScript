package com.autoscript.platform.system

import com.autoscript.domain.bridge.decodeObject
import com.autoscript.domain.bridge.requiredStr
import com.autoscript.domain.bridge.RpcNamespaceHandler
import com.autoscript.domain.bridge.BridgeResponse
import com.autoscript.domain.bridge.BridgeRequest
import com.autoscript.domain.json.DomainJson
import com.autoscript.domain.core.AutojsException
import com.autoscript.domain.core.ErrorCode
import com.autoscript.domain.storage.SystemSettings

/**
 * `settings` 命名空间的桥处理器（§9.6；JS 对偶 `bridge/js/src/settings.ts`）。
 *
 * 与 [DatastoreNamespaceHandler] / [ZipNamespaceHandler] 同属 §9.6 存储面：**独立注入缝**
 * （`AppShell.assemble` 的 `settingsHandler`），不入 `SystemHandlers` 束 —— 五个共担门禁的
 * 命名空间是「接就五个一起接」的一束，存储面不与它们同组（§12.2 接线表）。单独成文件的
 * 理由同 datastore：混进 `SystemNamespaces.kt` 会搅浑那束语义。
 *
 * **方法表照抄 SPI 的两型**：`canWrite`/`getString`/`getInt`/`putString`/`putInt`。
 * 不提供猜型的 `get`/`put` —— `Settings.System` 的字符串与整型是两套系统 API
 * （`putString` 存原文、`putInt` 存数字），按 `typeof value` 推断等于在桥面发明一条
 * SPI 没有的策略（`"128"` 该存成串还是数？），调用方显式选。未约定过的别名如实
 * `ERR_NOT_IMPLEMENTED`，与 zip 的 `unzip` 同一条「不猜」纪律。
 *
 * **读侧缺失回裸 JSON `null`**（不是 datastore 那种 `{found,value}` 信封）：本契约的值面
 * 只有 `String?`/`Int?`，JSON 的 `null` 与 `"…"`/`123` 天然不撞；需要信封的是 datastore ——
 * 它的值面含显式 JSON `null`，存的 null 与键缺失在裸 JSON 里分不开。JS 侧
 * `getString`/`getInt` 因此回 `null` 而非 `undefined`：缺键是常态答案，不是「查不到」。
 * 空串/0 同样是真值（`""` 是合法设置值、0 是合法亮度），一律不拿来冒充缺失。
 *
 * **写前不预检 `canWrite`**：未授 `WRITE_SETTINGS` 抛 `ERR_PERMISSION_DENIED` 的判据
 * 唯一出处是 [SystemSettings] 实现（`:platform:system` 的 `AndroidSystemSettings`）——
 * handler 再判一遍就是两处判据，必然漂移。这里只做参数口径 + `AutojsException` 原码透传。
 *
 * 参数口径：缺 payload/缺 key/空白 key/`putString` 的 value 非字符串/`putInt` 的 value
 * 非整型或超 Int 范围 → `ERR_INVALID_PARAM`，且**不碰 SPI**（垃圾进不了 ContentResolver）。
 */
class SettingsNamespaceHandler(
    private val settings: SystemSettings,
) : RpcNamespaceHandler() {
    override suspend fun dispatch(request: BridgeRequest): BridgeResponse = when (request.method) {
        "canWrite" -> canWrite(request)
        "getString" -> getString(request)
        "getInt" -> getInt(request)
        "putString" -> putString(request)
        "putInt" -> putInt(request)
        else -> err(request, ErrorCode.ERR_NOT_IMPLEMENTED,
            "未知 settings 方法: ${request.method}",
        )
    }

    /** 授权探针：读设置不需要授权，写前的诚实提问（JS 可先问再写）。 */
    private fun canWrite(request: BridgeRequest): BridgeResponse =
        ok(request, settings.canWrite().toString())

    private fun getString(request: BridgeRequest): BridgeResponse {
        val key = readKey(request)
        // 缺失 = 裸 null（见类 KDoc：值面无显式 null，不需要信封）
        return ok(request, DomainJson.encode(settings.getString(key)))
    }

    private fun getInt(request: BridgeRequest): BridgeResponse {
        val key = readKey(request)
        return ok(request, DomainJson.encode(settings.getInt(key)))
    }

    private fun putString(request: BridgeRequest): BridgeResponse {
        val fields = request.decodeObject()
        val key = keyOf(request, fields)
        // 空串是合法设置值 —— 缺参/非串才是参数错，不拿 isBlank 卡 value。
        val value = fields["value"] as? DomainJson.Value.S
            ?: return err(request, ErrorCode.ERR_INVALID_PARAM,
                "putString 缺 value 字段或 value 不是字符串",
            )
        return run {
            settings.putString(key, value.v)
            ok(request, "true")
        }
    }

    private fun putInt(request: BridgeRequest): BridgeResponse {
        val fields = request.decodeObject()
        val key = keyOf(request, fields)
        val n = fields["value"] as? DomainJson.Value.N
            ?: return err(request, ErrorCode.ERR_INVALID_PARAM,
                "putInt 缺 value 字段或 value 不是数字",
            )
        val raw = n.raw.toLongOrNull()
            ?: return err(request, ErrorCode.ERR_INVALID_PARAM,
                "putInt value 必须是整数: ${n.raw}",
            )
        if (raw < Int.MIN_VALUE || raw > Int.MAX_VALUE) {
            return err(request, ErrorCode.ERR_INVALID_PARAM,
                "putInt value 超出 Int 范围: ${n.raw}",
            )
        }
        return run {
            settings.putInt(key, raw.toInt())
            ok(request, "true")
        }
    }

    /** 解 payload 取 key；缺 payload/缺 key/空白 key 抛 [IllegalArgumentException]（调用方折叠）。 */
    private fun readKey(request: BridgeRequest): String = keyOf(request, request.decodeObject())

    /** 取 `key` 字段并拒空白；缺/非串/空白抛 [IllegalArgumentException]。 */
    private fun keyOf(
        request: BridgeRequest,
        fields: Map<String, DomainJson.Value>,
    ): String {
        val key = request.requiredStr(fields, "key")
        if (key.isBlank()) throw IllegalArgumentException("settings key 不得为空白")
        return key
    }
}
