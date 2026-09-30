package com.autoscript.platform.capabilities

import com.autoscript.domain.bridge.decodeObject
import com.autoscript.domain.bridge.requiredStr
import com.autoscript.domain.bridge.optStr
import com.autoscript.domain.bridge.optLong
import com.autoscript.domain.bridge.requiredStrList
import com.autoscript.domain.bridge.requiredRef
import com.autoscript.domain.bridge.enumOrNull
import com.autoscript.domain.bridge.RpcNamespaceHandler
import com.autoscript.domain.bridge.BridgeResponse
import com.autoscript.domain.bridge.BridgeRequest
import com.autoscript.domain.json.DomainJson
import com.autoscript.domain.bridge.HandleRef
import com.autoscript.domain.core.AutojsException
import com.autoscript.domain.core.ErrorCode
import com.autoscript.domain.system.AppLauncher
import com.autoscript.domain.system.DeviceInfoProvider
import com.autoscript.domain.system.DialogChooseRequest
import com.autoscript.domain.system.DialogHost
import com.autoscript.domain.system.DialogMode
import com.autoscript.domain.system.DialogOutcome
import com.autoscript.domain.system.DialogPromptRequest
import com.autoscript.domain.system.FloatingWindowHost
import com.autoscript.domain.system.FloatingWindowSpec
import com.autoscript.domain.system.ShellExecutor
import com.autoscript.domain.system.ShellMode

/**
 * `dialogs` / `shell` / `device` / `app` / `floatingWindow` 五个命名空间的 JVM 可测实现
 * （docs §9.4 / §9.6 / §12.2；JS 对偶 `bridge/js/src/extras.ts`）。
 *
 * 为什么住 `:platform:capabilities` 而不是 `:platform:system`：§12.2 的注入缝是
 * `NamespaceHandler`（住 `:domain`），`BridgeRouter` 的 `RequestHandler` 只是它的
 * typealias。`:app` 禁止直连 `:platform`，所以「自有形状 → 桥信封」的转接必须发生在
 * 平台侧（本模块已有 [CapabilityNamespaces] 的先例），否则装配层要同时直连
 * `:platform:capabilities` 和 `:platform:system` 两个模块。
 *
 * **能力门禁不在这里**：`:app-service:permission-center` 的 `PermissionFacade` 住
 * `:app-service:*`，而本模块的 archUnit 黑名单含 `com.autoscript.appservice..`（§6）。
 * 所以门禁由装配层在调用本文件工厂前完成（`ensure(Capability.OVERLAY)` 等），
 * 本文件只负责**能力已保证之后的语义**：参数校验、句柄记账、分类错误。
 * 未接线的命名空间由 Router 如实回 ERR_NOT_IMPLEMENTED（§7.5），不伪造可用。
 *
 * 超时（铁律 3）：桥侧 TTL 由 `BridgeRequest.ttlMillis` 驱动，handler 必须在
 * TTL 内返回。`shell.exec` 的实现方超时（[DEFAULT_SHELL_TIMEOUT_MILLIS]）是宿主侧
 * 兜底：即使请求侧忘了给 TTL，也不会有无限等待的 `Runtime.waitFor`。
 */

// ── shell（§9.6）────────────────────────────────────────────────────

/** 默认 shell 超时：`child_process` 缺失的副作用由宿主侧兑现（§10 零 spawn），实现不得无限等。 */
const val DEFAULT_SHELL_TIMEOUT_MILLIS: Long = 30_000

class ShellNamespaceHandler(
    private val executor: ShellExecutor,
    private val defaultTimeoutMillis: Long = DEFAULT_SHELL_TIMEOUT_MILLIS,
) : RpcNamespaceHandler() {
    override suspend fun dispatch(request: BridgeRequest): BridgeResponse = when (request.method) {
        "exec", "shell" -> exec(request)
        else -> err(request, ErrorCode.ERR_NOT_IMPLEMENTED, "未知 shell 方法: ${request.method}")
    }

    private suspend fun exec(request: BridgeRequest): BridgeResponse {
        val fields = request.decodeObject()
        val cmd = request.requiredStr(fields, "cmd")
        val mode = request.enumOrNull(fields, "mode", ShellMode.DEFAULT) { ShellMode.valueOf(it.uppercase()) }
        val timeout = request.optLong(fields, "timeout") ?: defaultTimeoutMillis
        if (timeout <= 0) {
            return err(request, ErrorCode.ERR_INVALID_PARAM, "timeout 必须 > 0，实际 $timeout")
        }
        return run {
            val r = executor.exec(cmd, mode, timeout)
            ok(request, DomainJson.encode(
                    mapOf("code" to r.code.toLong(), "stdout" to r.stdout, "stderr" to r.stderr),
                ),
            )
        }
    }
}

// ── device（§9.6）───────────────────────────────────────────────────

class DeviceNamespaceHandler(private val info: DeviceInfoProvider) : RpcNamespaceHandler() {
    override suspend fun dispatch(request: BridgeRequest): BridgeResponse = when (request.method) {
        "model" -> {
            val p = info.profile()   // 构造期已校验（空型号/SDK<1 即拒）
            ok(request, DomainJson.encode(p.model))
        }
        "sdkInt" -> ok(request, DomainJson.encode(info.profile().sdkInt.toLong()))
        else -> err(request, ErrorCode.ERR_NOT_IMPLEMENTED, "未知 device 方法: ${request.method}")
    }
}

// ── app（§9.3/§12.2）────────────────────────────────────────────────

class AppNamespaceHandler(private val launcher: AppLauncher) : RpcNamespaceHandler() {
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

// ── floatingWindow（§9.4）───────────────────────────────────────────

class FloatingWindowNamespaceHandler(
    private val host: FloatingWindowHost,
) : RpcNamespaceHandler() {
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
