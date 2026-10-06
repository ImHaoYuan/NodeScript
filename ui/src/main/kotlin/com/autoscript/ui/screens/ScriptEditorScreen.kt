package com.autoscript.ui.screens

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.autoscript.ui.components.ActionBar
import com.autoscript.ui.components.ActionBarAction
import com.autoscript.ui.components.LocalTabBarHidden
import com.autoscript.ui.components.LocalToast
import com.autoscript.ui.components.TabBarBottomClearance
import com.autoscript.ui.components.color
import com.autoscript.ui.state.StatusTone
import com.autoscript.ui.theme.ThemeColors
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch

/**
 * 脚本文本编辑器（项目页**点文件**进来的整屏编辑面；TG 文件页点开文档的对应位）。
 *
 * 版式：顶栏（‹ 返回 / 文件名 / 完整相对路径 / 右侧「保存」）+ 正文（等宽文本框）。
 * 保存按钮**只在有未保存改动时可点** —— 一颗永远可点的保存键会让人以为「点过就一定写进去了」。
 *
 * 诚实边界（与其他屏同一条纪律）：
 * - 读取失败**不冒充空文件**：空文件与「没读到」在编辑框里必须长得不一样 ——
 *   前者可以直接写内容，后者写下去就是把原内容抹掉；
 * - 保存失败**原文留在正文上方**（不只用浮层提示）：浮层几秒后消失，
 *   而「这次没存上」这件事必须一直看得见，直到下一次保存或退出；
 * - 有未保存改动时返回**先问一句**（放弃 / 继续编辑）：静默丢弃用户刚敲的代码是最坏的一种「贴心」。
 *
 * 只负责编辑一个**已存在**的文件：新建/改名/删除都不在这里（新建归项目页 FAB，
 * 其余要宿主侧的口，没有的口不摆成按钮）。
 *
 * @param projectId 所属项目（`files/scripts/` 下第一级目录名）。
 * @param relPath 项目内相对路径（子文件夹里的文件靠它定位）。
 * @param displayName 顶栏标题（文件名，不含目录）。
 * @param onRead 读全文（挂起；失败原文抛）。
 * @param onSave 覆盖写（挂起；**正常返回即已落盘**，失败原文抛）。
 * @param onClose 退出编辑面（返回键与 ‹ 共用一条路径）。
 */
@Composable
fun ScriptEditorScreen(
    projectId: String,
    relPath: String,
    displayName: String,
    onRead: suspend (projectId: String, relPath: String) -> String,
    onSave: suspend (projectId: String, relPath: String, content: String) -> Unit,
    onClose: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val palette = ThemeColors
    val scope = rememberCoroutineScope()
    val toast = LocalToast.current
    // 保存成功后要收的两样东西：焦点（= 光标）与输入法（见下面保存那一段）。
    val focusManager = LocalFocusManager.current
    val keyboard = LocalSoftwareKeyboardController.current
    // **编辑面占整屏，底栏整条收掉**（用户口径）：编辑时那四个页签既点不到正文、又压着最后几行。
    // 开关由外壳供（[LocalTabBarHidden]），只在进出时写它 —— 退出（含配置重建）自动还回去。
    val tabBarHidden = LocalTabBarHidden.current
    DisposableEffect(Unit) {
        tabBarHidden.value = true
        onDispose { tabBarHidden.value = false }
    }
    // null = 还没读到（或正在读）：与「读到了但是空文件」（空串）必须区分开。
    var text by remember { mutableStateOf<String?>(null) }
    var loadError by remember { mutableStateOf<String?>(null) }
    var saveError by remember { mutableStateOf<String?>(null) }
    var dirty by remember { mutableStateOf(false) }
    var saving by remember { mutableStateOf(false) }
    var confirmDiscard by remember { mutableStateOf(false) }

    // 换文件（key 变）重读：编辑器是整屏面，同一个实例被复用到另一个文件上时必须把
    // 上一位的状态清干净，否则会拿着 A 的内容去存 B。
    LaunchedEffect(projectId, relPath) {
        text = null
        loadError = null
        saveError = null
        dirty = false
        val loaded = loadScriptText(projectId, relPath, onRead)
        text = loaded.text
        loadError = loaded.error
    }

    /** 退回上一层（返回键与 ‹ 共用）：有未保存改动先问一句，不静默丢。 */
    fun close() {
        if (dirty) confirmDiscard = true else onClose()
    }

    BackHandler { close() }

    val body = text
    Column(modifier.fillMaxSize().background(palette.background)) {
        ActionBar(
            title = displayName,
            subtitle = relPath,
            subtitleTone = StatusTone.MUTED,
            onBack = ::close,
            backGlyph = "‹",
            actions = {
                ActionBarAction(
                    text = if (saving) "保存中…" else "保存",
                    // 没改动 / 还没读到 / 正在存 → 不可点（见类 KDoc）。
                    enabled = body != null && dirty && !saving,
                    onClick = {
                        val content = body ?: return@ActionBarAction
                        scope.launch {
                            saving = true
                            saveError = null
                            saveError = saveScriptText(projectId, relPath, content, onSave)
                            saving = false
                            // 存失败：那句原文留在正文上方，**不收键盘**（还要接着改）。
                            if (saveError != null) return@launch
                            dirty = false
                            // 存完就**收键盘、撤光标**（用户口径）：写完了还杵着输入法 +
                            // 一根闪烁光标，读起来像"还在编辑、没存上"。清焦点即撤光标
                            // （未聚焦的 BasicTextField 不画光标），键盘随之收起。
                            focusManager.clearFocus()
                            keyboard?.hide()
                            toast?.show("已保存「$displayName」")
                        }
                    },
                )
            },
        )
        // 保存失败的原文一直留在正文上方（浮层会消失，这句不会）。
        saveError?.let { reason ->
            Text(
                text = "保存失败：$reason",
                color = StatusTone.PROBLEM.color(),
                style = MaterialTheme.typography.bodySmall,
                modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
            )
        }
        when {
            loadError != null -> EditorNotice(text = "读不到这个文件：$loadError")
            body == null -> EditorNotice(text = "读取中…")
            else -> Box(
                Modifier
                    .weight(1f)
                    .fillMaxWidth()
                    // 底栏留白：编辑器住页签条**下面**那一层（外壳画的底栏浮在页内容之上），
                    // 不留白就会被胶囊底栏压住最后几行。
                    .padding(bottom = TabBarBottomClearance())
                    .verticalScroll(rememberScrollState()),
            ) {
                BasicTextField(
                    value = body,
                    onValueChange = {
                        text = it
                        dirty = true
                        // 一改动就把上一次的保存失败结论收掉：那句话说的是**上一次**的内容，
                        // 留在屏幕上会让人以为这次也没存上。
                        saveError = null
                    },
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 12.dp),
                    // 代码用等宽：脚本里对齐的赋值/缩进是读得出来的信息，比例字体把它抹平了。
                    textStyle = TextStyle(
                        fontFamily = FontFamily.Monospace,
                        fontSize = 14.sp,
                        lineHeight = 20.sp,
                        color = palette.text,
                    ),
                    cursorBrush = SolidColor(palette.accent),
                )
            }
        }
    }

    if (confirmDiscard) {
        AlertDialog(
            onDismissRequest = { confirmDiscard = false },
            title = { Text("有未保存的修改") },
            text = { Text("退出会丢掉这些改动。") },
            confirmButton = {
                TextButton(onClick = {
                    confirmDiscard = false
                    onClose()
                }) {
                    Text("放弃修改")
                }
            },
            dismissButton = {
                TextButton(onClick = { confirmDiscard = false }) {
                    Text("继续编辑")
                }
            },
        )
    }
}

/**
 * 读一次正文：**读到了给正文，读不到给失败原文**，两者绝不会同时非空。
 *
 * 单独抽出来的两个理由：① 「失败不冒充空文件」这条判据现在是一个可以直接单测的函数
 * （空文件 = `text = ""`、读失败 = `text = null` + `error`），而不是埋在组合里的
 * try/catch；② 组合函数已经贴着 detekt 的长函数线。
 *
 * 取消**照原样抛**（`CancellationException` 是协程的通行证，吞了会让换文件时的重读
 * 变成"上一位的结果盖到新文件上"）。
 */
internal suspend fun loadScriptText(
    projectId: String,
    relPath: String,
    onRead: suspend (projectId: String, relPath: String) -> String,
): ScriptLoad = try {
    ScriptLoad(text = onRead(projectId, relPath), error = null)
} catch (e: CancellationException) {
    throw e
} catch (t: Exception) {
    ScriptLoad(text = null, error = t.message ?: t.javaClass.simpleName)
}

/**
 * 保存一次：**正常返回即已落盘**；失败把原文作为结果带回来（不往组合里抛）。
 *
 * 与 [loadScriptText] 同一条纪律：`CancellationException` 照原样抛（协程的通行证），
 * 其余异常都收成"这次没存上"的那句话 —— 由调用方决定它显示在哪儿。
 */
internal suspend fun saveScriptText(
    projectId: String,
    relPath: String,
    content: String,
    onSave: suspend (projectId: String, relPath: String, content: String) -> Unit,
): String? = try {
    onSave(projectId, relPath, content)
    null
} catch (e: CancellationException) {
    throw e
} catch (t: Exception) {
    t.message ?: t.javaClass.simpleName
}

/**
 * [loadScriptText] 的结果。
 *
 * @property text 正文；`null` = 没读到（**不是**空文件 —— 空文件是 `""`）。
 * @property error 失败原文；`null` = 读到了。
 */
internal data class ScriptLoad(val text: String?, val error: String?)

/** 读不到 / 读取中这两句的落位（居中一行，次级色）—— 与正文同一块地方，不另起一屏。 */
@Composable
private fun ColumnScope.EditorNotice(text: String) {
    Box(
        Modifier.weight(1f).fillMaxWidth().padding(horizontal = 16.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text = text,
            color = ThemeColors.textTertiary,
            style = MaterialTheme.typography.bodyMedium,
        )
    }
}
