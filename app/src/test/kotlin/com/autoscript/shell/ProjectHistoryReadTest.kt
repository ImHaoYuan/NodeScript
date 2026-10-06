package com.autoscript.shell

import com.autoscript.domain.scripts.EngineRunLink
import com.autoscript.domain.scripts.RunRecord
import com.autoscript.domain.scripts.RunState
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertSame
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class ProjectHistoryReadTest {
    private fun record(id: Long, state: RunState, finished: Long? = 200L) = RunRecord(
        id = id,
        projectId = "demo",
        scriptPath = "main.js",
        runNonce = "n$id",
        state = state,
        startedAtMillis = 100L,
        finishedAtMillis = finished,
    )

    @Test
    fun `终态逐字段投影并按结算时间倒序`() = runBlocking {
        val records = listOf(
            record(1, RunState.SUCCEEDED, 210).copy(exitCode = 0),
            record(2, RunState.FAILED, 220).copy(exitCode = 3, crashSummary = "执行失败"),
            record(3, RunState.CRASHED, 230).copy(exitCode = 7, crashSummary = "错误原文\n第二行"),
            record(4, RunState.CANCELLED, 240),
        )
        val snapshot = ProjectHistoryRead.snapshot("demo", records) { EngineRunLink(it + 10, it) }
        assertEquals("demo", snapshot.projectId)
        assertEquals(listOf(4L, 3L, 2L, 1L), snapshot.runs.map { it.engineRunId })
        for (row in snapshot.runs) {
            val source = records.single { it.id == row.engineRunId }
            assertEquals(source.id + 10, row.intentRunId)
            assertEquals(source.projectId, row.projectId)
            assertEquals(source.scriptPath, row.scriptPath)
            assertEquals(source.state, row.state)
            assertEquals(source.startedAtMillis, row.startedAtMillis)
            assertEquals(source.finishedAtMillis, row.finishedAtMillis)
            assertEquals(source.exitCode, row.exitCode)
            assertEquals(source.crashSummary, row.crashSummary)
        }
        Unit
    }

    @Test
    fun `排除未结算与其他项目且不查询它们的关联`() = runBlocking {
        val reads = mutableListOf<Long>()
        val snapshot = ProjectHistoryRead.snapshot(
            "demo",
            listOf(
                record(1, RunState.RUNNING, null),
                record(2, RunState.PENDING, null),
                record(3, RunState.SUCCEEDED).copy(projectId = "other"),
                record(4, RunState.SUCCEEDED),
            ),
        ) { reads += it; null }
        assertEquals(listOf(4L), reads)
        assertEquals(1, snapshot.runs.size)
        assertNull(snapshot.runs.single().intentRunId)
        Unit
    }

    @Test
    fun `空历史不读关联`() = runBlocking {
        val snapshot = ProjectHistoryRead.snapshot("demo", emptyList()) { error("空历史不应读关联") }
        assertTrue(snapshot.runs.isEmpty())
        Unit
    }

    @Test
    fun `缺时刻不猜且同刻按 id 稳定倒序`() = runBlocking {
        val snapshot = ProjectHistoryRead.snapshot(
            "demo",
            listOf(
                record(1, RunState.SUCCEEDED, null).copy(startedAtMillis = null),
                record(2, RunState.SUCCEEDED),
                record(3, RunState.SUCCEEDED),
            ),
        ) { null }
        assertEquals(listOf(3L, 2L, 1L), snapshot.runs.map { it.engineRunId })
        assertNull(snapshot.runs.last().startedAtMillis)
        assertNull(snapshot.runs.last().finishedAtMillis)
        Unit
    }

    @Test
    fun `关联读取失败原样抛出不冒充空历史`() {
        val failure = IllegalStateException("档案读失败")
        val actual = assertThrows(IllegalStateException::class.java) {
            runBlocking { ProjectHistoryRead.snapshot("demo", listOf(record(1, RunState.SUCCEEDED))) { throw failure } }
        }
        assertSame(failure, actual)
    }
}
