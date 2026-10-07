package com.autoscript.ui.components

import androidx.compose.runtime.ProvidableCompositionLocal
import androidx.compose.runtime.staticCompositionLocalOf
import com.autoscript.domain.editor.SyntaxHighlighter

/**
 * 语法高亮会话的来源（外壳实现，编辑器只认这一个口）。
 *
 * 为什么是接口而不是直接让编辑器去问宿主：`:ui` 的屏只收 `:domain` 的 DTO 与函数，
 * `HostSummary` 只有外壳（`MainActivity`）持有 —— 编辑器要的是"给个 relPath、还我一个
 * 高亮会话"，就把这一条线抽成一个函数式接口。
 */
fun interface EditorHighlightHost {
    /**
     * 为一个脚本文件开高亮会话。**实现方不得抛**：原生解析器缺席/加载失败时回
     * [SyntaxHighlighter.NONE]（编辑器据此按纯文本编辑，不是错误）。
     */
    fun open(relPath: String): SyntaxHighlighter
}

/**
 * 语法高亮的宿主口（未供 = null = 本屏不起会话）。
 *
 * 走 CompositionLocal 而不是层层传参，理由与 [LocalToast] 同款：发出点（编辑器）在屏内、
 * 宿主只有一处（外壳），传参要把这条线穿过四屏的签名、每屏转发一次。null 也**不是**错误态：
 * 预览/单测里没有宿主，编辑器就该是纯文本。
 */
val LocalEditorHighlightHost: ProvidableCompositionLocal<EditorHighlightHost?> =
    staticCompositionLocalOf { null }
