package com.autoscript.shell

import com.autoscript.domain.host.TaskLogSnapshot
import com.autoscript.domain.scripts.EngineRunLink
import com.autoscript.domain.scripts.RunRecord
import com.autoscript.domain.scripts.isTerminal

/** 任务日志的装配侧投影：复用档案，只取终态（未结算记录归任务中心，不混进来）。 */
object TaskLogRead {
    suspend fun snapshot(
        records: List<RunRecord>,
        linkOf: suspend (Long) -> EngineRunLink?,
    ): TaskLogSnapshot = TaskLogSnapshot(
        runs = records.filter { it.state.isTerminal }
            .sortedWith(
                compareByDescending<RunRecord> { it.finishedAtMillis ?: it.startedAtMillis ?: Long.MIN_VALUE }
                    .thenByDescending { it.id },
            )
            .map { TaskCenterRead.runRow(it, linkOf(it.id)) },
    )
}
