package com.autoscript.platform.system.shell

import com.autoscript.domain.json.DomainJson
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * 捕获上限的**算账**与**告警缝**（§9.6；口径 = `docs/design-decisions.md` 第 21 项）。
 *
 * 上限不是一个"看着合适"的整数，它有两个必须成立的约束，改任何一个数都要重算：
 *
 * 1. **对合法用途够不着**：正常 shell 输出是 KB 级，1 MiB 只会在误用（`cat` 大文件）时触发；
 * 2. **最坏情况仍不触单帧上限**：[ShellCaptureLimit.MAX_CAPTURE_BYTES] ×
 *    [ShellCaptureLimit.JSON_WORST_CASE_EXPANSION] 必须 < [ShellCaptureLimit.FRAME_BUDGET_BYTES]。
 *    这条是硬约束 —— 单帧超限在桥上是 `FrameTooLargeException` → **关连接**
 *    （`NewlineFrameServer` 读循环 `break`），比截断重得多：一次 `cat` 就能把脚本与宿主的
 *    连接打断。所以实现侧必须在编码之前就兜住，不能让超限载荷走到 JSON 那一步。
 */
class ShellCaptureLimitTest {

    @Test
    fun `最坏膨胀后仍在单帧预算之内（改了任一个数就得重算这笔账）`() {
        val worst = ShellCaptureLimit.MAX_CAPTURE_BYTES.toLong() * ShellCaptureLimit.JSON_WORST_CASE_EXPANSION
        assertTrue(
            worst < ShellCaptureLimit.FRAME_BUDGET_BYTES,
            "1MiB×6=$worst 必须 < 单帧上限 ${ShellCaptureLimit.FRAME_BUDGET_BYTES}",
        )
    }

    @Test
    fun `膨胀系数不是估的：控制字符经 DomainJson 编码确实是 6 倍`() {
        // 上限那句话的"6 倍"若只是保守估计，上面那条断言就守不住真正的上界。
        // 拿 DomainJson 真编一次：N 个 `\u0001` 编出来应当正好 6N + 2（两端引号）。
        val n = 1_000
        val encoded = DomainJson.encode("\u0001".repeat(n)).length
        assertEquals(n * ShellCaptureLimit.JSON_WORST_CASE_EXPANSION + 2, encoded, "控制字符转义不是 6 倍，上限的算法要重推")
    }

    @Test
    fun `截断标记与上限同源，不手抄数字`() {
        assertTrue(
            ShellCaptureLimit.TRUNCATION_MARK.contains(ShellCaptureLimit.MAX_CAPTURE_LABEL),
            "标记里的上限文案应当来自 MAX_CAPTURE_LABEL：${ShellCaptureLimit.TRUNCATION_MARK}",
        )
        assertTrue(ShellCaptureLimit.MAX_CAPTURE_LABEL.contains("1048576"), "标签里要有精确字节数")
    }

    @Test
    fun `ShellResult 缺省不截断：旧调用方的形状不变`() {
        // truncated 是纯增量字段 —— 不传就是 false，不让"没实现截断的实现"凭空报截断。
        assertFalse(ShellResult(0, "out", null).truncated)
        assertTrue(ShellResult(0, "out", null, truncated = true).truncated)
        // 截断与退出码正交：截断了也照样能是成功。
        assertTrue(ShellResult(0, null, null, truncated = true).isSuccess)
    }

    @Test
    fun `缺省日志缝不发散：JUL 只是转发，不抛也不吞`() {
        // 这里只验"缝是函数类型、可注入"这件事本身；真机上它由 JUL 接进 logcat。
        val seen = ArrayList<String>()
        ShellCaptureLimit.LogSink { seen += it }.warn("hello")
        assertEquals(listOf("hello"), seen)
        // 缺省实例存在且可调用（Android 上 JUL → logcat；JVM 单测里不会因 "not mocked" 炸）。
        ShellCaptureLimit.defaultLogSink.warn("smoke")
    }
}
