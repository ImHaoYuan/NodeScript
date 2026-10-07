package com.autoscript.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import com.autoscript.ui.components.ActionBar
import com.autoscript.ui.components.ActionBarAction
import com.autoscript.ui.components.EmptyHint
import com.autoscript.ui.components.PillButton
import com.autoscript.ui.components.Separator
import com.autoscript.ui.components.TabBarBottomClearance
import com.autoscript.ui.components.ToneText
import com.autoscript.ui.components.pressable
import com.autoscript.ui.components.rememberCopyAction
import com.autoscript.ui.components.rememberRefreshAction
import com.autoscript.ui.state.ConsoleLineState
import com.autoscript.ui.state.ConsoleState
import com.autoscript.ui.state.LoadState
import com.autoscript.ui.state.Status
import com.autoscript.ui.state.StatusTone
import com.autoscript.ui.state.TaskLogRowState
import com.autoscript.ui.state.TaskLogState
import com.autoscript.ui.theme.ThemeColors

/**
 * 日志管理页（管理面板 → 日志管理）：两个列表，分段钮切换。
 *
 * - **系统日志**：控制台里**不属于任何执行**的行（`runId == 0`，即 [ConsoleLineState.external]）。
 * - **任务日志**：全部项目的终态执行历史（原先项目页里的「历史」子页，2026-10-07 用户口径搬到这里，
 *   并从「某个项目」改成「全部项目」—— 每行带 `项目 / 脚本`）。
 *
 * **诚实边界 —— 系统日志今天的真实内容**（核过代码，不照设计愿望写）：
 * - 桥侧 `console.log` 的写入口把 `runId` 恒写成 0（`ConsoleCollector.handle`；请求帧里没有执行归属），
 *   所以**脚本自己的 console 输出今天也落在这个列表里**，而不是归到某次执行名下；
 * - 宿主装配/调度/闹钟那些事件今天走的是 `android.util.Log`，**没有写进收集器**，所以这里**看不到**它们。
 * 两件事都在列表顶部如实说明，不把「系统日志」说成「纯宿主事件」。给脚本输出打 runId 要动桥协议帧，
 * 另批处理。
 *
 * 读取与刷新由外壳驱动（进入本页/回前台/手动刷新）；本屏只画，状态原样来自 [ConsoleState] 与 [TaskLogState]
 * （控制台游标与累积行与控制台页同一份，读失败保留已读到的行）。
 */
@Composable
fun LogManagementScreen(
    consoleState: ConsoleState,
    taskLogState: TaskLogState,
    onRefreshConsole: suspend () -> Unit,
    onRefreshTaskLog: suspend () -> Unit,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
) {
    var selectedTab by rememberSaveable { mutableIntStateOf(TAB_SYSTEM) }
    val systemLines = consoleState.lines.filter { it.external }
    val status = if (selectedTab == TAB_SYSTEM) {
        Status.of(
            load = consoleState.load,
            notLoadedText = "尚未读取（点右上「刷新」现取）",
            loadedText = "共 ${systemLines.size} 行",
        )
    } else {
        Status.count(
            load = taskLogState.load,
            notLoadedText = "尚未读取（点右上「刷新」现取）",
            total = taskLogState.runs.size,
            emptyText = "暂无已归档的终态执行",
            unit = "次终态执行",
        )
    }
    val refresh = rememberRefreshAction {
        if (selectedTab == TAB_SYSTEM) onRefreshConsole() else onRefreshTaskLog()
    }
    Column(modifier.fillMaxSize().background(ThemeColors.background)) {
        ActionBar(
            title = "日志管理",
            onBack = onBack,
            subtitle = status.text,
            subtitleTone = status.tone,
            actions = { ActionBarAction("刷新", refresh::trigger) },
        )
        Row(
            Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            PillButton("系统日志", selected = selectedTab == TAB_SYSTEM, onClick = { selectedTab = TAB_SYSTEM })
            PillButton("任务日志", selected = selectedTab == TAB_TASK, onClick = { selectedTab = TAB_TASK })
        }
        if (selectedTab == TAB_SYSTEM) {
            SystemLogList(consoleState, systemLines, Modifier.weight(1f))
        } else {
            TaskLogList(taskLogState, Modifier.weight(1f))
        }
    }
}

private const val TAB_SYSTEM = 0
private const val TAB_TASK = 1

@Composable
private fun SystemLogList(state: ConsoleState, lines: List<ConsoleLineState>, modifier: Modifier = Modifier) {
    val copy = rememberCopyAction()
    LazyColumn(modifier.fillMaxWidth(), contentPadding = PaddingValues(bottom = TabBarBottomClearance())) {
        item {
            ToneText(
                text = "不属于任何执行的输出（runId 0）。注意：脚本经桥的 console 输出今天也记在这里（尚未按执行归属）；" +
                    "宿主装配/调度/闹钟事件尚未写入本列表。",
                tone = StatusTone.MUTED,
                style = MaterialTheme.typography.bodySmall,
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp),
            )
        }
        if (state.droppedTotal > 0L) {
            item {
                // 丢包不藏：有界队列是数据面既定语义，但「显示的不是全部」必须说出来。
                ToneText(
                    text = "已丢弃 ${state.droppedTotal} 行（队列有界，最早输出被覆盖）：日志有缺口",
                    tone = StatusTone.ATTENTION,
                    style = MaterialTheme.typography.bodySmall,
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp),
                )
            }
        }
        if (state.pageFull && state.load.isLoaded) {
            item {
                ToneText(
                    text = "本批已拉满，可能还有更新的行 —— 点「刷新」继续拉取",
                    tone = StatusTone.MUTED,
                    style = MaterialTheme.typography.bodySmall,
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp),
                )
            }
        }
        (state.load as? LoadState.Failed)?.let { failed ->
            item {
                // 读失败带原文，已读到的行仍在下面（一次瞬时失败不抹掉已看到的日志）。
                ToneText(
                    text = "读取失败：${failed.reason}",
                    tone = StatusTone.PROBLEM,
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
                )
            }
        }
        if (state.load.isLoaded && lines.isEmpty()) {
            item { EmptyHint("读到了，暂无系统日志") }
        }
        items(lines, key = { it.seq }) { line ->
            ToneText(
                text = line.systemLogText(),
                tone = line.tone,
                style = MaterialTheme.typography.bodySmall,
                modifier = Modifier
                    .animateItem()
                    .fillMaxWidth()
                    .pressable(role = Role.Button, onClick = { copy.copy("已复制该行", line.systemLogText()) })
                    .padding(horizontal = 16.dp, vertical = 1.dp),
            )
        }
    }
}

@Composable
private fun TaskLogList(state: TaskLogState, modifier: Modifier = Modifier) {
    val copy = rememberCopyAction()
    LazyColumn(modifier.fillMaxWidth(), contentPadding = PaddingValues(bottom = TabBarBottomClearance())) {
        item {
            ToneText(
                text = "仅显示已归档的终态；启动失败无引擎记录，不在此列表内。点一行复制摘要。",
                tone = StatusTone.MUTED,
                style = MaterialTheme.typography.bodySmall,
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp),
            )
        }
        when (val load = state.load) {
            LoadState.NotLoaded -> item { EmptyHint("尚未读取任务日志") }
            is LoadState.Failed -> item {
                ToneText(
                    text = "读取任务日志失败：${load.reason}",
                    tone = StatusTone.PROBLEM,
                    modifier = Modifier.padding(16.dp),
                )
            }
            LoadState.Loaded -> if (state.runs.isEmpty()) item { EmptyHint("暂无已归档的终态执行") }
        }
        items(state.runs, key = { it.engineRunId }) { run ->
            TaskLogRow(run, onCopy = { copy.copy("已复制执行摘要", run.summaryText()) })
            Separator()
        }
    }
}

@Composable
private fun TaskLogRow(run: TaskLogRowState, onCopy: () -> Unit) {
    Column(
        Modifier
            .fillMaxWidth()
            .pressable(role = Role.Button, onClick = onCopy)
            .padding(horizontal = 16.dp, vertical = 12.dp),
    ) {
        Text(
            "${run.projectId} / ${run.scriptPath}",
            style = MaterialTheme.typography.titleMedium,
            color = ThemeColors.text,
        )
        Text("#${run.engineRunId} · ${run.stateLabel}", color = ThemeColors.text)
        ToneText("开始：${run.startedText ?: "未记录"}", StatusTone.MUTED)
        ToneText("结束：${run.finishedText ?: "未记录"}", StatusTone.MUTED)
        ToneText("退出码：${run.exitCode?.toString() ?: "未知"}", StatusTone.MUTED)
        run.intentRunId?.let { ToneText("投递记录：#$it", StatusTone.MUTED) }
        run.crashSummary?.let { ToneText(it, StatusTone.PROBLEM, modifier = Modifier.padding(top = 8.dp)) }
    }
}

/** 系统日志一行（也是复制出去的那串字）：本列表全是 runId 0，故不再每行写归属。 */
private fun ConsoleLineState.systemLogText(): String = "$timeText  $levelLabel  $text"
