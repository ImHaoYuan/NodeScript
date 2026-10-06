package com.autoscript.ui.state

import kotlin.math.ceil
import kotlin.math.floor

/**
 * 编辑器的**行数几何**：行号槽要几行、右下那颗钮这一下去哪头、它上面那个气泡该显示几。
 *
 * 为什么单独一个文件、而且全是纯函数：这三件事都是"由滚动位置与行高算出来的数"，与
 * Compose 无关 —— 抽出来就能单测（`EditorScrollTest`），也免得把 `ceil`/`floor` 的边界
 * 写错还看不出来（差一行的错在界面上只表现为"气泡的数字少了一个"，肉眼极难发现）。
 *
 * **长度单位一律 px**（调用方把 dp 换成 px 再传进来）：这里只做除法与取整；混着 dp
 * 会让"行高 20sp 在 3.0 密度下是 60px"这种换算散到四处去。
 */
object EditorScroll {

    /**
     * 文本占几行。**末行没有换行符也算一行**（`"a"` = 1、`""` = 1、`"a\n"` = 2）：
     * 空文件在编辑器里也是一个能落光标的位置，行号槽要给它留一个 `1`。
     */
    fun lineCount(text: String): Int = text.count { it == '\n' } + 1

    /**
     * 屏幕外**上方**还有多少行。
     *
     * 判据是"**整行**都在视口上沿之外"：第 i 行占 `[i*L, (i+1)*L)`，只有它的下沿
     * `<= S` 时才算完全看不见 —— 还露着半行的不算，否则气泡上的数字会比人眼数出来的多。
     */
    fun linesAbove(scrollPx: Float, lineHeightPx: Float): Int {
        if (lineHeightPx <= 0f) return 0
        return floor(scrollPx / lineHeightPx).toInt().coerceAtLeast(0)
    }

    /** 屏幕外**下方**还有多少行（判据同上：整行都在视口下沿之外才算）。 */
    fun linesBelow(scrollPx: Float, viewportPx: Float, lineHeightPx: Float, lines: Int): Int {
        if (lineHeightPx <= 0f) return 0
        val firstHidden = ceil((scrollPx + viewportPx) / lineHeightPx).toInt()
        return (lines - firstHidden).coerceAtLeast(0)
    }

    /** 正文有没有超出一屏（气泡只在这时候出现 —— 没超出时"屏幕外还有几行"是个假问题）。 */
    fun overflows(lines: Int, lineHeightPx: Float, viewportPx: Float): Boolean =
        lineHeightPx > 0f && lines * lineHeightPx > viewportPx

    /** 行号槽要留几位数（三位数的文件不该按一位数留宽）。 */
    fun gutterDigits(lines: Int): Int = lines.coerceAtLeast(1).toString().length

    /**
     * 行号槽那一列字（每个**视觉行**一条，``\n`` 连接）。
     *
     * 折行打开时，一个逻辑行会占好几个视觉行 —— 行号只写在它的**第一个**视觉行上，
     * 其余行留空：这样"第几行"数的是逻辑行（用户数得出来的那个数），而不是折出来的段。
     * 反过来说，行号槽的**行数**必须等于正文的视觉行数，否则下面的数字会整体错位。
     *
     * @param visualRows 折行之后的视觉行总数（``TextLayoutResult.lineCount``）。
     * @param firstRowOfLine 第 L 个逻辑行的首个视觉行序号（``getLineForOffset`` 读出来的）。
     */
    fun gutterLabels(visualRows: Int, firstRowOfLine: List<Int>): String {
        if (visualRows <= 0) return ""
        val labels = Array(visualRows) { "" }
        firstRowOfLine.forEachIndexed { lineIndex, row ->
            // 越界的丢掉而不是崩：排版结果与文本是两次读取，理论上可能差一帧。
            if (row in 0 until visualRows) labels[row] = (lineIndex + 1).toString()
        }
        return labels.joinToString("\n")
    }

    /**
     * 这一下按钮该画成"去最顶"还是"去最底"，以及气泡上那个数字。
     *
     * **方向来自手指，不是来自位置**（用户口径）：往下拖 = 内容往回走 = 你在往上看，
     * 于是按钮变"去最顶"、气泡数**上方**还剩几行；往上拖反之。默认（还没拖过）是"去最底"。
     */
    fun affordance(
        scrollPx: Float,
        viewportPx: Float,
        lineHeightPx: Float,
        lines: Int,
        towardTop: Boolean,
    ): EditorAffordance = EditorAffordance(
        jump = if (towardTop) EditorJump.TOP else EditorJump.BOTTOM,
        offscreenLines = if (towardTop) {
            linesAbove(scrollPx, lineHeightPx)
        } else {
            linesBelow(scrollPx, viewportPx, lineHeightPx, lines)
        },
    )
}

/** 右下那颗钮这一下去哪一头。 */
enum class EditorJump { TOP, BOTTOM }

/**
 * 右下那颗钮当下该显示的样子。
 *
 * @property jump 点它去哪儿（[EditorJump.TOP] / [EditorJump.BOTTOM]）。
 * @property offscreenLines 气泡上那个数：**你要去的那一头**在屏幕外还有几行。
 */
data class EditorAffordance(val jump: EditorJump, val offscreenLines: Int)
