package com.autoscript.platform.editor

import com.autoscript.domain.editor.SyntaxHighlighter
import java.util.Locale

/** 每次调用为一个 JavaScript 文档新建会话；调用方在文件关闭时 close。 */
object EditorHighlighters {
    private val javascriptExtensions = setOf("js", "mjs", "cjs")

    fun create(relPath: String): SyntaxHighlighter = create(relPath) { TreeSitterNative.load() }

    // 工厂缝保证纯 JVM 单测也走生产的扩展名筛选与加载失败降级。
    @Suppress("SwallowedException") // 可选 so 缺席或 JNI 符号不兼容时，编辑器保留纯文本能力。
    internal fun create(relPath: String, loadNative: () -> TreeSitterNativeOps): SyntaxHighlighter {
        val fileName = relPath.substringAfterLast('/').substringAfterLast('\\')
        val extension = fileName.substringAfterLast('.', "").lowercase(Locale.ROOT)
        if (extension !in javascriptExtensions) return SyntaxHighlighter.NONE
        return try {
            TreeSitterHighlighter.create(loadNative())
        } catch (_: LinkageError) {
            SyntaxHighlighter.NONE
        }
    }
}
