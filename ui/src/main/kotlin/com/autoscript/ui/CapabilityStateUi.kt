package com.autoscript.ui

import com.autoscript.domain.host.CapabilityCenterSnapshot
import com.autoscript.domain.host.CapabilityRow
import com.autoscript.domain.host.InstallSize
import com.autoscript.domain.permission.Capability
import com.autoscript.domain.permission.CapabilityState

/**
 * 能力中心呈现态（纯数据，Compose 之外可 JVM 测）。
 *
 * 三态各自**怎么说给用户听**是呈现层的事（`:domain` 只给 `GRANTED/DEGRADED/DENIED`
 * 三个字面量），但映射本身必须在可测面上 —— 把 DENIED 渲染成"未开启"会让用户以为
 * 「还没开」，而实际是「被拒了，要去系统里改」，两者该做的事不一样。
 *
 * 四条诚实规则（与 `HomeState` 同一条纪律）：
 * - [loaded] = false —— 还没读到（首帧/读取失败）。此时 [rows] 为空、[loadError] 给出原因；
 *   **不冒充**「一个能力都没有」（那是另一种事实：读成功但清单为空，现实中不存在 ——
 *   `Capability.entries` 恒非空，所以空行集只可能是没读到）；
 * - 每行都显示**引导文案**（`PermissionCenter.guideText` 的同一份）：GRANTED 时它也说明了
 *   当前是什么态，不按三态去猜该不该显示；
 * - 「去授权」按钮的显隐取 [CapabilityRow.canRequestGrant]（`:domain`
 *   `CapabilityLifecycle` 的判据）—— 呈现层不自己写 `state != GRANTED`，那是第二套判据；
 * - 降级中的定时任务**单列一段**（§8.6 承诺「可能偏差」要在 UI 上标注）：它们不是
 *   权限问题，塞进能力行里会让人找不到；
 * - 安装体积**单列一段**（§15 的 E1 处置：2026-10-02 拍板「接受超支并在能力中心明示」）：
 *   超支是既成事实，不披露等于让用户装完才发现。没量到就说没量到，不显示 0。
 */
data class CapabilityCenterState(
    val loaded: Boolean,
    val loadError: String?,
    val rows: List<CapabilityRowState>,
    val degradedAlarmTaskIds: List<String>,
    val installSize: InstallSizeState?,
) {
    companion object {
        /** 首帧哨兵：没读到过（**不是**"读成功但为空"，见类 KDoc）。 */
        val NOT_LOADED = CapabilityCenterState(
            loaded = false,
            loadError = null,
            rows = emptyList(),
            degradedAlarmTaskIds = emptyList(),
            installSize = null,
        )

        /** 读取成功。 */
        fun of(snapshot: CapabilityCenterSnapshot): CapabilityCenterState = CapabilityCenterState(
            loaded = true,
            loadError = null,
            rows = snapshot.rows.map { CapabilityRowState.of(it) },
            degradedAlarmTaskIds = snapshot.degradedAlarmTaskIds,
            // null（没量到）原样传下去：UI 那一条「未量到」与「量到了 0」必须长得不一样。
            installSize = snapshot.installSize?.let { InstallSizeState.of(it) },
        )

        /**
         * 读取失败。**保留原异常文案**（`message` 为 null 时退到类名）——
         * 吞成一句"读取失败"会让现场无法区分是 ROM 查询崩了还是装配没接线。
         */
        fun failed(t: Throwable): CapabilityCenterState = CapabilityCenterState(
            loaded = false,
            loadError = t.message ?: t.javaClass.simpleName,
            rows = emptyList(),
            degradedAlarmTaskIds = emptyList(),
            installSize = null,
        )
    }
}

/**
 * 一行能力的呈现态。
 *
 * @property title 能力的用户可读名（[label] 的落点）。
 * @property stateLabel 三态的中文说法（[stateLabel] 的落点）—— 逐态不同，
 *   因为"用户该做什么"逐态不同：GRANTED 什么都不用做、DEGRADED 能用但受限、
 *   DENIED 必须去系统里改。
 * @property guide 引导文案（原样透传，不截断不加工）。
 * @property canRequestGrant 「去授权」按钮显隐（`:domain` 判据的投影，见 [CapabilityRow]）。
 */
data class CapabilityRowState(
    val capability: Capability,
    val title: String,
    val state: CapabilityState,
    val stateLabel: String,
    val guide: String,
    val canRequestGrant: Boolean,
) {
    companion object {
        fun of(row: CapabilityRow): CapabilityRowState = CapabilityRowState(
            capability = row.capability,
            title = label(row.capability),
            state = row.state,
            stateLabel = stateLabel(row.state),
            guide = row.guide,
            canRequestGrant = row.canRequestGrant,
        )

        /** 能力名的中文说法。`else` 分支回枚举名 —— 新增能力忘配文案时显示英文名而不是空白。 */
        fun label(capability: Capability): String = when (capability) {
            Capability.ACCESSIBILITY -> "无障碍服务"
            Capability.SCREEN_CAPTURE -> "屏幕采集"
            Capability.OVERLAY -> "悬浮窗"
            Capability.NOTIFICATION -> "通知（任务提醒）"
            Capability.SCHEDULE_EXACT_ALARM -> "精确闹钟"
            Capability.ROOT -> "root"
            Capability.ADB_INPUT -> "ADB 输入（Shizuku）"
            Capability.POST_NOTIFICATIONS -> "通知发送权限"
        }

        /**
         * 三态的中文说法。逐态对应 `PermissionCenter.guideText` 的承诺：
         * DEGRADED 是"能用但受限"（不是"不能用"），DENIED 是"被拒，要去系统里改"。
         */
        fun stateLabel(state: CapabilityState): String = when (state) {
            CapabilityState.GRANTED -> "可用"
            CapabilityState.DEGRADED -> "降级可用"
            CapabilityState.DENIED -> "被拒绝"
        }
    }
}

/**
 * 安装体积的呈现态（§15 的 E1 处置；文案与换算都在这里，让「说成多少 MB」也可测）。
 *
 * **为什么自己算 MB 而不在装配层算好**：换算口径（MiB vs MB、按什么除）是个会被漂移的
 * 决定，放进纯 JVM 呈现态才能被 `CapabilityCenterStateTest` 钉住。
 *
 * @property engineFilesPresent 引擎 .so 真的在不在。false 时 UI **如实说「引擎未随包」**——
 *   那正是「装了个跑不了脚本的壳」的事实，隐去体积会让人以为装全了。
 */
data class InstallSizeState(
    val totalBytes: Long,
    val engineBytes: Long,
    val engineFilesPresent: Boolean,
) {
    /** 给用户看的那句话。三件事都要在：总数、其中引擎多少、为什么这么大。 */
    fun text(): String {
        val total = "${mib(totalBytes)} MiB"
        if (!engineFilesPresent) {
            return "安装体积 $total（引擎未随包，脚本暂时跑不了；完整说明见 docs/design/13-roadmap-budget.md）"
        }
        val rest = (totalBytes - engineBytes).coerceAtLeast(0L)
        return "安装体积 $total（其中引擎 ${mib(engineBytes)} MiB，其余 ${mib(rest)} MiB）"
    }

    companion object {
        fun of(size: InstallSize) = InstallSizeState(
            totalBytes = size.totalBytes,
            engineBytes = size.engineBytes,
            engineFilesPresent = size.engineFilesPresent,
        )

        /** MiB（1 MiB = 1024² B），一位小数。**不是** MB —— 两套口径混用正是"体积对不上"的来源。 */
        fun mib(bytes: Long): String = String.format("%.1f", bytes / (1024.0 * 1024.0))
    }
}
