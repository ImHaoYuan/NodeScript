package com.autoscript.platform.system

import com.autoscript.domain.bridge.decodeObject
import com.autoscript.domain.bridge.requiredStr
import com.autoscript.domain.bridge.RpcNamespaceHandler
import com.autoscript.domain.bridge.BridgeResponse
import com.autoscript.domain.bridge.BridgeRequest
import com.autoscript.domain.json.DomainJson
import com.autoscript.domain.core.AutojsException
import com.autoscript.domain.core.ErrorCode
import com.autoscript.domain.storage.ZipArchiver
import java.nio.file.Path

/**
 * `zip` 命名空间的桥处理器（§9.6；JS 对偶 `bridge/js/src/zip.ts`）。
 *
 * 与 [DatastoreNamespaceHandler] 同属存储面：**独立注入缝**（`AppShell.assemble`
 * 的 `zipHandler`），不入 `SystemHandlers` 束 —— 归档无需能力门禁，且与五个
 * 共担门禁的命名空间不是一组（§12.2 接线表）。单独成文件的理由同 datastore：
 * 混进 `SystemNamespaces.kt` 会搅浑「接就五个一起接」的束语义。
 *
 * 本层只做参数口径 + 错误透传，**归档语义（zip-slip 防线、原子落位）全在
 * [ZipArchiver] 实现里**（`:platform:system` 的 `JdkZipArchiver`）—— handler
 * 不碰归档字节，也就不可能绕开那条契约级安全底线。
 *
 * 错误口径：SPI 抛的 `AutojsException` 原码透传（`ERR_FILE_NOT_FOUND` / `ERR_IO` /
 * `ERR_INVALID_PARAM` 不折叠）；缺参/空白路径 → `ERR_INVALID_PARAM`；未知方法 →
 * `ERR_NOT_IMPLEMENTED`（含 `unzip` 这种没约定过的别名 —— 不猜，facade 只发 `extract`）。
 */
class ZipNamespaceHandler(
    private val archiver: ZipArchiver,
) : RpcNamespaceHandler() {
    override suspend fun dispatch(request: BridgeRequest): BridgeResponse = when (request.method) {
        "compress" -> compress(request)
        "extract" -> extract(request)
        else -> err(request, ErrorCode.ERR_NOT_IMPLEMENTED,
            "未知 zip 方法: ${request.method}",
        )
    }

    private suspend fun compress(request: BridgeRequest): BridgeResponse {
        val fields = request.decodeObject()
        val (source, archive) = pathOf(request, fields, "source") to pathOf(request, fields, "archive")
        return run {
            archiver.compress(source, archive)
            ok(request, "true")
        }
    }

    private suspend fun extract(request: BridgeRequest): BridgeResponse {
        val fields = request.decodeObject()
        val (archive, targetDir) = pathOf(request, fields, "archive") to pathOf(request, fields, "targetDir")
        return run {
            archiver.extract(archive, targetDir)
            ok(request, "true")
        }
    }

    /** 取非空白路径字段；非法（缺/非字符串/空白/NUL 字符）抛 IllegalArgumentException。 */
    private fun pathOf(
        request: BridgeRequest,
        fields: Map<String, DomainJson.Value>,
        key: String,
    ): Path {
        val s = request.requiredStr(fields, key)
        if (s.isBlank()) throw IllegalArgumentException("$key 不得为空白")
        // Path.of 对 NUL 等非法字符抛 InvalidPathException（IllegalArgumentException 子类）→ 调用方折叠
        return Path.of(s)
    }
}
