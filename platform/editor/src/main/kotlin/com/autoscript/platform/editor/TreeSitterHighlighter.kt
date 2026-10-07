package com.autoscript.platform.editor

import com.autoscript.domain.editor.SyntaxHighlighter
import com.autoscript.domain.editor.SyntaxKind
import com.autoscript.domain.editor.SyntaxSpan

/** 单文档 native 会话。解析、缓冲区重用及销毁在同一监视器内串行执行。 */
internal class TreeSitterHighlighter private constructor(
    private val native: TreeSitterNativeOps,
) : SyntaxHighlighter {
    private var outSpans = IntArray(INITIAL_SPANS * SPAN_WIDTH)
    private var handle = native.createSession()

    @Synchronized
    @Suppress("SwallowedException") // JNI 符号缺失与加载失败同样降级；不捕获 VM 错误或取消异常。
    override fun highlight(source: String): List<SyntaxSpan> {
        if (handle == 0L) return emptyList()
        // String.getBytes(UTF_8)：NUL 是 00，emoji 是四字节，孤立 surrogate 替换为 '?'。
        val utf8 = source.toByteArray(Charsets.UTF_8)
        val count = try {
            parse(utf8)
        } catch (_: LinkageError) {
            return emptyList()
        }
        if (count <= 0 || count > outSpans.size / SPAN_WIDTH) return emptyList()
        return decodeSpans(utf8, count)
    }

    private fun parse(source: ByteArray): Int {
        while (true) {
            val count = native.highlight(handle, source, outSpans)
            if (count != BUFFER_TOO_SMALL || outSpans.size == MAX_SPANS * SPAN_WIDTH) return count
            // 有界倍增；-3 的部分输出不作为完整高亮返回，下一次解析复用已扩容缓冲区。
            val capacity = (outSpans.size / SPAN_WIDTH * 2).coerceAtMost(MAX_SPANS)
            outSpans = IntArray(capacity * SPAN_WIDTH)
        }
    }

    private fun decodeSpans(source: ByteArray, count: Int): List<SyntaxSpan> {
        val offsets = utf16Boundaries(source)
        return buildList(count) {
            repeat(count) { index ->
                val start = outSpans[index * SPAN_WIDTH]
                val end = outSpans[index * SPAN_WIDTH + 1]
                val kind = kindsByCode[outSpans[index * SPAN_WIDTH + 2]]
                if (kind != null && isValidByteRange(start, end, source.size)) {
                    val utf16Start = offsets[start]
                    val utf16End = offsets[end]
                    if (utf16Start >= 0 && utf16End >= 0) add(SyntaxSpan(utf16Start, utf16End, kind))
                }
            }
        }
    }

    @Synchronized
    @Suppress("SwallowedException") // 销毁符号缺位也只尝试一次；会话在调用 native 之前即标记关闭。
    override fun close() {
        val session = handle
        if (session == 0L) return
        handle = 0L
        outSpans = IntArray(0)
        try {
            native.destroySession(session)
        } catch (_: LinkageError) {
            // native 已不可用；后续 close/highlight 不再访问这个会话。
        }
    }

    companion object {
        private const val SPAN_WIDTH = 3
        private const val INITIAL_SPANS = 256
        private const val MAX_SPANS = 65_536
        private const val BUFFER_TOO_SMALL = -3
        private val kindsByCode = SyntaxKind.entries.associateBy { it.wireCode }

        fun create(native: TreeSitterNativeOps): SyntaxHighlighter {
            val highlighter = TreeSitterHighlighter(native)
            return if (highlighter.handle == 0L) SyntaxHighlighter.NONE else highlighter
        }
    }
}

/** 原生层吐出的区间必须非空、顺序正确且落在源码字节范围内；否则整条丢弃。 */
private fun isValidByteRange(start: Int, end: Int, size: Int): Boolean =
    start >= 0 && start < end && end <= size

/**
 * 只映射编码后的 UTF-8 字符边界，字符内部字节保持 -1；总耗时 O(bytes + spans)。
 * 输入由 String.toByteArray(UTF_8) 生成：四字节码点占两个 UTF-16 单元，其余占一个，
 * 包括 Java 将每个孤立 surrogate 替换成的单字节 '?'，因此不会错算后续区间。
 */
private fun utf16Boundaries(source: ByteArray): IntArray {
    val offsets = IntArray(source.size + 1) { -1 }
    var byteOffset = 0
    var utf16Offset = 0
    while (byteOffset < source.size) {
        offsets[byteOffset] = utf16Offset
        val leadingByte = source[byteOffset].toInt() and 0xff
        val byteWidth = when {
            leadingByte < 0x80 -> 1
            leadingByte < 0xe0 -> 2
            leadingByte < 0xf0 -> 3
            else -> 4
        }
        byteOffset += byteWidth
        utf16Offset += if (byteWidth == 4) 2 else 1
    }
    offsets[source.size] = utf16Offset
    return offsets
}
