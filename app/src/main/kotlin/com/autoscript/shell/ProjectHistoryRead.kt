package com.autoscript.shell

import com.autoscript.domain.host.ProjectHistorySnapshot
import com.autoscript.domain.scripts.EngineRunLink
import com.autoscript.domain.scripts.RunRecord
import com.autoscript.domain.scripts.isTerminal

/** 项目历史的装配侧投影：复用档案，不把未结算记录或别的项目混进来。 */
object ProjectHistoryRead {
    suspend fun snapshot(
        projectId: String,
        records: List<RunRecord>,
        linkOf: suspend (Long) -> EngineRunLink?,
    ): ProjectHistorySnapshot = ProjectHistorySnapshot(
        projectId = projectId,
        runs = records.filter { it.projectId == projectId && it.state.isTerminal }
            .sortedWith(
                compareByDescending<RunRecord> { it.finishedAtMillis ?: it.startedAtMillis ?: Long.MIN_VALUE }
                    .thenByDescending { it.id },
            )
            .map { TaskCenterRead.runRow(it, linkOf(it.id)) },
    )
}
