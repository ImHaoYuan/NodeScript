package com.autoscript.ui.state

import com.autoscript.domain.host.ProjectHistorySnapshot
import com.autoscript.domain.host.RunRow
import com.autoscript.domain.scripts.RunState
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.time.ZoneId

class ProjectHistoryStateTest {
    private val zone = ZoneId.of("UTC")

    @Test
    fun `首帧与成功空历史分开`() {
        val initial = ProjectHistoryState.notLoaded("demo")
        assertFalse(initial.load.isLoaded)
        assertNull(initial.load.failedReason())
        val empty = ProjectHistoryState.of(ProjectHistorySnapshot("demo", emptyList()), zone)
        assertTrue(empty.load.isLoaded)
        assertTrue(empty.runs.isEmpty())
        assertEquals("demo", empty.projectId)
    }

    @Test
    fun `失败保留原文不伪装成功空列表`() {
        val failed = ProjectHistoryState.failed("demo", IllegalStateException("壳未装配"))
        assertFalse(failed.load.isLoaded)
        assertEquals("壳未装配", failed.load.failedReason())
        assertEquals("demo", failed.projectId)
        assertEquals("RuntimeException", ProjectHistoryState.failed("other", RuntimeException()).load.failedReason())
    }

    @Test
    fun `终态退出码与多行摘要原样保留`() {
        val rows = listOf(
            RunRow(1, 11, "demo", "main.js", RunState.SUCCEEDED, 0, 1000, 0),
            RunRow(2, null, "demo", "fail.js", RunState.FAILED, null, null, 3, "失败原因"),
            RunRow(3, 13, "demo", "bad.js", RunState.CRASHED, 1000, 2000, 7, "错误原文\n第二行"),
            RunRow(4, null, "demo", "stop.js", RunState.CANCELLED, null, null),
        )
        val state = ProjectHistoryState.of(ProjectHistorySnapshot("demo", rows), zone)
        assertEquals(listOf(1L, 2L, 3L, 4L), state.runs.map { it.engineRunId })
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
}
