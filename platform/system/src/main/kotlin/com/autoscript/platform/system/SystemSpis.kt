package com.autoscript.platform.system

import android.content.Context
import com.autoscript.domain.storage.DataStore

/**
 * `:platform:system` 的实现入口（docs §12.2「分两层」的**下面那层**）：
 * 系统面十个 SPI 的 Android 实现一次性造齐（2026-09-30 审查步骤 6 起，SPI 契约本身
 * 也随迁本模块同包 —— 判据见 `SystemHostContracts.kt` KDoc），交装配层组合。
 *
 * **本类与 [SystemNamespaces] 的分工（同模块内仍两对象）**：本类负责「拿 `Context`
 * 造 Android 实现」（构造签名要 Android 类型），`SystemNamespaces` 负责「SPI →
 * `NamespaceHandler` 工厂束」（纯参数注入，单测直传假 SPI）。装配层 `PlatformWiring`
 * 拿两者组装 —— 本类不 new handler，handler 侧不碰 `Context`。
 *
 * **`dialogs` 仍不在此造**（理由从"待 P2"变成"分层归属"）：`DialogHost` 实现按
 * domain KDoc 约定住 `:platform:capabilities`（`AndroidDialogHost` 编排 +
 * `SystemDialogOps` 设备面），而平台模块之间没有依赖边 —— 构造归装配层
 * `PlatformWiring.of`（同 `overlayAvailable` 参数一并传入）。本类十件不变（sensors 本批补到十件；`dialogs` 仍不在此造）。
 *
 * 构造点在装配层（持有 `Context` 的 Android 侧）；本类不做权限判断（§9.5：
 * 门禁在 `PermissionFacade`，先判后取）。
 */
object SystemSpis {

    /**
     * 本模块当前能提供的十件（见 [Bundle] 字段注释）。
     * `overlayAvailable` 缺省恒假 —— 悬浮窗走 `TYPE_APPLICATION_OVERLAY`；
     * a11y 服务在跑时由上层传 `{ true }` 换成 `TYPE_ACCESSIBILITY_OVERLAY`。
     */
    fun of(
        context: Context,
        overlayAvailable: () -> Boolean = { false },
    ): Bundle {
        val app = context.applicationContext
        return Bundle(
            shell = AndroidShellExecutor(),
            device = AndroidDeviceInfoProvider(),
            app = AndroidAppLauncher(PackageManagerOps(app), UsageStatsEvents(app)),
            floatingWindow = AndroidFloatingWindowHost(
                ops = WindowManagerOps(app),
                overlayTypeAvailable = overlayAvailable,
            ),
            datastore = AndroidDataStore(SqliteKvOps(app)),
            zip = JdkZipArchiver(),
            settings = AndroidSystemSettings(SettingsSystemOps(app)),
            notification = AndroidNotificationPoster(NotificationOps(app)),
            clipboard = AndroidClipboard(ClipboardOps(app)),
            sensors = AndroidSensorSource(SensorOps(app)),
        )
    }

    /**
     * 十个 SPI 实现（`dialogs` 缺位，见 [SystemSpis] 的 KDoc）。
     * 字段声明成 SPI 类型而非具体类：上层只该看见契约，不背实现。
     *
     * `datastore` 是 SPI 束的成员、**不是** `systemHandlers` 束的成员：
     * handler 侧它是独立注入缝（存储面无共担门禁，§12.2 接线表）——
     * 两束形状不同是有意的，别对齐。
     */
    data class Bundle(
        val shell: ShellExecutor,
        val device: DeviceInfoProvider,
        val app: AppLauncher,
        val floatingWindow: FloatingWindowHost,
        val datastore: DataStore,
        val zip: ZipArchiver,
        val settings: SystemSettings,
        val notification: NotificationPoster,
        val clipboard: Clipboard,
        val sensors: SensorSource,
    )
}
