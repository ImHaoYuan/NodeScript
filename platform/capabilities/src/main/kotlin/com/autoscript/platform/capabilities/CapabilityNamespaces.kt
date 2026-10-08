package com.autoscript.platform.capabilities

import com.autoscript.domain.automation.FrameSource
import com.autoscript.domain.automation.InputProvider
import com.autoscript.domain.automation.InputChannel
import com.autoscript.domain.automation.ScreenConsentBroker
import com.autoscript.domain.automation.ScreenRecordingController
import com.autoscript.domain.automation.UiActionExecutor
import com.autoscript.domain.automation.UiEventStream
import com.autoscript.domain.automation.UiNodeTreeReader
import com.autoscript.domain.bridge.NamespaceHandler
import com.autoscript.platform.capabilities.a11y.A11yNamespaceHandler
import com.autoscript.platform.capabilities.dialogs.DialogsNamespaceHandler
import com.autoscript.platform.capabilities.screen.MediaProjectionCapturer
import com.autoscript.platform.capabilities.screen.ScreenNamespaceHandler
import com.autoscript.domain.system.DialogHost
import com.autoscript.platform.capabilities.screen.AndroidFrameProducer
import com.autoscript.platform.capabilities.screen.InMemoryInputProvider
import com.autoscript.platform.capabilities.screen.ScreenshotSource

/**
 * a11y/screen/dialogs 三个无障碍面的 handler 装配工厂束（§4.1/§6）。
 *
 * 系统面十一件（shell/device/app/floatingWindow/datastore/zip/settings/notification/
 * clipboard/sensors/images）随 handler 迁去
 * `com.autoscript.platform.system.SystemNamespaces` —— 本文件只留无障碍面。
 *
 * **本文件不再做字段转接**：全部 handler 直接实现
 * `:domain` 的 [NamespaceHandler]（基类 `RpcNamespaceHandler` 收口错误映射，
 * `BridgeRequestLite`/`ResponseLite` 与 a11y/screen 的自定义 Request/Response 已退役）。
 * 现存职责只剩「SPI 参数 → handler 实例」的构造收拢：装配层（`PlatformWiring`/
 * `AppShell.assemble`）拿现成 [NamespaceHandler] 挂 Router，只依赖 `:domain`（§6），
 * 不逐个 import 本模块的 handler 类（本文件只剩 a11y/screen/dialogs 三件）。
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
        /**
         * 通道 → provider 表（§9.3 三通道）。**缺省只有 auto** —— 其余两条如实不可用
         * （`ERR_PERMISSION_DENIED`），不是「悄悄回落到 auto」。生产装配把三条都塞进来。
         */
        channels: Map<InputChannel, InputProvider> = mapOf(InputChannel.AUTO to input),
    ): NamespaceHandler {
        return A11yNamespaceHandler(tree, actions, input, events, channels)
    }

    /**
     * `screen` 命名空间（§9.2 / §8.8）：截图帧源，分类错误而非黑图。
     * 参数即 `FrameSource` SPI 实现 —— 本函数从不构造内存帧源（构造是调用方的事），
     * 单测传内存 producer、生产传 `ScreenshotSource(AndroidFrameProducer())`
     * （PlatformWiring 落点）。
     *
     * **两条帧源各自独立**：[projection] 是投屏会话面（§9.2 MediaProjection），
     * 缺省 null = 未接线 → `startCapturer` 走 [FrameSource.openSession] 的兼容路径。
     * 装配期分岔、运行期不互相顶替（见 [ScreenNamespaceHandler] KDoc）。
     */
    fun screen(
        source: FrameSource,
        projection: MediaProjectionCapturer? = null,
        consent: ScreenConsentBroker? = null,
        /**
         * 录屏腿（§9.2）：与 [projection] 并列的可选缝。缺省 null = 未接线 →
         * `startRecording` 如实 `ERR_NOT_IMPLEMENTED`（**绝不**退化成"录一段帧当视频"）。
         * 生产装配给的是同一个设备对象（两条腿共用一条 MediaProjection 会话账）。
         */
        recorder: ScreenRecordingController? = null,
    ): NamespaceHandler {
        return ScreenNamespaceHandler(source, projection, consent, recorder)
    }

    /** `dialogs` 命名空间：`prompt`/`choose`（§9.4 BAL 安全路径）。 */
    fun dialogs(host: DialogHost): NamespaceHandler {
        return DialogsNamespaceHandler(host)
    }

}
