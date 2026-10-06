package com.autoscript.domain.editor

/** 区间一律使用 Kotlin String 的 UTF-16 下标；颜色与 Compose 类型留在呈现层。 */
data class SyntaxSpan(val start: Int, val end: Int, val kind: SyntaxKind)

/** 显式编码对应原生桥；不依赖 enum ordinal，增项时须同步桥面。 */
enum class SyntaxKind(val wireCode: Int) {
    KEYWORD(0), STRING(1), COMMENT(2), NUMBER(3), FUNCTION_NAME(4), OPERATOR(5), TYPE(6),
}

/**
 * 单文档解析会话。highlight 是 CPU 工作，调用方必须在后台执行并串行调用。
 * 实现方保留增量语法树；文件关闭时释放，close 可重复调用。
 * 解析器不可用时返回空区间，正文与编辑能力不受影响。
 */
interface SyntaxHighlighter : AutoCloseable {
    fun highlight(source: String): List<SyntaxSpan>

    override fun close()

    companion object {
        val NONE: SyntaxHighlighter = object : SyntaxHighlighter {
            override fun highlight(source: String): List<SyntaxSpan> = emptyList()
            override fun close() = Unit
        }
    }
}
