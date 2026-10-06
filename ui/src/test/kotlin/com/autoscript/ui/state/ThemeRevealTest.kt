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
}
