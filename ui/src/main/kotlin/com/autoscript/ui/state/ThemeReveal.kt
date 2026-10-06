package com.autoscript.ui.state

import kotlin.math.hypot
import kotlin.math.max

/**
 * 主题切换的圆形揭示（TG 日夜切换那颗的形态：从按下的地方长出一个圆，圆内已经是新主题）。
 *
 * **为什么单独一个纯对象**（与 [Status]/`ProjectState` 同一条理由）：几何算错不会崩，
 * 只会"差一点点"—— 圆没盖满时屏幕角上留着旧主题的边，圆盖过头时动画早该结束还在长。
 * 这段判断是纯函数，抽出来就能在 JVM 上钉住（见 `ThemeRevealTest`）。
 */
object ThemeReveal {

    /**
     * 从 ([originX], [originY]) 起、盖满 `[width] × [height]` 这块矩形所需的**最小半径**。
     *
     * = 圆心到**最远那个角**的距离（四个角里取最远；圆心按约定落在矩形内）。
     * 不是"到最远那条边的距离"：那样对角线方向的两个角永远盖不住，动画跑完屏幕上会
     * 留着两块旧主题的三角 —— 这正是本对象存在的理由。
     */
    fun maxRadius(originX: Float, originY: Float, width: Float, height: Float): Float {
        val dx = max(originX, width - originX)
        val dy = max(originY, height - originY)
        return hypot(dx, dy)
    }

    /**
     * 动画进度 → **圆的占比**（0 = 圆没长出来，1 = 圆盖满整屏）。
     *
     * TG 的两个方向**不是同一段动画的镜像**（`LaunchActivity` 的 `needSetDayNightTheme`）：
     * ```
     * anim = ViewAnimationUtils.createCircularReveal(
     *     toDark ? drawerLayoutContainer : themeSwitchImageView,   // 裁谁
     *     pos[0], pos[1],
     *     toDark ? 0 : finalRadius,                                // 起始半径
     *     toDark ? finalRadius : 0);                               // 结束半径
     * ```
     * 转深色：裁**内容**（新主题）0 → R，圆**长大**，圆外是旧主题那张底片；
     * 转浅色：裁**旧底片** R → 0，圆**缩回**按钮，圆外露出的是新主题。
     * 两个方向共用一条进度曲线，差别只在这一处取反 —— 所以它是一个纯函数。
     */
    fun circleFraction(progress: Float, toDark: Boolean): Float = if (toDark) progress else 1f - progress

    /**
     * 旧主题那一帧铺在**圆内**吗（圆外铺新主题）。
     *
     * 这一位是 [circleFraction] 之外**另一个**必须分开记的事实 —— 两个方向不是同一张画的镜像：
     * - 转深色（`oldFrameInside = false`）：新主题的圆从按钮长大，旧界面留在**圆外**；
     * - 转浅色（`oldFrameInside = true`）：旧底片**缩回**按钮，所以它在**圆内**。
     *
     * 把这一位写反，动画就会从"整屏新主题"开始往回长 —— 而两种写法在进度 0.5 处都画得出
     * 一个圆，肉眼要盯着头一帧才看得出。故它是纯函数，由 `ThemeRevealTest` 钉住
     * **两个方向的起点都必须是整屏旧主题**。
     */
    fun oldFrameInside(toDark: Boolean): Boolean = !toDark
}
