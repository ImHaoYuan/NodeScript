package com.autoscript.shell

import com.autoscript.bridge.ConsoleCollector
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertSame
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/** 每例自建 writer，logcat 换记录器；不改全局回调，也不依赖 Android 桩返回假值。 */
class HostLogTest {
    private data class Cat(val level: String, val tag: String, val text: String, val cause: Throwable?)

    private val logcat = mutableListOf<Cat>()
    private var now = 100L

    private fun writer(capacity: Int = 10) = HostLogWriter(
        logcat = { level, tag, text, cause -> logcat.add(Cat(level, tag, text, cause)) },
        capacity = capacity,
        nowMillis = { now },
    )

    @Test
    fun `两条通道都写 级别与宿主归属不变`() {
        val writer = writer()
        val collector = ConsoleCollector()
        writer.attach(collector).use {
            writer.i("T", "装配启动")
            writer.w("T", "绑定失败")
            writer.e("T", "恢复失败")
        }
        val lines = collector.drain(0).second
        assertEquals(listOf("info", "warn", "error"), logcat.map { it.level })
        assertEquals(listOf("装配启动", "绑定失败", "恢复失败"), logcat.map { it.text })
        assertEquals(listOf("info", "warn", "error"), lines.map { it.level })
        assertEquals(listOf("T: 装配启动", "T: 绑定失败", "T: 恢复失败"), lines.map { it.text })
        assertTrue(lines.all { it.runId == 0L })
    }

    @Test
    fun `启动期先写 logcat 接线后回放且保留原时间`() {
        val writer = writer()
        writer.i("T", "开始装配")
        now = 200L
        writer.w("T", "装配尚未就绪")
        assertEquals(2, logcat.size)

        now = 900L
        val collector = ConsoleCollector()
        writer.attach(collector).use {
            writer.i("T", "壳就绪")
        }
        val lines = collector.drain(0).second
        assertEquals(listOf(100L, 200L, 900L), lines.map { it.atMillis })
        assertEquals(listOf(1L, 2L, 3L), lines.map { it.seq })
        assertEquals(3, logcat.size, "回放不重打 logcat")
        assertTrue(collector.drain(3).second.isEmpty(), "续拉不重复回放")
    }

    @Test
    fun `启动缓冲满时丢最老并显式报告缺口`() {
        val writer = writer(capacity = 2)
        repeat(5) { writer.i("T", "事件$it") }
        val collector = ConsoleCollector()
        writer.attach(collector).close()
        val lines = collector.drain(0).second
        assertEquals(3, lines.size)
        assertEquals("warn", lines.last().level)
        assertTrue(lines.last().text.contains("已丢弃 3 行"))
        assertEquals(listOf("T: 事件3", "T: 事件4"), lines.dropLast(1).map { it.text })
        assertEquals(5, logcat.size, "缓冲溢出不影响 logcat")
    }

    @Test
    fun `缓冲与收集器同容量时缺口提示不被回放挤掉`() {
        val writer = writer(capacity = 2)
        repeat(5) { writer.i("T", "事件$it") }
        val collector = ConsoleCollector(capacity = 2)
        writer.attach(collector).close()
        val lines = collector.drain(0).second
        assertEquals(2, lines.size)
        assertEquals("T: 事件4", lines.first().text)
        assertEquals("warn", lines.last().level)
        assertTrue(lines.last().text.contains("已丢弃 3 行"), "接线前丢弃有明确提示")
        assertEquals(1L, collector.droppedCount(), "回放时的淘汰另走收集器计数")
    }

    @Test
    fun `镜像过长才截断 logcat 原文与异常不变`() {
        val writer = writer()
        val text = "长".repeat(HostLogWriter.MAX_TEXT_CHARS * 2)
        val cause = IllegalStateException("错误原因")
        val collector = ConsoleCollector()
        writer.attach(collector).use { writer.e("T", text, cause) }
        val line = collector.drain(0).second.single()
        assertEquals(HostLogWriter.MAX_TEXT_CHARS, line.text.length)
        assertTrue(line.text.endsWith("…"))
        assertEquals(text, logcat.single().text)
        assertSame(cause, logcat.single().cause)
    }

    @Test
    fun `异常镜像只带类型和消息 完整栈留在 logcat`() {
        val writer = writer()
        val collector = ConsoleCollector()
        val cause = IllegalStateException("桥 socket 名被占")
        writer.attach(collector).use { writer.e("T", "绑定失败", cause) }
        assertEquals("T: 绑定失败（IllegalStateException: 桥 socket 名被占）", collector.drain(0).second.single().text)
        assertSame(cause, logcat.single().cause)
        assertFalse(collector.drain(0).second.single().text.contains("HostLogTest.kt"))
    }

    @Test
    fun `异常无 message 时仍保留类型`() {
        val writer = writer()
        val collector = ConsoleCollector()
        writer.attach(collector).use { writer.w("T", "失败", IllegalStateException()) }
        assertEquals("T: 失败（IllegalStateException）", collector.drain(0).second.single().text)
    }

    @Test
    fun `换壳后旧连接关闭不摘新连接 摘线后继续缓冲`() {
        val writer = writer()
        val old = ConsoleCollector()
        val latest = ConsoleCollector()
        val oldConnection = writer.attach(old)
        writer.i("T", "旧壳")
        val latestConnection = writer.attach(latest)
        oldConnection.close()
        oldConnection.close()
        writer.i("T", "新壳")
        assertEquals(listOf("T: 旧壳"), old.drain(0).second.map { it.text })
        assertEquals(listOf("T: 新壳"), latest.drain(0).second.map { it.text })

        latestConnection.close()
        latestConnection.close()
        writer.w("T", "摘线期间")
        val replacement = ConsoleCollector()
        writer.attach(replacement).close()
        assertEquals(listOf("T: 摘线期间"), replacement.drain(0).second.map { it.text })
        assertEquals(1, latest.size())
    }

    @Test
    fun `重复接线不重复回放与丢弃报告`() {
        val writer = writer(capacity = 1)
        writer.i("T", "已淘汰")
        writer.i("T", "保留")
        val collector = ConsoleCollector()
        val first = writer.attach(collector)
        val second = writer.attach(collector)
        first.close()
        writer.i("T", "新行")
        second.close()
        val lines = collector.drain(0).second
        assertEquals(3, lines.size)
        assertEquals(1, lines.count { it.level == "warn" })
        assertEquals(listOf("T: 保留", "T: 新行"), lines.filter { it.level == "info" }.map { it.text })
    }

    @Test
    fun `镜像失败不改变原日志调用的完成`() {
        val writer = HostLogWriter(
            logcat = { level, tag, text, cause -> logcat.add(Cat(level, tag, text, cause)) },
            nowMillis = { error("镜像时钟失败") },
        )
        writer.i("T", "日志仍能返回")
        assertEquals("日志仍能返回", logcat.single().text)
    }

    @Test
    fun `非正缓冲容量不接受`() {
        assertThrows(IllegalArgumentException::class.java) { writer(capacity = 0) }
        assertThrows(IllegalArgumentException::class.java) { writer(capacity = -1) }
    }
}
