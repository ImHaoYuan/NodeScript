package com.autoscript.ui.screens

import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.PointerInputScope
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.autoscript.ui.components.ActionBar
import com.autoscript.ui.components.ActionBarAction
import com.autoscript.ui.components.LocalTabBarHidden
import com.autoscript.ui.components.LocalToast
import com.autoscript.ui.components.TabBarBottomClearance
import com.autoscript.ui.components.color
import com.autoscript.ui.components.pressable
import com.autoscript.ui.state.EditorAffordance
import com.autoscript.ui.state.EditorJump
import com.autoscript.ui.state.EditorScroll
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
    //
    // 存的是 **TextFieldValue 而不是 String**：光标位置也是编辑器状态的一部分 ——
    // "点正文下方的空白把光标送到文末"（用户口径）要能**主动设选区**，而 String 那套重载
    // 的选区归 Compose 内部管，外面够不着。
    var value by remember { mutableStateOf<TextFieldValue?>(null) }
    var loadError by remember { mutableStateOf<String?>(null) }
    var saveError by remember { mutableStateOf<String?>(null) }
    var dirty by remember { mutableStateOf(false) }
    var saving by remember { mutableStateOf(false) }
    var confirmDiscard by remember { mutableStateOf(false) }

    // 换文件（key 变）重读：编辑器是整屏面，同一个实例被复用到另一个文件上时必须把
    // 上一位的状态清干净，否则会拿着 A 的内容去存 B。
    LaunchedEffect(projectId, relPath) {
        value = null
        loadError = null
        saveError = null
        dirty = false
        val read = loadScriptText(projectId, relPath, onRead)
        // 读失败时正文是 null（不是空串），光标位置无从谈起 —— 两处一起置空。
        value = read.text?.let { TextFieldValue(it) }
        loadError = read.error
    }

    /** 退回上一层（返回键与 ‹ 共用）：有未保存改动先问一句，不静默丢。 */
    fun close() {
        if (dirty) confirmDiscard = true else onClose()
    }

    BackHandler { close() }

    // 局部 val：`value` 是可变的，下面 when 的分支里要的是"读到了"那一档的非空值。
    val loaded = value
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
                    enabled = loaded != null && dirty && !saving,
                    onClick = {
                        val current = loaded ?: return@ActionBarAction
                        scope.launch {
                            saving = true
                            saveError = null
                            saveError = saveScriptText(projectId, relPath, current.text, onSave)
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
            loaded == null -> EditorNotice(text = "读取中…")
            // 换文件时编辑器会**整块重建**（重读期间这一支不在树上）：滚动位置、光标、
            // 手指方向都不跨文件继承 —— 不需要再给它们加 key。
            else -> EditorBody(
                value = loaded,
                onValueChange = { next ->
                    // **只有正文真的变了才标脏**：点正文下方的空白只是把光标挪到文末
                    // （用户口径），正文一个字没动 —— 那时亮起"保存"、退出还要问
                    // "有未保存的修改"，就是在骗人。
                    val edited = next.text != loaded.text
                    value = next
                    if (edited) {
                        dirty = true
                        // 一改动就把上一次的保存失败结论收掉：那句话说的是**上一次**的
                        // 内容，留在屏幕上会让人以为这次也没存上。
                        saveError = null
                    }
                },
                modifier = Modifier
                    .weight(1f)
                    .fillMaxWidth()
                    // 底栏留白：编辑器住页签条**下面**那一层（外壳画的底栏浮在页内容之上），
                    // 不留白就会被胶囊底栏压住最后几行（编辑态底栏已收起，这里只剩导航栏）。
                    .padding(bottom = TabBarBottomClearance()),
            )
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

/**
 * 正文那一层：**行号槽 + 正文 + 右下那颗跳转钮**（含它上面的气泡）。
 *
 * 正文**不折行**（用户口径）：一行就是一行，超出的部分交给横向滚动，所以"第几行"
 * 只有一个口径 —— 行号列就是 `1..lineCount(text)`，与正文天然逐行对齐。
 * 双指捏合改的是**字号/行高/字距**（见 [detectPinchZoom]），不是把画面拉伸。
 *
 * 单独一个函数有两个理由：① [ScriptEditorScreen] 已经贴着 detekt 的长函数线；
 * ② 它自带四份状态（纵横两根滚动轴、缩放倍率、排版结果报回来的正文高度、手指方向）
 * —— 留在屏里会和保存/退出那套混在一起。
 *
 * @param value 正文与**光标位置**（选区由这一层改，见"点空白送到文末"那一段）。
 * @param onValueChange 内容或选区变了（调用方据此标脏、清掉上一次的保存失败结论）。
 */
@Composable
private fun EditorBody(
    value: TextFieldValue,
    onValueChange: (TextFieldValue) -> Unit,
    modifier: Modifier = Modifier,
) {
    val palette = ThemeColors
    val density = LocalDensity.current
    val vScroll = rememberScrollState()
    val hScroll = rememberScrollState()
    val focusRequester = remember { FocusRequester() }
    val scope = rememberCoroutineScope()
    // 双指缩放的倍率：字号/行高/字距**整组**乘它（不是 `graphicsLayer` 拉伸画面）。
    // 拉伸只改画面、不改排版 —— 滚动范围、行号位置、"屏幕外还有几行"会全部对不上；
    // 改字号是让排版自己重算一遍，所有几何都跟着走。
    var zoom by remember { mutableStateOf(1f) }
    // 正文自己占的高度（**不折行**之后 = 行数 × 行高）：判断"点在不在正文下方"要用它。
    // 存 Float 而不是 TextLayoutResult：等值比较，布局回调万一再来一次也不会自激。
    var contentHeightPx by remember { mutableStateOf(0f) }
    // 手指方向：往下拖 = 往上看（见 [EditorScroll.affordance]）。默认 false = 去最底。
    var towardTop by remember { mutableStateOf(false) }
    LaunchedEffect(vScroll) {
        var last = vScroll.value
        snapshotFlow { vScroll.value }.collect { now ->
            if (now != last) {
                towardTop = now < last
                last = now
            }
        }
    }
    // 正文与行号槽**共用同一套字体度量**（同一个 TextStyle，只换对齐与颜色）：换字号、
    // 换行高、换字距都只改一处，否则第 n 个数字会逐行偏离第 n 行。
    val codeStyle = remember(palette.text, zoom) { editorCodeStyle(palette.text, zoom) }
    val gutterStyle = remember(codeStyle, palette.textTertiary) {
        editorGutterStyle(codeStyle, palette.textTertiary)
    }

    BoxWithConstraints(modifier.fillMaxSize()) {
        val viewportHeight = maxHeight
        val viewportPx = with(density) { viewportHeight.toPx() }
        // 行高跟着缩放走：气泡那个数与"超没超出一屏"都按**当前**行高算。
        val lineHeightPx = with(density) { (EditorLineHeight * zoom).toPx() }
        // 正文上沿到整行上沿的距离（判断"点的是不是正文下方"时要用）。
        val topPaddingPx = with(density) { EditorVerticalPadding.toPx() }
        // 正文上下那两块留白（上 12dp + 下 60dp 让位给圆钮）：判断"超没超出一屏"时
        // 它们是内容的一部分，要从视口里扣掉。
        val contentPaddingPx = with(density) { (EditorVerticalPadding + EditorChipReserve).toPx() }
        // **不折行**之后"逻辑行 = 视觉行"：行数直接从文本数出来（不必等排版结果），
        // 行号列就是 1..lines，气泡那个数也按它算。
        val lines = EditorScroll.lineCount(value.text)
        val gutterText = remember(lines) { (1..lines).joinToString("\n") }
        // 槽宽按行数留（最大行号是几位就留几位）；数字跟着缩放，槽宽也得跟着。
        val gutterWidth = EditorGutterStart +
            EditorGutterDigit * zoom * EditorScroll.gutterDigits(lines) + EditorGutterEnd
        val gutterWidthPx = with(density) { gutterWidth.toPx() }
        // 正文可视宽度（Row 里扣掉行号槽那一块）：正文不足一屏宽时也要占满它，
        // 否则右边那片空白点不到、也落不了光标。
        val textViewportWidth = maxWidth - gutterWidth
        // 气泡里那个数与按钮去哪一头：**只在"跨过一行"或"方向翻面"时才变值** ——
        // 直接读 `vScroll.value` 会让整块编辑面每滚一帧重组一次。
        val affordance by remember(viewportPx, lineHeightPx, lines) {
            derivedStateOf {
                // `vScroll.value` 是 Int（px 整数），几何那层一律吃 Float。
                EditorScroll.affordance(vScroll.value.toFloat(), viewportPx, lineHeightPx, lines, towardTop)
            }
        }
        Box(Modifier.fillMaxSize().verticalScroll(vScroll)) {
            Row(
                Modifier
                    .fillMaxWidth()
                    // 正文不足一屏时，下面那一块**也要收得到点击**（用户口径：点空白把光标
                    // 送到文末）。不给最小高度，那块空白根本不属于编辑器，点了没反应。
                    .heightIn(min = viewportHeight)
                    // 手势挂在**内边距之外**这一层：这样 `at` 的坐标就是整行的坐标，
                    // "在不在正文下方"只需和正文高度比，不用再去减内边距。
                    .pointerInput(value.text, contentHeightPx) {
                        detectTapGestures { at ->
                            // 只认**正文下方、且不在行号槽里**的空白：正文本身有自己的落光标
                            // 逻辑（点哪落哪），行号槽点了不该动光标。
                            val belowText = at.y > contentHeightPx + topPaddingPx
                            if (belowText && at.x > gutterWidthPx) {
                                // `composition = null`：正在输入法组词时改选区要先收掉
                                // 那一段组词，否则光标会跳回组词开头。
                                onValueChange(
                                    value.copy(
                                        selection = TextRange(value.text.length),
                                        composition = null,
                                    ),
                                )
                                focusRequester.requestFocus()
                            }
                        }
                    }
                    // 双指捏合缩放（见 [detectPinchZoom]）：单指阶段一个事件都不消费，
                    // 竖向滚动、落光标、长按选词照旧。
                    .pointerInput(Unit) {
                        detectPinchZoom { factor ->
                            zoom = (zoom * factor).coerceIn(EDITOR_ZOOM_MIN, EDITOR_ZOOM_MAX)
                        }
                    }
                    // 下方留出那颗圆钮的位置：滚到最底时最后一行正好停在钮上方，
                    // 而不是被它压住半行（钮是浮层，不参与布局，只能这样让位）。
                    .padding(top = EditorVerticalPadding, bottom = EditorChipReserve),
                verticalAlignment = Alignment.Top,
            ) {
                // 行号槽：一条淡灰竖线把它和正文分开（用户口径）。数字与正文**同一套行高**
                // （同一个 TextStyle 派生的），所以第 n 个数字恰好落在第 n 行上。
                Box(
                    Modifier
                        .width(gutterWidth)
                        .drawBehind {
                            // 竖线画在槽的**右缘**：贴着正文那一侧，不占正文的宽度。
                            val lineWidth = EditorGutterLineWidth.toPx()
                            drawRect(
                                color = palette.divider,
                                topLeft = Offset(size.width - lineWidth, 0f),
                                size = Size(lineWidth, size.height),
                            )
                        },
                ) {
                    Text(
                        text = gutterText,
                        style = gutterStyle,
                        modifier = Modifier.fillMaxWidth().padding(end = EditorGutterEnd),
                    )
                }
                // 正文：**不折行 + 横向滚动**（用户口径）。一行就是一行，长行往右拉；
                // 于是"第几行"只有一个口径，行号列与正文天然逐行对齐（折行那套
                // `getLineForOffset` 映射整个不需要了）。
                Box(Modifier.weight(1f).horizontalScroll(hScroll)) {
                    BasicTextField(
                        value = value,
                        onValueChange = onValueChange,
                        modifier = Modifier
                            // **不折行**靠的是约束而不是参数：`BasicTextField` 没有
                            // `softWrap`，但外层 `horizontalScroll` 会给内容无限宽 —— 拿不到
                            // 宽度上限，排版就不会折行，字段宽度跟着最长的行走。
                            // `widthIn(min = …)` 补另一头：正文比一屏窄时也占满可视宽度，
                            // 右边那片空白才点得到、才落得了光标。
                            .widthIn(min = textViewportWidth)
                            .focusRequester(focusRequester)
                            .padding(start = EditorTextStart, end = EditorTextEnd),
                        textStyle = codeStyle,
                        cursorBrush = SolidColor(palette.accent),
                        onTextLayout = { layout -> contentHeightPx = layout.size.height.toFloat() },
                    )
                }
            }
        }
        EditorJumpChip(
            affordance = affordance,
            // 气泡只在正文**超出一屏**时出现：没超出时"屏幕外还有几行"是个假问题。
            // 比的是"正文能不能占满视口"（上下留白不算正文，要从视口里扣掉）。
            showBubble = EditorScroll.overflows(lines, lineHeightPx, viewportPx - contentPaddingPx),
            onJump = {
                val toTop = affordance.jump == EditorJump.TOP
                scope.launch {
                    vScroll.animateScrollTo(if (toTop) 0 else vScroll.maxValue)
                    // 跳完**把方向翻过来**：刚去了顶部，下一次有用的是"去底部"。不翻的话
                    // 钮会停在"去顶部"上、再点一次什么都不发生 —— 看起来像按钮坏了。
                    // （用户口径只规定了"手指方向 → 钮的方向"，跳转后的方向由这里补齐。）
                    towardTop = !toTop
                }
            },
            modifier = Modifier.align(Alignment.BottomEnd).padding(EditorChipMargin),
        )
    }
}

/**
 * 双指捏合缩放（回调给的是**相对上一次**的倍率，调用方自己乘起来再夹上下限）。
 *
 * 为什么不用 `detectTransformGestures`：它**单指**拖动过了 touch slop 之后也会
 * `consume()` —— 竖向滚动、"点空白落光标"、长按选词当场全失效。这里等到第二根
 * 手指落下才开始算两指距离，单指阶段一个事件都不碰。
 *
 * 又为什么跑在 [PointerEventPass.Initial] 上：Initial 趟是**父 → 子**，而横向滚动与
 * 文本框自己的手势都在子树里（Main 趟是子 → 父，抢不过它们）。两根手指往两边分开，
 * 在横向滚动看来就是一次"横向拖动"—— 不在这趟里拦下来，缩放会跟横向滚动打架。
 */
private suspend fun PointerInputScope.detectPinchZoom(onZoom: (Float) -> Unit) {
    awaitEachGesture {
        awaitFirstDown(requireUnconsumed = false, pass = PointerEventPass.Initial)
        var lastDistance = 0f
        var pinching = false
        while (true) {
            val event = awaitPointerEvent(PointerEventPass.Initial)
            val pressed = event.changes.filter { it.pressed }
            // 不足两根手指：要么还没开始捏（接着等），要么捏到一半抬起一根（收工）。
            if (pressed.size < 2) {
                if (pinching || pressed.isEmpty()) return@awaitEachGesture
                continue
            }
            val distance = (pressed[0].position - pressed[1].position).getDistance()
            if (pinching && lastDistance > 0f) {
                onZoom(distance / lastDistance)
                // **在 Initial 趟消费**：子树（横向滚动 / 文本框）就收不到这两个指针了。
                pressed.forEach { it.consume() }
            }
            pinching = true
            lastDistance = distance
        }
    }
}

/**
 * 编辑器右下那颗圆钮与它上面的气泡。
 *
 * 形态按用户口径：**正圆、白底、一圈淡灰描边**（让它从两种主题的正文里都浮起来）。
 * 钮上那个箭头与"去最底/去最顶"同步翻面（见 [EditorScroll.affordance]），气泡里是
 * **你要去的那一头**在屏幕外还剩几行 —— 气泡与钮说的是同一件事，不各说各的。
 *
 * @param affordance 去哪一头 + 气泡上的数字。
 * @param showBubble 正文有没有超出一屏（没超出就不摆气泡）。
 */
@Composable
private fun EditorJumpChip(
    affordance: EditorAffordance,
    showBubble: Boolean,
    onJump: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val palette = ThemeColors
    Column(modifier, horizontalAlignment = Alignment.CenterHorizontally) {
        AnimatedVisibility(
            visible = showBubble,
            enter = fadeIn(tween(EDITOR_CHIP_FADE_MILLIS)) + slideInVertically { it / 2 },
            exit = fadeOut(tween(EDITOR_CHIP_FADE_MILLIS)) + slideOutVertically { it / 2 },
        ) {
            Box(
                Modifier
                    .background(palette.editorChipBackground, RoundedCornerShape(EditorBubbleRadius))
                    .border(EditorChipBorderWidth, palette.editorChipBorder, RoundedCornerShape(EditorBubbleRadius))
                    .padding(horizontal = 8.dp, vertical = 2.dp),
            ) {
                Text(
                    text = affordance.offscreenLines.toString(),
                    color = palette.editorChipIcon,
                    style = MaterialTheme.typography.labelSmall,
                )
            }
        }
        Spacer(Modifier.height(6.dp))
        Box(
            Modifier
                .size(EditorChipSize)
                .background(palette.editorChipBackground, CircleShape)
                .border(EditorChipBorderWidth, palette.editorChipBorder, CircleShape)
                .pressable(role = Role.Button, shape = CircleShape, onClick = onJump),
            contentAlignment = Alignment.Center,
        ) {
            Text(
                text = if (affordance.jump == EditorJump.TOP) "↑" else "↓",
                color = palette.editorChipIcon,
                style = MaterialTheme.typography.titleMedium,
            )
        }
    }
}

/**
 * 正文与行号槽**共用**的那套字体度量（行号只换对齐与颜色）。
 *
 * 双指缩放（[zoom]）乘在字号/行高/字距三处：只改这一处，行号槽与正文的对齐关系
 * 才不会散（改字号不改行高、或只改正文不改行号，第 n 个数字就会逐行偏离第 n 行）。
 */
private fun editorCodeStyle(color: Color, zoom: Float): TextStyle = TextStyle(
    fontFamily = FontFamily.Monospace,
    fontSize = EditorFontSize * zoom,
    lineHeight = EditorLineHeight * zoom,
    letterSpacing = EditorLetterSpacing * zoom,
    color = color,
)

/** 行号槽的样式：与正文同一套度量，只换右对齐与更淡的颜色。 */
private fun editorGutterStyle(code: TextStyle, color: Color): TextStyle =
    code.copy(textAlign = TextAlign.End, color = color)

/** 正文字号（等宽 14sp，与重构前一致）。双指缩放的倍率乘在它（以及行高、字距）上。 */
private val EditorFontSize = 14.sp

/** 正文行高：行号槽、气泡那个数、点空白的判据**全按它算** —— 改它要三处一起看。 */
private val EditorLineHeight = 20.sp

/** 字间距（用户口径：别让字连在一起）。等宽字体的 CJK 与数字挤在一起最难认。 */
private val EditorLetterSpacing = 0.5.sp

/**
 * 双指缩放的上下限（相对 [EditorFontSize] 的倍率）。
 *
 * 下限 0.6 倍 ≈ 8.4sp（再小在手机上看不清了），上限 3 倍 = 42sp（一屏放不下几行，
 * 但看一条特别长的行够用）。
 */
private const val EDITOR_ZOOM_MIN = 0.6f
private const val EDITOR_ZOOM_MAX = 3f

/** 正文上下留白（行号槽与正文共用同一份，否则第一行就对不齐）。 */
private val EditorVerticalPadding = 12.dp

/** 行号槽左内边距。 */
private val EditorGutterStart = 6.dp

/** 行号槽**每一位数字**的宽度（14sp 等宽 + 0.5sp 字距 ≈ 8.9dp，取 9dp 留一点余量）。 */
private val EditorGutterDigit = 9.dp

/** 行号槽右内边距（那条竖线画在它的右缘）。 */
private val EditorGutterEnd = 8.dp

/** 行号槽那条竖线的宽度（px 由 `drawBehind` 里换算，这里只存 dp 值）。 */
private val EditorGutterLineWidth = 1.dp

/** 正文左内边距（与行号槽的竖线之间留一口气）。 */
private val EditorTextStart = 12.dp

/** 正文右内边距。 */
private val EditorTextEnd = 16.dp

/** 右下那颗圆钮的直径（与"回到顶部"那颗同档）。 */
private val EditorChipSize = 44.dp

/** 圆钮与气泡的描边宽度（用户口径"淡淡的灰边"）。 */
private val EditorChipBorderWidth = 1.dp

/** 气泡的圆角。 */
private val EditorBubbleRadius = 8.dp

/** 圆钮距屏幕右下角的距离。 */
private val EditorChipMargin = 16.dp

/** 正文下方的留白：让出右下那颗圆钮（直径 + 外边距 + 一口气）的位置。 */
private val EditorChipReserve = 60.dp

/** 气泡出现/消失的时长（与全仓那几条小过渡同档）。 */
private const val EDITOR_CHIP_FADE_MILLIS = 160
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
