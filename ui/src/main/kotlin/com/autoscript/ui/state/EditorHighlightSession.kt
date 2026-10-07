package com.autoscript.ui.state

import com.autoscript.domain.editor.SyntaxHighlighter
import com.autoscript.domain.editor.SyntaxSpan
import java.util.logging.Level
import java.util.logging.Logger
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.launch

/** 同一正文即使经历 A → B → A，也不能认领第一次 A 的在途结果。 */
internal data class SyntaxRequest(val revision: Long, val source: String)

internal data class SyntaxHighlightResult(val request: SyntaxRequest, val spans: List<SyntaxSpan>)

/**
 * 一份编辑文档的后台会话。factory、highlight、close 全由唯一 worker 串行执行。
 *
 * [close] 只取消 worker，绝不在调用线程等待 JNI。同步解析未必能被中断，故 finally
 * 在它返回后才释放 parser；输入递增版本，不把这种迟到结果交给新正文。Channel 只留
 * 最新输入，防止长解析期间攒起一队已过期工作。构造本身不启动工作，组合被放弃也不泄漏 native session。
 */
internal class EditorHighlightSession(
    private val relPath: String,
    private val factory: (String) -> SyntaxHighlighter,
    dispatcher: CoroutineDispatcher = Dispatchers.Default,
) : AutoCloseable {
    private val scope = CoroutineScope(SupervisorJob() + dispatcher)
    private val pending = Channel<SyntaxRequest>(Channel.CONFLATED)
    private val mutableResult = MutableStateFlow<SyntaxHighlightResult?>(null)
    val result: StateFlow<SyntaxHighlightResult?> = mutableResult.asStateFlow()

    @Volatile private var latest: SyntaxRequest? = null
    @Volatile private var closed = false
    private var revision = 0L
    private var started = false

    @Synchronized
    fun start() {
        if (started || closed) return
        started = true
        scope.launch { runSession() }
    }

    /** null = 尚未读到；只挪光标/改 IME 选区不产生新的解析版本。 */
    @Synchronized
    fun submitSource(source: String?) {
        if (closed || source == latest?.source) return
        revision++
        latest = source?.let { SyntaxRequest(revision, it) }
        mutableResult.value = null
        latest?.let { pending.trySend(it) }
    }

    fun isCurrent(request: SyntaxRequest): Boolean = !closed && latest == request

    @Synchronized
    override fun close() {
        if (closed) return
        closed = true
        latest = null
        mutableResult.value = null
        pending.close()
        scope.cancel()
    }

    @OptIn(FlowPreview::class)
    private suspend fun runSession() {
        var highlighter: SyntaxHighlighter? = null
        try {
            currentCoroutineContext().ensureActive()
            // 在同一个 try/finally 内创建：factory 同步执行期间被取消，返回的 session 仍会 close。
            highlighter = openHighlighter()
            currentCoroutineContext().ensureActive()
            pending.receiveAsFlow().debounce { syntaxDebounceMillis(it.source.length) }.collect { request ->
                currentCoroutineContext().ensureActive()
                if (isCurrent(request)) {
                    val spans = parse(highlighter, request.source)
                    currentCoroutineContext().ensureActive()
                    if (isCurrent(request)) mutableResult.value = SyntaxHighlightResult(request, spans)
                }
            }
        } finally {
            highlighter?.let { release(it) }
        }
    }

    // 有意宽捕获：解析器是 JNI/第三方，任何运行期异常都只许降级（回 NONE/空区间），
    // 不许把编辑器带崩；CancellationException 已在上一支原样重抛，不会被吞。
    @Suppress("TooGenericExceptionCaught")
    private fun openHighlighter(): SyntaxHighlighter = try {
        factory(relPath)
    } catch (cancelled: CancellationException) {
        throw cancelled
    } catch (failure: RuntimeException) {
        LOGGER.log(Level.WARNING, "编辑器语法会话创建失败：$relPath", failure)
        SyntaxHighlighter.NONE
    } catch (failure: LinkageError) {
        LOGGER.log(Level.WARNING, "编辑器语法解析器不可用：$relPath", failure)
        SyntaxHighlighter.NONE
    }

    // 有意宽捕获：解析器是 JNI/第三方，任何运行期异常都只许降级（回 NONE/空区间），
    // 不许把编辑器带崩；CancellationException 已在上一支原样重抛，不会被吞。
    @Suppress("TooGenericExceptionCaught")
    private fun parse(highlighter: SyntaxHighlighter, source: String): List<SyntaxSpan> = try {
        highlighter.highlight(source)
    } catch (cancelled: CancellationException) {
        throw cancelled
    } catch (failure: RuntimeException) {
        LOGGER.log(Level.WARNING, "编辑器语法解析失败：$relPath", failure)
        emptyList()
    } catch (failure: LinkageError) {
        LOGGER.log(Level.WARNING, "编辑器语法解析器不可用：$relPath", failure)
        emptyList()
    }

    // 有意宽捕获：解析器是 JNI/第三方，任何运行期异常都只许降级（回 NONE/空区间），
    // 不许把编辑器带崩；CancellationException 已在上一支原样重抛，不会被吞。
    @Suppress("TooGenericExceptionCaught")
    private fun release(highlighter: SyntaxHighlighter) {
        try {
            highlighter.close()
        } catch (failure: RuntimeException) {
            LOGGER.log(Level.WARNING, "编辑器语法会话释放失败：$relPath", failure)
        } catch (failure: LinkageError) {
            LOGGER.log(Level.WARNING, "编辑器语法会话释放失败：$relPath", failure)
        }
    }

    private companion object {
        val LOGGER: Logger = Logger.getLogger("AutoScript.editor")
    }
}

/** 大文本少做中间解析；这是输入防抖，不延迟 TextFieldValue 的更新。 */
internal fun syntaxDebounceMillis(sourceLength: Int): Long =
    if (sourceLength >= LARGE_SYNTAX_SOURCE_CHARS) 300L else 120L

internal const val LARGE_SYNTAX_SOURCE_CHARS = 16_384
