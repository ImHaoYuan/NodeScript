package com.autoscript.domain.permission

/**
 * **桥面能力**（A5，§11）：宿主为一次执行发放的、**桥命名空间级**的授权位。
 *
 * ## 为什么与设备能力 [Capability] 是两个枚举
 *
 * 两者问的是**正交**的两件事，共用一个枚举会把「脚本授权」暴露成「系统设置项」：
 *
 * | | [Capability]（设备能力） | 本枚举（桥面能力） |
 * |---|---|---|
 * | 问什么 | **系统**给不给这个 App（§9.5 三态门禁） | **这次执行**被宿主授权用哪些桥面 |
 * | 谁回答 | [PermissionFacade] 现问系统（Settings/服务状态） | 宿主在 spawn 时按来源策略算（[ScriptAuthorizationPolicy]） |
 * | 在哪可见 | 能力中心 UI（`HostSummary.CapabilityRow`，逐项列给用户看） | 不进 UI：没有系统授权页可跳 |
 * | 能改吗 | 用户去系统设置改 | 用户改不了；改的是脚本来源/宿主装配 |
 * | 有无降级 | 有（GRANTED/DEGRADED/DENIED） | 无三态：**在或不在**（deny-by-default） |
 *
 * 因此本枚举里的项**没有** Android 系统授权页，也不该出现在能力中心的行清单里 ——
 * 混进 [Capability] 会让 `Capability.entries` 把 `CROSS_SCRIPT_CONTROL` 这类脚本权限
 * 当成一个设备设置项列出来，而 `AndroidSystemStateReader`/`AndroidGrantLauncher` 只能
 * 被迫给它们编一个假的系统态/假页面。**两套目录各自完整，谁都不许给对方兜底。**
 *
 * ## 与设备面同名的项（[ACCESSIBILITY]/[SCREEN_CAPTURE]/[OVERLAY]/[NOTIFICATION]）
 *
 * 名字与设备面重合，但**语义不同**：设备面的 `POST_NOTIFICATIONS` 问「系统允不允许发通知」，
 * 桥面的 [NOTIFICATION] 问「这次执行允不允许调 `notification.*`」。**两道闸都要过**：
 * 桥面掩码是**先决条件**，不是替代品 —— 掩码给了 `ALL` 也不会让系统未授权的 a11y 变可用
 * （各 handler 的既有设备门禁照旧执行，本批不动那条路径）。
 * 同名是有意的（现场用同一个词说同一件事），但**类型不同、来源不同、不互相赋值**。
 *
 * ## 位序纪律（[CapabilityMask] 的位 = 本枚举的 `ordinal`）
 *
 * ⚠️ **只许在末尾追加**。中间插入会让已发放的掩码位序整体漂移。
 * **本批没有持久化**：掩码只在内存里活（签发 → 连接 → 进程结束即消失），
 * 没有落盘、没有跨版本读回 —— 所以这里**不**声称「有版本号能救」。
 * `CapabilityMask.fromBits` 的未知位守卫是**纵深防御**（防宿主策略代码写错位），
 * 不是持久化兼容方案。真要做持久化掩码，必须先有稳定编号（显式 id 而非 ordinal）
 * + 版本字段，那是后续批次的事，别用「有守卫」冒充「已兼容」。
 */
enum class BridgeCapability {
    // —— 与设备面同名、但语义是「这次执行被授权用这个桥面」（见类 KDoc）——
    /** 读无障碍界面树 / 走无障碍通道（`a11y.*`）。 */
    ACCESSIBILITY,

    /** 屏幕采集帧源（`screen.*`）。 */
    SCREEN_CAPTURE,

    /** 悬浮窗与对话框编排（`dialogs.*`/`floatingWindow.*`）。 */
    OVERLAY,

    /** 发通知（`notification.*`）。 */
    NOTIFICATION,

    // —— 桥面专有位 ——
    /** 输入注入（a11y 手势/点击、root/Shizuku 通道）。 */
    INPUT_INJECTION,

    /** 剪贴板读写（`clipboard.*`）。 */
    CLIPBOARD,

    /** 传感器（`sensors.*`）。 */
    SENSORS,

    /** 改宿主/系统状态（`shell`、`settings`、`power_manager`、`app` 拉起）。 */
    DEVICE_CONTROL,

    /** 读设备事实（型号/SDK 等，`device.*`）。 */
    DEVICE_DATA_READ,

    /** 应用私有 KV（`datastore.*`）。 */
    LOCAL_STORAGE,

    /** 应用私有目录文件读写（`zip`/`images` 的读图面）。 */
    FILESYSTEM_ACCESS,

    /** npm 安装（供应链面：写 node_modules + 跑安装会话，`npm.*`）。 */
    PACKAGE_INSTALL,

    /** 写宿主任务注册表（`workManager.*`、`engines.exec` 的排期面）。 */
    SCHEDULER_WRITE,

    /** **观察**其他执行（`engines.status`/`engines.poolStats`）。 */
    CROSS_SCRIPT_OBSERVE,

    /** **控制**其他执行 / 拉起新执行（`engines.stop`/`engines.exec`）。 */
    CROSS_SCRIPT_CONTROL,
}
