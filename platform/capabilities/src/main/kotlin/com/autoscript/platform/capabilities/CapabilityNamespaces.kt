package com.autoscript.platform.capabilities

import com.autoscript.domain.automation.FrameSource
import com.autoscript.domain.automation.ImageAnalyzer
import com.autoscript.domain.automation.InputProvider
import com.autoscript.domain.automation.UiActionExecutor
import com.autoscript.domain.automation.UiEventStream
import com.autoscript.domain.automation.UiNodeTreeReader
import com.autoscript.domain.bridge.BridgeResponse
import com.autoscript.domain.bridge.NamespaceHandler
import com.autoscript.domain.system.AppLauncher
import com.autoscript.domain.system.Clipboard
import com.autoscript.domain.system.DeviceInfoProvider
import com.autoscript.domain.system.DialogHost
import com.autoscript.domain.system.FloatingWindowHost
import com.autoscript.domain.system.NotificationPoster
import com.autoscript.domain.system.SensorSource
import com.autoscript.domain.storage.DataStore
import com.autoscript.domain.storage.SystemSettings
import com.autoscript.domain.storage.ZipArchiver
import com.autoscript.domain.system.ShellExecutor

/**
 * 能力 handler 的装配工厂束（§4.1/§6）。
 *
 * 审查步骤 4（2026-09-30）之后**本文件不再做字段转接**：全部 handler 直接实现
 * `:domain` 的 [NamespaceHandler]（基类 `RpcNamespaceHandler` 收口错误映射，
 * `BridgeRequestLite`/`ResponseLite` 与 a11y/screen 的自定义 Request/Response 已退役）。
 * 现存职责只剩「SPI 参数 → handler 实例」的构造收拢：装配层（`PlatformWiring`/
 * `AppShell.assemble`）拿现成 [NamespaceHandler] 挂 Router，只依赖 `:domain`（§6），
 * 不逐个 import 本模块的 14 个 handler 类。
 *
 * 本文件无逻辑：不解释 payload、不吞错误、不改错误码（§7 桥侧只透传）。
 * handler 自己的方法表/错误分类在 [A11yNamespaceHandler] / [ScreenNamespaceHandler]。
 */
object CapabilityNamespaces {

    /**
     * `a11y` 命名空间（§9.1）：窗口树 + 动作 + 输入通道的装配缝。
     * 缺省三参 = 内存实现（单测/骨架可测）；Android 真实现（AccessibilityNodeInfo 遍历 /
     * dispatchGesture）到位 = 传三块 SPI 实现（树、动作、输入，可选事件流）再构造 ——
     * 本函数不解释 payload（见上），只做构造收拢。
     */
    fun a11y(
        tree: UiNodeTreeReader,
        actions: UiActionExecutor,
        input: InputProvider = InMemoryInputProvider(),
        events: UiEventStream? = null,
    ): NamespaceHandler {
        return A11yNamespaceHandler(tree, actions, input, events)
    }

    /**
     * `screen` 命名空间（§9.2 / §8.8）：截图帧源，分类错误而非黑图。
     * 参数即 `FrameSource` SPI 实现 —— 本函数从不构造内存帧源（构造是调用方的事），
     * 单测传内存 producer、生产传 `ScreenshotSource(AndroidFrameProducer())`
     * （PlatformWiring 落点；MediaProjection 升级 = 换 producer）。
     */
    fun screen(source: FrameSource): NamespaceHandler {
        return ScreenNamespaceHandler(source)
    }

    // ── 系统侧五个命名空间（§9.4/§9.6，handler 见 SystemNamespaces.kt）──────

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

    /** `dialogs` 命名空间：`prompt`/`choose`（§9.4 BAL 安全路径）。 */
    fun dialogs(host: DialogHost): NamespaceHandler {
        return DialogsNamespaceHandler(host)
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
     * [com.autoscript.domain.storage.ZipArchiver] SPI 实现（测试传假归档器，
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
