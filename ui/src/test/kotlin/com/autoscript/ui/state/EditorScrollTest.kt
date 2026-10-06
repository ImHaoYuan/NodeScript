package com.autoscript.ui.state

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * 编辑器的行数几何（[EditorScroll]）：行号槽的位数、气泡里那个数、按钮去哪一头。
 *
 * 值得一条测试的理由：这几处全是 `floor`/`ceil` 的边界，写错一位在界面上只表现为
 * "气泡的数字偶尔差一个"，没人会盯着它数；而"往下拖该去最顶"这种方向如果写反，
 * 按钮就是**每次都把你送到反方向**，比没有这个按钮更糟。
 */
class EditorScrollTest {

    /** 行高 20px、视口 100px（= 正好 5 行）—— 取整数是为了让边界一眼可算。 */
    private val line = 20f
    private val viewport = 100f

    @Test
    fun `空文件也是一行_末行没有换行符也算`() {
        assertEquals(1, EditorScroll.lineCount(""))
        assertEquals(1, EditorScroll.lineCount("a"))
        assertEquals(2, EditorScroll.lineCount("a\n"))
        assertEquals(3, EditorScroll.lineCount("a\nb\nc"))
        assertEquals(4, EditorScroll.lineCount("a\nb\nc\n"))
    }

    @Test
    fun `上方的行数_只数完全看不见的那些`() {
        assertEquals(0, EditorScroll.linesAbove(0f, line))
        // 滚了半行：第一行还露着下半截，不算"屏幕外还有一行"。
        assertEquals(0, EditorScroll.linesAbove(10f, line))
        assertEquals(1, EditorScroll.linesAbove(20f, line))
        assertEquals(2, EditorScroll.linesAbove(59f, line))
    }

    @Test
    fun `下方的行数_只数完全看不见的那些`() {
        // 视口正好盖住前 5 行（0..4），共 10 行 → 下面还有 5 行。
        assertEquals(5, EditorScroll.linesBelow(0f, viewport, line, 10))
        assertEquals(3, EditorScroll.linesBelow(40f, viewport, line, 10))
        // 滚到底：一行都不剩（负数要夹到 0，否则气泡会显示 -2）。
        assertEquals(0, EditorScroll.linesBelow(100f, viewport, line, 10))
        assertEquals(0, EditorScroll.linesBelow(500f, viewport, line, 10))
        // 正文比一屏还短时，下方**没有**"屏幕外的行"。
        assertEquals(0, EditorScroll.linesBelow(0f, viewport, line, 3))
    }

    @Test
    fun `行高为0不除零_一律给0`() {
        assertEquals(0, EditorScroll.linesAbove(100f, 0f))
        assertEquals(0, EditorScroll.linesBelow(0f, viewport, 0f, 10))
        assertFalse(EditorScroll.overflows(100, 0f, viewport))
    }

    @Test
    fun `超出一屏才算溢出_正好一屏不算`() {
        assertFalse(EditorScroll.overflows(5, line, viewport))
        assertTrue(EditorScroll.overflows(6, line, viewport))
    }

    @Test
    fun `行号槽的位数_按最大行号算`() {
        assertEquals(1, EditorScroll.gutterDigits(0))
        assertEquals(1, EditorScroll.gutterDigits(9))
        assertEquals(2, EditorScroll.gutterDigits(10))
        assertEquals(3, EditorScroll.gutterDigits(100))
    }

    @Test
    fun `缩放提交后_手指底下那个内容点还在原处`() {
        // 判据不是"算出来等于某个数"，而是**不变量**：锚点那个内容点在缩放前后落在同一个
        // 视口位置（差一位取整）。这一条错了的表现是"松手瞬间画面跳一下"，极难归因。
        val oldScroll = 500
        val anchor = 100f
        val fixed = 12f
        val ratio = 1.5f
        val after = EditorScroll.anchoredScroll(oldScroll, anchor, fixed, ratio)
        val scaled = fixed + (oldScroll + anchor - fixed) * ratio
        assertEquals(anchor, scaled - after, 1f)
    }

    @Test
    fun `倍率没变时滚动值原地不动`() {
        assertEquals(500, EditorScroll.anchoredScroll(500, 100f, 12f, 1f))
    }

    @Test
    fun `缩放的倍率不作用在不动点上`() {
        // 锚点正好压在不动点上（内容坐标 = 正文上留白 100px）：那个点不随字号走，
        // 于是滚动值也一分不动。倍率换成什么都不该影响它。
        assertEquals(40, EditorScroll.anchoredScroll(40, 60f, 100f, 2.5f))
        assertEquals(40, EditorScroll.anchoredScroll(40, 60f, 100f, 0.5f))
    }

    @Test
    fun `顶着顶部缩小时夹到0`() {
        // 滚到顶（0）、手指在视口 50px 处、倍率减半：公式给的是负数，滚动值只能到 0。
        assertEquals(0, EditorScroll.anchoredScroll(0, 50f, 0f, 0.5f))
    }

    @Test
    fun `往下拖去最顶_数上方的行`() {
        val a = EditorScroll.affordance(40f, viewport, line, 10, towardTop = true)
        assertEquals(EditorJump.TOP, a.jump)
        assertEquals(2, a.offscreenLines)
    }

    @Test
    fun `往上拖去最底_数下方的行`() {
        val a = EditorScroll.affordance(40f, viewport, line, 10, towardTop = false)
        assertEquals(EditorJump.BOTTOM, a.jump)
        assertEquals(3, a.offscreenLines)
    }

    @Test
    fun `默认（还没拖过）是去最底`() {
        // 初始 towardTop = false 这一档就是"默认去最底"：位置在顶上也照样给 BOTTOM。
        assertEquals(EditorJump.BOTTOM, EditorScroll.affordance(0f, viewport, line, 10, towardTop = false).jump)
    }
}
