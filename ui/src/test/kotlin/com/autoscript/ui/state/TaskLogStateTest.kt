package com.autoscript.ui.state

import com.autoscript.domain.host.RunRow
import com.autoscript.domain.host.TaskLogSnapshot
import com.autoscript.domain.scripts.RunState
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.time.ZoneId

class TaskLogStateTest {
    private val zone = ZoneId.of("UTC")

    @Test
    fun `首帧与成功空历史分开`() {
        assertFalse(TaskLogState.NOT_LOADED.load.isLoaded)
        assertNull(TaskLogState.NOT_LOADED.load.failedReason())
        val empty = TaskLogState.of(TaskLogSnapshot(emptyList()), zone)
        assertTrue(empty.load.isLoaded)
        assertTrue(empty.runs.isEmpty())
    }

    @Test
    fun `失败保留原文且保留已读到的行不伪装成功空列表`() {
        val failed = TaskLogState.failed(IllegalStateException("壳未装配"))
        assertFalse(failed.load.isLoaded)
        assertEquals("壳未装配", failed.load.failedReason())
        assertEquals("RuntimeException", TaskLogState.failed(RuntimeException()).load.failedReason())

        val rows = listOf(RunRow(1, 11, "demo", "main.js", RunState.SUCCEEDED, 0, 1000, 0))
        val loaded = TaskLogState.of(TaskLogSnapshot(rows), zone)
        val after = TaskLogState.failed(IllegalStateException("档案读失败"), loaded)
        assertEquals("档案读失败", after.load.failedReason())
        assertEquals(loaded.runs, after.runs)
    }

    @Test
    fun `终态退出码与多行摘要原样保留且带项目`() {
        val rows = listOf(
            RunRow(1, 11, "demo", "main.js", RunState.SUCCEEDED, 0, 1000, 0),
            RunRow(2, null, "other", "fail.js", RunState.FAILED, null, null, 3, "失败原因"),
            RunRow(3, 13, "demo", "bad.js", RunState.CRASHED, 1000, 2000, 7, "错误原文\n第二行"),
            RunRow(4, null, "other", "stop.js", RunState.CANCELLED, null, null),
        )
        val state = TaskLogState.of(TaskLogSnapshot(rows), zone)
        assertEquals(listOf(1L, 2L, 3L, 4L), state.runs.map { it.engineRunId })
        assertEquals(listOf("demo", "other", "demo", "other"), state.runs.map { it.projectId })
        assertEquals(listOf("成功", "失败", "崩溃（被杀/OOM/看门狗）", "已取消"), state.runs.map { it.stateLabel })
        for ((row, source) in state.runs.zip(rows)) {
            assertEquals(source.scriptPath, row.scriptPath)
            assertEquals(source.intentRunId, row.intentRunId)
            assertEquals(source.exitCode, row.exitCode)
            assertEquals(source.crashSummary, row.crashSummary)
        }
        assertEquals(ScheduleText.absolute(0, zone), state.runs.first().startedText)
        assertEquals(ScheduleText.absolute(1000, zone), state.runs.first().finishedText)
        assertNull(state.runs[1].startedText)
        assertNull(state.runs[1].finishedText)
        assertNull(state.runs.last().exitCode)
    }

    @Test
    fun `复制摘要含项目退出码关联与崩溃原文且未知如实写未知`() {
        val bad = TaskLogRowState.of(
            RunRow(3, 13, "demo", "bad.js", RunState.CRASHED, 1000, 2000, 7, "错误原文\n第二行"),
            zone,
        )
        val text = bad.summaryText()
        assertTrue(text.startsWith("demo / bad.js\n#3 · "), text)
        assertTrue("退出码：7" in text && "投递记录：#13" in text && text.endsWith("错误原文\n第二行"), text)

        val bare = TaskLogRowState.of(RunRow(4, null, "p", "x.js", RunState.CANCELLED, null, null), zone).summaryText()
        assertTrue("开始：未记录" in bare && "结束：未记录" in bare && "退出码：未知" in bare, bare)
        assertFalse("投递记录" in bare, bare)
    }
}
