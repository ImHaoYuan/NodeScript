package com.autoscript.ui.screens

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.autoscript.ui.components.ActionBar
import com.autoscript.ui.components.ActionBarAction
import com.autoscript.ui.components.EmptyHint
import com.autoscript.ui.components.Separator
import com.autoscript.ui.components.TabBarBottomClearance
import com.autoscript.ui.components.ToneText
import com.autoscript.ui.components.rememberRefreshAction
import com.autoscript.ui.state.LoadState
import com.autoscript.ui.state.ProjectHistoryState
import com.autoscript.ui.state.StatusTone
import com.autoscript.ui.theme.ThemeColors

/** 项目终态历史子页；诊断文本可选中复制，不把 stderr 冒充控制台流。 */
@Suppress("FunctionNaming") // Compose 屏幕沿用全模块的 PascalCase 命名。
@Composable
fun ProjectHistoryScreen(
    state: ProjectHistoryState,
    active: Boolean,
    onRefresh: suspend () -> Unit,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
) {
    BackHandler(enabled = active, onBack = onBack)
    val refresh = rememberRefreshAction(onRefresh)
    Column(modifier.fillMaxSize().background(ThemeColors.background)) {
        ActionBar(
            title = "执行历史",
            subtitle = state.projectId,
            onBack = onBack,
            actions = { ActionBarAction("刷新", refresh::trigger) },
        )
        LazyColumn(contentPadding = PaddingValues(bottom = TabBarBottomClearance())) {
            item {
                ToneText(
                    text = "仅显示已归档的终态；启动失败无引擎记录，不在此列表内。",
                    tone = StatusTone.MUTED,
                    style = MaterialTheme.typography.bodySmall,
                    modifier = Modifier.padding(16.dp),
                )
            }
            when (val load = state.load) {
                LoadState.NotLoaded -> item { EmptyHint("尚未读取执行历史") }
                is LoadState.Failed -> item {
                    ToneText(
                        text = "读取执行历史失败：${load.reason}",
                        tone = StatusTone.PROBLEM,
                        modifier = Modifier.padding(16.dp),
                    )
                }
                LoadState.Loaded -> if (state.runs.isEmpty()) {
                    item { EmptyHint("此项目暂无已归档的终态执行") }
                }
            }
            items(state.runs, key = { it.engineRunId }) { run ->
                SelectionContainer {
                    Column(Modifier.padding(horizontal = 16.dp, vertical = 12.dp)) {
                        Text(run.scriptPath, style = MaterialTheme.typography.titleMedium, color = ThemeColors.text)
                        Text("#${run.engineRunId} · ${run.stateLabel}", color = ThemeColors.text)
                        ToneText("开始：${run.startedText ?: "未记录"}", StatusTone.MUTED)
                        ToneText("结束：${run.finishedText ?: "未记录"}", StatusTone.MUTED)
                        ToneText("退出码：${run.exitCode?.toString() ?: "未知"}", StatusTone.MUTED)
                        run.intentRunId?.let { ToneText("投递记录：#$it", StatusTone.MUTED) }
                        run.crashSummary?.let {
                            ToneText(it, StatusTone.PROBLEM, modifier = Modifier.padding(top = 8.dp))
                        }
                    }
                }
                Separator()
            }
        }
    }
}
