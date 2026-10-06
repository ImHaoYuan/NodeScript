package com.autoscript.ui.screens

import com.autoscript.ui.state.ProjectHistoryState

/** 外壳下发的历史数据与读操作；不把宿主接口传进项目屏。 */
data class ProjectHistoryUi(
    val state: ProjectHistoryState,
    val onRead: suspend (String) -> Unit,
    val active: Boolean,
    val resumeTick: Int,
)

