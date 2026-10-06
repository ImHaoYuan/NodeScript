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
}
