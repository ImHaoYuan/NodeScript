package com.autoscript.ui.state

import com.autoscript.domain.host.ProjectHistorySnapshot
import com.autoscript.domain.host.RunRow
import java.time.ZoneId

/** 项目终态历史的纯呈现态：未读取、失败、成功空列表互不冒充。 */
data class ProjectHistoryState(
    val projectId: String,
    val load: LoadState,
    val runs: List<ProjectHistoryRowState>,
) {
    companion object {
        fun notLoaded(projectId: String) = ProjectHistoryState(projectId, LoadState.NotLoaded, emptyList())

        fun of(snapshot: ProjectHistorySnapshot, zone: ZoneId = ZoneId.systemDefault()) = ProjectHistoryState(
            projectId = snapshot.projectId,
            load = LoadState.Loaded,
            runs = snapshot.runs.map { ProjectHistoryRowState.of(it, zone) },
        )

        fun failed(projectId: String, t: Throwable) = ProjectHistoryState(projectId, LoadState.of(t), emptyList())
    }
}

/** 历史一行保留退出码和完整诊断原文；未知时间不编造。 */
data class ProjectHistoryRowState(
    val engineRunId: Long,
    val intentRunId: Long?,
    val scriptPath: String,
    val stateLabel: String,
    val startedText: String?,
    val finishedText: String?,
    val exitCode: Int?,
    val crashSummary: String?,
) {
    companion object {
        fun of(run: RunRow, zone: ZoneId) = ProjectHistoryRowState(
            engineRunId = run.engineRunId,
            intentRunId = run.intentRunId,
            scriptPath = run.scriptPath,
            stateLabel = RunStateText.describe(run.state),
            startedText = run.startedAtMillis?.let { ScheduleText.absolute(it, zone) },
            finishedText = run.finishedAtMillis?.let { ScheduleText.absolute(it, zone) },
            exitCode = run.exitCode,
            crashSummary = run.crashSummary,
        )
    }
}
