package com.autoscript.domain.automation

/**
 * 手势输入模型（docs §9.1 `dispatchGesture` + §9.3 手势 DSL）。
 * 与 Android GestureDescription 语义对齐：多笔画，每笔画一起点 + 持续时长；
 * 坐标为逻辑像素（与 [UiBounds] 同口径）。
 *
 * 校验内建在构造器（非法即抛 IllegalArgumentException，桥 handler 折叠为
 * ERR_INVALID_PARAM，绝不把非法手势发往系统服务）。
 * touchDown/Move/Up 统一原语随 root `sendevent` / Shizuku-ADB 实现落地（P1/P2，
 * 见 §9.3）；P0 只有无障碍默认实现（[InputProvider] 单方法）。
 */
data class GesturePoint(val x: Int, val y: Int) {
    init {
        require(x >= 0 && y >= 0) { "手势坐标不得为负 x=$x y=$y" }
    }
}

data class GestureStroke(
    val points: List<GesturePoint>,
    val startDelayMillis: Long = 0,
    val durationMillis: Long = 100,
) {
    init {
        require(points.isNotEmpty()) { "笔画至少包含一个点" }
        require(startDelayMillis >= 0) { "startDelay 不得为负: $startDelayMillis" }
        require(durationMillis > 0) { "duration 必须 > 0: $durationMillis" }
    }
}

data class GestureInput(val strokes: List<GestureStroke>) {
    init {
        require(strokes.isNotEmpty()) { "手势至少包含一个笔画" }
    }
}

/**
 * 输入通道 SPI（§9.3）：无障碍 / root / Shizuku-ADB 三条**平级**通道的统一入口，
 * 由 [InputChannel] 选路（**不是降级链** —— 选哪条走哪条，不可用即失败）。
 * - [canPerformGestures] 即无障碍服务能力位 `CAPABILITY_CAN_PERFORM_GESTURES`
 *   （系统没有 `AccessibilityManager.canPerformGestures()` 方法，AOSP 实证；运行期
 *   读服务 capability 位）：false 时调用方不得发手势，由能力中心引导用户启用；
 * - [dispatchGesture] 回 false = 系统拒绝执行（非能力问题，不抛错，与 click 同口径）。
 *
 * **坐标点击（[tap]）与手势分开**：tap 是「按坐标点一下」，手势是「按轨迹划」。
 * 无障碍有 `dispatchGesture` 这条原生路；root/adb 走 shell（`input tap` / `input swipe`）。
 * 缺省实现把 [tap] 表达成一条**零长度单笔画** —— 那不是新增能力，是把既有手势面
 * 用同一套坐标表达出来（`InMemoryInputProvider` 与 `AndroidGestureInput` 因此零改动）。
 *
 * [tap] 的 [durationMillis] 是**按下到抬起的时长**（长按 = 调大它）：无障碍路径由
 * 笔画时长天然表达；shell 路径映射成 `input swipe x y x y <ms>`（同点滑 = 长按，
 * 这是 shell 面唯一能表达按住时长的写法）。
 */
interface InputProvider {
    val canPerformGestures: Boolean
    suspend fun dispatchGesture(gesture: GestureInput): Boolean

    /** 坐标点击（[channel] 由选路层决定，实现方只管「这条通道怎么点」）。 */
    suspend fun tap(x: Int, y: Int, durationMillis: Long = 0): Boolean =
        dispatchGesture(
            GestureInput(
                listOf(
                    GestureStroke(
                        points = listOf(GesturePoint(x, y), GesturePoint(x, y)),
                        durationMillis = durationMillis.coerceAtLeast(1),
                    ),
                ),
            ),
        )
}
