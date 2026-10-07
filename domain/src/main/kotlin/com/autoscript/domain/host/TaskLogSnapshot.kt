package com.autoscript.domain.host

/**
 * 任务日志：**全部项目**的终态执行历史（§8.5；日志管理页的「任务日志」列表）。
 * 与 [TaskCenterSnapshot.runs] 的未结算记录分开。
 *
 * [runs] 只含已归档的终态，最近结算的在前；缺时间、退出码或关联就保留 null。
 * 启动失败没有引擎记录，不在此快照内；空历史不能被解释成“每次投递都成功”。
 */
data class TaskLogSnapshot(
    val runs: List<RunRow>,
)
