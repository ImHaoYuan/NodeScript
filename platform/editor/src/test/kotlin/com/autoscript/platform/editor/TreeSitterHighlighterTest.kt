package com.autoscript.platform.editor

import com.autoscript.domain.editor.SyntaxHighlighter
import com.autoscript.domain.editor.SyntaxKind
import com.autoscript.domain.editor.SyntaxSpan
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotSame
import org.junit.jupiter.api.Assertions.assertSame
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * 纯 JVM 单测：可注入的 [TreeSitterNativeOps] 假实现顶掉 JNI，走生产同一份
 * 扩展名筛选 / UTF-8 字节 → UTF-16 换算 / 扩容重试 / 降级 / close 逻辑。
 * 测试函数一律块体：表达式体返回非 Unit 会被 JUnit 静默跳过（本仓踩过）。
 */
class TreeSitterHighlighterTest {

    /** [respond] 收到 (handle, utf8, out)，自己往 out 里写三元组并返回条数/负码。 */
    private class FakeNative(
        private val session: Long = 7L,
        private val respond: (Long, ByteArray, IntArray) -> Int = { _, _, _ -> 0 },
    ) : TreeSitterNativeOps {
        var creates = 0
        var highlightCalls = 0
        val capacities = mutableListOf<Int>()
        val destroyed = mutableListOf<Long>()

        override fun createSession(): Long {
            creates++
            return session
        }

        override fun highlight(handle: Long, source: ByteArray, outSpans: IntArray): Int {
            highlightCalls++
            capacities += outSpans.size / 3
            return respond(handle, source, outSpans)
        }

        override fun destroySession(handle: Long) {
            destroyed += handle
        }
    }

    /** 在 UTF-8 字节里定位 [needle]，返回其 [start, end) 字节区间（测试坐标一律算出来，不手写）。 */
    private fun byteRange(source: String, needle: String): Pair<Int, Int> {
        val start = source.substring(0, source.indexOf(needle)).toByteArray(Charsets.UTF_8).size
        return start to start + needle.toByteArray(Charsets.UTF_8).size
    }

    private fun spansOf(vararg triples: Triple<Int, Int, Int>): (Long, ByteArray, IntArray) -> Int =
        { _, _, out ->
            if (out.size / 3 < triples.size) {
                -3
            } else {
                triples.forEachIndexed { i, (s, e, k) ->
                    out[i * 3] = s
                    out[i * 3 + 1] = e
                    out[i * 3 + 2] = k
                }
                triples.size
            }
        }

    private fun production(relPath: String, native: FakeNative): SyntaxHighlighter {
        val h = EditorHighlighters.create(relPath) { native }
        assertNotSame(SyntaxHighlighter.NONE, h, "应走 tree-sitter 路径：$relPath")
        return h
    }

    @Test
    fun `扩展名筛选——js mjs cjs 大小写无关走原生，其余与无扩展名回 NONE 且不加载`() {
        for (name in listOf("a.js", "dir/b.MJS", "c.Cjs", "x\\y\\d.js")) {
            val native = FakeNative()
            production(name, native).close()
            assertEquals(1, native.creates, name)
        }
        for (name in listOf("a.ts", "a.jsx", "a.json", "a", "a.js.txt", "js")) {
            var loads = 0
            val h = EditorHighlighters.create(name) { loads++; FakeNative() }
            assertSame(SyntaxHighlighter.NONE, h, name)
            assertEquals(0, loads, "非 JS 不得触发 so 加载：$name")
        }
    }

    @Test
    fun `so 缺位（LinkageError）降级 NONE，不向编辑器抛`() {
        val h = EditorHighlighters.create("main.js") { throw UnsatisfiedLinkError("no libtreesitter") }
        assertSame(SyntaxHighlighter.NONE, h)
    }

    @Test
    fun `createSession 回 0 视为不可用，直接给 NONE`() {
        val native = FakeNative(session = 0L)
        assertSame(SyntaxHighlighter.NONE, EditorHighlighters.create("main.js") { native })
    }

    @Test
    fun `UTF-8 字节偏移换算成 UTF-16 下标——中文三字节记 1，emoji 四字节记 2`() {
        val src = "// 中文😀\nconst n = 42;"
        val (cs, ce) = byteRange(src, "// 中文😀")
        val (ns, ne) = byteRange(src, "42")
        val native = FakeNative(respond = spansOf(Triple(cs, ce, 2), Triple(ns, ne, 3)))
        val spans = production("a.js", native).highlight(src)
        val comment = "// 中文😀"
        assertEquals(
            listOf(
                SyntaxSpan(0, comment.length, SyntaxKind.COMMENT),
                SyntaxSpan(src.indexOf("42"), src.indexOf("42") + 2, SyntaxKind.NUMBER),
            ),
            spans,
        )
        assertEquals(7, comment.length, "夹具须区分 UTF-8 字节数（13）与 UTF-16 长度（7）")
        assertTrue(ce > comment.length)
    }

    @Test
    fun `内嵌 NUL 按单字节计，其后区间不漂`() {
        val src = "const a = \"x\u0000y\"; let b = 7;"
        val (s, e) = byteRange(src, "7")
        val spans = production("a.js", FakeNative(respond = spansOf(Triple(s, e, 3)))).highlight(src)
        assertEquals(listOf(SyntaxSpan(src.indexOf("7"), src.indexOf("7") + 1, SyntaxKind.NUMBER)), spans)
    }

    @Test
    fun `非法区间一律丢弃——越界、逆序、空区间、未知 kind、落在多字节字符中间`() {
        val src = "中x"   // 中 = 字节 0..3，x = 字节 3..4
        val native = FakeNative(
            respond = spansOf(
                Triple(0, 3, 0),     // 合法：中
                Triple(3, 99, 1),    // 越界
                Triple(3, 3, 1),     // 空
                Triple(4, 3, 1),     // 逆序
                Triple(3, 4, 42),    // 未知 kind
                Triple(1, 4, 1),     // 起点在「中」内部
                Triple(-1, 2, 1),    // 负起点
            ),
        )
        assertEquals(listOf(SyntaxSpan(0, 1, SyntaxKind.KEYWORD)), production("a.js", native).highlight(src))
    }

    @Test
    fun `容量不足 -3 时倍增重试，用完整结果，不用部分结果`() {
        val many = (0 until 300).map { Triple(it, it + 1, 3) }.toTypedArray()
        val src = "1".repeat(300)
        val native = FakeNative(respond = spansOf(*many))
        val spans = production("a.js", native).highlight(src)
        assertEquals(300, spans.size)
        assertEquals(listOf(256, 512), native.capacities, "首轮 256 不够，倍增到 512 一次即足")
    }

    @Test
    fun `容量到上限仍 -3 时停止重试并降级为空，不死循环`() {
        val native = FakeNative(respond = { _, _, _ -> -3 })
        assertEquals(emptyList<SyntaxSpan>(), production("a.js", native).highlight("x"))
        assertEquals(65_536, native.capacities.last(), "最后一次应恰在上限")
        assertTrue(native.highlightCalls < 20, "有界倍增：调用次数 ${native.highlightCalls}")
    }

    @Test
    fun `其它负码（空会话、解析失败、参数非法）降级为空列表`() {
        for (code in listOf(-1, -2, -4)) {
            val native = FakeNative(respond = { _, _, _ -> code })
            assertEquals(emptyList<SyntaxSpan>(), production("a.js", native).highlight("x"), "code=$code")
        }
    }

    @Test
    fun `close 幂等且 destroy 恰好一次；关闭后 highlight 回空且不再碰 native`() {
        val native = FakeNative(session = 11L, respond = spansOf(Triple(0, 1, 3)))
        val h = production("a.js", native)
        h.close()
        h.close()
        assertEquals(listOf(11L), native.destroyed)
        val before = native.highlightCalls
        assertEquals(emptyList<SyntaxSpan>(), h.highlight("1"))
        assertEquals(before, native.highlightCalls)
    }

    @Test
    fun `两个会话各持自己的句柄，关一个不影响另一个`() {
        val seen = mutableListOf<Long>()
        val record: (Long, ByteArray, IntArray) -> Int = { handle, _, _ -> seen += handle; 0 }
        val a = production("a.js", FakeNative(session = 1L, respond = record))
        val nb = FakeNative(session = 2L, respond = record)
        val b = production("b.js", nb)
        a.highlight("x")
        a.close()
        b.highlight("y")
        assertEquals(listOf(1L, 2L), seen)
        assertEquals(emptyList<Long>(), nb.destroyed)
        b.close()
        Unit
    }
}
