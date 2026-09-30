package com.autoscript.platform.system

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
import com.autoscript.domain.automation.ImageAnalyzer
import com.autoscript.domain.storage.DataStore
import com.autoscript.domain.bridge.NamespaceHandler

/**
 * `shell` / `device` / `app` / `floatingWindow` 四个命名空间的桥处理器 + 系统面
 * handler 工厂束 [SystemNamespaces]（docs §9.4/§9.6/§12.2；JS 对偶 `extras.ts`）。
 *
 * **归属（审查步骤 6，2026-09-30）**：自 `:platform:capabilities` 迁入本模块 —— 这批
 * handler 的 Android 实现与 SPI 替身本就住这里，语义层与实现同模块收拢；§12.2 原
 * 「语义层 handler 不住 system」口径已反转（记入 design-decisions）。capabilities
 * 收敛为 a11y/screen/dialogs 三个无障碍面（`dialogs` handler 拆去
 * `com.autoscript.platform.capabilities.dialogs`）。
 *
 * **能力门禁不在这里**：`:app-service:permission-center` 的 `PermissionFacade` 住
 * `:app-service:*`，而本模块的 archUnit 黑名单含 `com.autoscript.appservice..`（§6）。
 * 所以门禁由装配层在调用工厂前完成（`ensure(Capability.OVERLAY)` 等），
 * 本模块只负责**能力已保证之后的语义**：参数校验、句柄记账、分类错误。
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

// ── 系统面 handler 工厂束（装配收拢：PlatformWiring 不逐个 import handler 类）──────

/**
 * 系统面命名空间的构造工厂（§4.1/§6，原 `CapabilityNamespaces` 的系统面部分随
 * handler 迁入本模块；capabilities 侧保留 a11y/screen/dialogs 三件）。
 *
 * 本对象无逻辑：不解释 payload、不吞错误、不改错误码（§7 桥侧只透传）——
 * 只做「SPI 参数 → handler 实例」的构造收拢。装配层（`PlatformWiring`）拿现成
 * `NamespaceHandler` 挂 Router，只依赖 `:domain` 的类型形状。
 */
object SystemNamespaces {

    /** `shell` 命名空间：`exec`/`shell` 两方法（JS `extras.ts` 的 `shell()` 只是 `exec()` 的别名）。 */
    fun shell(
        executor: ShellExecutor,
        defaultTimeoutMillis: Long = DEFAULT_SHELL_TIMEOUT_MILLIS,
    ): NamespaceHandler {
        return ShellNamespaceHandler(executor, defaultTimeoutMillis)
    }

    /** `device` 命名空间：`model`/`sdkInt`（P0 最小集，§12.3）。 */
    fun device(info: DeviceInfoProvider): NamespaceHandler {
        return DeviceNamespaceHandler(info)
    }

    /** `app` 命名空间：`launch`/`currentPackage`。 */
    fun app(launcher: AppLauncher): NamespaceHandler {
        return AppNamespaceHandler(launcher)
    }

    /** `floatingWindow` 命名空间：`create`/`close`（§9.4）。 */
    fun floatingWindow(host: FloatingWindowHost): NamespaceHandler {
        return FloatingWindowNamespaceHandler(host)
    }

    /**
     * `datastore` 命名空间（§9.6）：KV 六方法（`get`/`put`/`remove`/`contains`/
     * `keys`/`clear`）。参数即 [com.autoscript.domain.storage.DataStore] SPI 实现
     * （测试传 `InMemoryDataStore`，真机传 `:platform:system` 的 SQLite 实现）。
     * 存储面不共担五命名空间的门禁（应用私有 KV 无需授权）→ 独立注入缝
     * `AppShell.assemble` 的 `datastoreHandler`，不入 `systemHandlers` 束。
     */
    fun datastore(store: DataStore): NamespaceHandler {
        return DatastoreNamespaceHandler(store)
    }

    /**
     * `zip` 命名空间（§9.6）：`compress`/`extract` 两方法。参数即
     * [ZipArchiver] SPI 实现（测试传假归档器，
     * 真机传 `:platform:system` 的 `JdkZipArchiver`）。同 datastore：
     * 无共担门禁 → 独立注入缝 `AppShell.assemble` 的 `zipHandler`。
     */
    fun zip(archiver: ZipArchiver): NamespaceHandler {
        return ZipNamespaceHandler(archiver)
    }

    /**
     * `settings` 命名空间（§9.6 系统设置面）：`canWrite`/`getString`/`getInt`/
     * `putString`/`putInt` 五方法（照抄 [SystemSettings] 的两型，不提供猜型的 `get`/`put`
     * 别名 —— 串与数是两套系统 API，推断就是发明策略）。参数即 [SystemSettings] SPI 实现
     * （测试传真读假写的替身，真机传 `:platform:system` 的 `AndroidSystemSettings`）。
     * 同 datastore/zip：三者同属 §9.6 存储面、与五命名空间无共担门禁 → 独立注入缝
     * `AppShell.assemble` 的 `settingsHandler`。
     */
    fun settings(systemSettings: SystemSettings): NamespaceHandler {
        return SettingsNamespaceHandler(systemSettings)
    }

    /**
     * `notification` 命名空间（§12.2）：`canPost`/`post`/`cancel` 三方法。参数即
     * [NotificationPoster] SPI 实现（测试传真读假写的替身，真机传 `:platform:system`
     * 的 `AndroidNotificationPoster`）。同 datastore/zip/settings：**独立注入缝**
     * `AppShell.assemble` 的 `notificationHandler`，不入 `systemHandlers` 束 ——
     * 通知的门禁是 `POST_NOTIFICATIONS`，判据在 SPI（与那五个不共担）。
     */
    fun notification(poster: NotificationPoster): NamespaceHandler {
        return NotificationNamespaceHandler(poster)
    }

    /**
     * `clipboard` 命名空间（§12.2 系统剪贴板面）：`getText`/`setText` 两方法。
     * 参数即 [Clipboard] SPI 实现（测试传内存替身，真机传 `:platform:system` 的
     * `AndroidClipboard`）。同 datastore/zip/settings/notification：**独立注入缝**
     * `AppShell.assemble` 的 `clipboardHandler`，不入 `systemHandlers` 束 ——
     * 剪贴板无门禁（读受限是系统的 null 答案、写不受限，判据在 SPI 自己身上）。
     */
    fun clipboard(clipboard: Clipboard): NamespaceHandler {
        return ClipboardNamespaceHandler(clipboard)
    }

    /**
     * `sensors` 命名空间（§12.2 传感器面）：`isSupported`/`register`/`unregister`/
     * `unregisterAll`/`drain` 五方法。参数即 [SensorSource] SPI 实现（测试传内存替身，
     * 真机传 `:platform:system` 的 `AndroidSensorSource`）。同 datastore/zip/settings/
     * notification/clipboard：**独立注入缝** `AppShell.assemble` 的 `sensorsHandler`，
     * 不入 `systemHandlers` 束 —— P0 名单无运行时门禁（未知名→`ERR_NOT_SUPPORTED`、
     * 系统拒收→`ERR_SERVICE_DISABLED`，判据在 SPI 自己身上）。
     */
    fun sensors(sensors: SensorSource): NamespaceHandler {
        return SensorsNamespaceHandler(sensors)
    }

    /**
     * `images` 命名空间（§9.2 图像面）：`decode`/`matchTemplate`/`findImage`/`release`
     * 四方法。参数即 [ImageAnalyzer] SPI 实现（测试传假分析器，真机传
     * `:bridge:image` 的 native 管线）。同 datastore/zip/settings/notification/clipboard/
     * sensors：**独立注入缝** `AppShell.assemble` 的 `imagesHandler`，不入
     * `systemHandlers` 束 —— 图像面无共担门禁（文件缺失/句柄失效判据在 SPI 自己身上）。
     */
    fun images(analyzer: ImageAnalyzer): NamespaceHandler {
        return ImagesNamespaceHandler(analyzer)
    }
}
