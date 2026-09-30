package com.autoscript.platform.system
import com.autoscript.domain.bridge.generated.WireMethods

import com.autoscript.domain.bridge.decodeObject
import com.autoscript.domain.bridge.requiredStr
import com.autoscript.domain.bridge.RpcNamespaceHandler
import com.autoscript.domain.bridge.BridgeResponse
import com.autoscript.domain.bridge.BridgeRequest
import com.autoscript.domain.json.DomainJson
import com.autoscript.domain.core.AutojsException
import com.autoscript.domain.core.ErrorCode
import com.autoscript.domain.storage.DataStore
import com.autoscript.domain.storage.StoredEntry

/**
 * `datastore` 命名空间的桥处理器（§9.6；JS 对偶 `bridge/js/src/datastore.ts`）。
 *
 * 为什么单独成文件而不住 `SystemNamespaces.kt`：那五个命名空间共享门禁组、
 * 是一个装配束（`AppShell.SystemHandlers`，「接就五个一起接」）；存储面与它们
 * 无关（应用私有 KV 无需授权），§12.2 表也把它列在五个之外 —— 同文件会把
 * 束语义搅浑。装配上它是**独立缝**（`AppShell.assemble` 的 `datastoreHandler`）。
 *
 * **值面只走 JSON**：`put` 收任意 JSON 值（宿主侧转文本存 [StoredEntry.Json]，
 * 不解释业务结构）；`get` 回 `{found,value}` 信封 —— 键缺失 `{"found":false}`
 * （JS facade 折叠成 `undefined`）、存的 JSON null `{"found":true,"value":null}`
 * （回 `null`），两者**不折叠**（[StoredEntry] 契约同款区分，JS 侧 `undefined`
 * ≠ `null` 的语义就靠这个信封活着）。
 *
 * **字节值不过桥**：存储里若躺着 [StoredEntry.Bytes]（只有 Kotlin 直写才会），
 * `get` 如实 `ERR_NOT_IMPLEMENTED` —— §7.4 二进制 side-channel 未接，
 * 不拿 base64 假装通用面。
 *
 * **事务不进桥面（P0）**：跨桥事务要 begin/commit/abort 句柄 + TTL 管理；
 * [DataStore.transaction] 先留给 Kotlin 调用方。facade 未提供该方法 ——
 * JS 侧直接没有这个函数（诚实缺位，不发一条注定 NOT_IMPLEMENTED 的请求）。
 */
class DatastoreNamespaceHandler(
    private val store: DataStore,
) : RpcNamespaceHandler() {

    /** 申报方法表（wire-schema 对账挂点）：单源指向生成物 [WireMethods.BY_NS]，不手抄。 */
    override fun methods(): Set<String> = WireMethods.BY_NS.getValue("datastore")

    override suspend fun dispatch(request: BridgeRequest): BridgeResponse = when (request.method) {
        "get" -> get(request)
        "put" -> put(request)
        "remove" -> remove(request)
        "contains" -> contains(request)
        "keys" -> keys(request)
        "clear" -> clear(request)
        else -> err(request, ErrorCode.ERR_NOT_IMPLEMENTED,
            "未知 datastore 方法: ${request.method}",
        )
    }

    private suspend fun get(request: BridgeRequest): BridgeResponse {
        val key = readKey(request)
        val entry = store.get(key) ?: return ok(request, NOT_FOUND)
        return when (entry) {
            is StoredEntry.Json ->
                ok(request, """{"found":true,"value":${entry.text}}""")
            is StoredEntry.Bytes -> err(request, ErrorCode.ERR_NOT_IMPLEMENTED,
                "字节值不过 JSON 桥（§7.4 side-channel 未接）",
            )
        }
    }

    private suspend fun put(request: BridgeRequest): BridgeResponse {
        val fields = request.decodeObject()
        val key = request.requiredStr(fields, "key").also(::requireNonBlank)
        // 显式 JSON null 是合法值 —— 用「键在不在」而不是「值是不是 Null」区分缺参。
        if (!fields.containsKey("value")) {
            return err(request, ErrorCode.ERR_INVALID_PARAM,
                "put 缺 value 字段（写 JSON null 请传 value:null，不是省略）",
            )
        }
        val text = DomainJson.encodeParsed(fields.getValue("value"))
        return run {
            store.put(key, StoredEntry.Json(text))
            ok(request, "true")
        }
    }

    private suspend fun remove(request: BridgeRequest): BridgeResponse {
        val key = readKey(request)
        val removed = store.remove(key)
        return ok(request, (removed != null).toString())
    }

    private suspend fun contains(request: BridgeRequest): BridgeResponse {
        val key = readKey(request)
        val present = store.contains(key)
        return ok(request, present.toString())
    }

    private suspend fun keys(request: BridgeRequest): BridgeResponse = ok(request, DomainJson.encode(store.keys()))

    private suspend fun clear(request: BridgeRequest): BridgeResponse = run {
        store.clear()
        ok(request, "true")
    }

    /** 解 payload 取非空白 key；参数非法抛 [IllegalArgumentException]（调用方折叠 ERR_INVALID_PARAM）。 */
    private fun readKey(request: BridgeRequest): String {
        val fields = request.decodeObject()
        val key = request.requiredStr(fields, "key")
        requireNonBlank(key)
        return key
    }

    private fun requireNonBlank(key: String) {
        if (key.isBlank()) throw IllegalArgumentException("key 不得为空白")
    }

    companion object {
        /** 键缺失的信封（与 `{"found":true,...}` 相对；JS facade 折成 undefined）。 */
        const val NOT_FOUND = """{"found":false}"""
    }
}
