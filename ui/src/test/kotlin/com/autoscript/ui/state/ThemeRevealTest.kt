package com.autoscript.ui.state

import kotlin.math.hypot
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * 圆形揭示的半径（[ThemeReveal.maxRadius]）：**判据是"四个角都在圆内"**，
 * 不是"半径等于某条边的距离"—— 差一点点就有一块旧主题留在屏幕上。
 */
class ThemeRevealTest {

    @Test
    fun `圆心在角上_半径就是对角线`() {
        assertEquals(hypot(100f, 200f), ThemeReveal.maxRadius(0f, 0f, 100f, 200f), 1e-3f)
        assertEquals(hypot(100f, 200f), ThemeReveal.maxRadius(100f, 200f, 100f, 200f), 1e-3f)
    }

    @Test
    fun `圆心在正中_半径是半对角线`() {
        assertEquals(hypot(50f, 100f), ThemeReveal.maxRadius(50f, 100f, 100f, 200f), 1e-3f)
    }

    @Test
    fun `盖满的判据是四个角都在圆内`() {
        // 真机尺寸 + 右上角那颗 ⋮ 附近的圆心（顶栏右侧）：最远的是左下角。
        val width = 1080f
        val height = 2400f
        val originX = 960f
        val originY = 120f
        val radius = ThemeReveal.maxRadius(originX, originY, width, height)
        val corners = listOf(0f to 0f, width to 0f, 0f to height, width to height)
        corners.forEach { (x, y) ->
            val distance = hypot(x - originX, y - originY)
            assertTrue(distance <= radius + 1e-3f, "角 ($x, $y) 距圆心 $distance > 半径 $radius")
        }
        // 最远那个角**恰好**落在圆上（半径不是"够大"而是"刚好"—— 大了动画会显得拖）。
        val farthest = corners.maxOf { (x, y) -> hypot(x - originX, y - originY) }
        assertEquals(farthest, radius, 1e-3f)
    }

    @Test
    fun `转深色_圆从0长到1`() {
        assertEquals(0f, ThemeReveal.circleFraction(0f, toDark = true), 1e-6f)
        assertEquals(0.25f, ThemeReveal.circleFraction(0.25f, toDark = true), 1e-6f)
        assertEquals(1f, ThemeReveal.circleFraction(1f, toDark = true), 1e-6f)
    }

    @Test
    fun `转浅色_圆从1缩回0`() {
        // TG 的两个方向不是同一段动画的镜像：转浅色时裁的是**旧主题那张底片**，
        // 起始半径 = 满、结束 = 0（`LaunchActivity` 里三元表达式的另一半）。
        assertEquals(1f, ThemeReveal.circleFraction(0f, toDark = false), 1e-6f)
        assertEquals(0.75f, ThemeReveal.circleFraction(0.25f, toDark = false), 1e-6f)
        assertEquals(0f, ThemeReveal.circleFraction(1f, toDark = false), 1e-6f)
    }

    @Test
    fun `旧底片铺哪一侧_转浅色在圆内_转深色在圆外`() {
        // 这一位是半径占比之外**另一个**必须分开记的事实（TG 的 createCircularReveal
        // 裁的是不同的视图：转深色裁内容、转浅色裁那张旧底片）。
        assertTrue(ThemeReveal.oldFrameInside(toDark = false), "转浅色时旧底片必须在圆内（缩回按钮）")
        assertTrue(!ThemeReveal.oldFrameInside(toDark = true), "转深色时旧界面必须留在圆外")
    }

    @Test
    fun `两个方向的起点都是整屏旧主题_终点都是整屏新主题`() {
        // 写反这一对（半径与铺哪一侧）会让动画**从新主题那一屏开始往回长** ——
        // 中段看过去一样是个圆，只有头一帧能看出闪了一下。故按"谁铺满整屏"钉端点：
        // 圆半径 0 + 旧帧在外 = 整屏旧；圆半径满 + 旧帧在内 = 整屏旧。
        listOf(true, false).forEach { toDark ->
            val inside = ThemeReveal.oldFrameInside(toDark)
            val r0 = ThemeReveal.circleFraction(0f, toDark)
            val r1 = ThemeReveal.circleFraction(1f, toDark)
            assertTrue((r0 == 0f && !inside) || (r0 == 1f && inside), "toDark=$toDark 的起点不是整屏旧主题")
            assertTrue((r1 == 1f && !inside) || (r1 == 0f && inside), "toDark=$toDark 的终点不是整屏新主题")
        }
    }

    @Test
    fun `两个方向在每一处都互补_映射写反会让某个方向根本不动`() {
        // 不变量：进度 0 与 1 两处，两个方向合起来必然一个是"满屏旧主题"、一个是
        // "满屏新主题"；中间任何时刻两者互补（0.5 处都是 0.5）。映射写反会让某个方向的
        // 占比恒为 0 或恒为 1 —— 那种错编译、lint、肉眼截图都看不出来，只有这条能抓住。
        listOf(0f, 0.5f, 1f).forEach { p ->
            val dark = ThemeReveal.circleFraction(p, toDark = true)
            val light = ThemeReveal.circleFraction(p, toDark = false)
            assertEquals(1f, dark + light, 1e-6f, "进度 $p 处两个方向不互补")
        }
    }
}
