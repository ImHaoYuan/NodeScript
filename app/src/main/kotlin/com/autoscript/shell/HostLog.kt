package com.autoscript.shell

import android.util.Log
import com.autoscript.bridge.ConsoleCollector
import java.util.ArrayDeque

/**
 * 进程级宿主日志口（A10②）：logcat 照写，另把宿主事件镜像进控制台（runId = 0）。
 *
 * Receiver / Service 由系统创建，Application 根包又不能直连桥，所以接线留在 shell 包。
 * 早于壳就绪的行暂存在有界缓冲；只有 Application.install 激活的壳才接管镜像，
 * 单纯 assemble（含失败的装配与 JVM 测试）不改变进程级去向。
 */
object HostLog {
    internal val writer = HostLogWriter(logcat = { level, tag, text, cause ->
        when (level) {
            "info" -> Log.i(tag, text)
            "warn" -> Log.w(tag, text, cause)
            else -> Log.e(tag, text, cause)
        }
    })

    fun i(tag: String, text: String) = writer.i(tag, text)
    fun w(tag: String, text: String, cause: Throwable? = null) = writer.w(tag, text, cause)
    fun e(tag: String, text: String, cause: Throwable? = null) = writer.e(tag, text, cause)
}

/**
 * 可 JVM 测的双写与接线：logcat 接触面由调用方给，不在测试里改全局可变回调。
 *
 * 缓冲有行数与单行长度两道界；溢出丢最老，接线时补一行明确的丢弃提示。
 * 回放保留事件原始时间，不把启动阶段的行全记成“装配完成时发生”。接线、写入、
 * 摘线共用一把锁，避免回放与实时写交错；旧壳关闭只摘自己的连接，不影响新壳。
 */
internal class HostLogWriter(
    private val logcat: (level: String, tag: String, text: String, cause: Throwable?) -> Unit,
    private val capacity: Int = ConsoleCollector.DEFAULT_CAPACITY,
    private val nowMillis: () -> Long = System::currentTimeMillis,
) {
    init {
        require(capacity > 0) { "capacity 必须 > 0" }
    }

    private data class PendingLine(val level: String, val text: String, val atMillis: Long)
    private class Connection(val collector: ConsoleCollector)

    private val lock = Any()
    private val pending = ArrayDeque<PendingLine>()
    private var dropped = 0L
    private var connection: Connection? = null

    /** 只给激活中的壳接线；返回的句柄属于该次连接，重复 close 幂等。 */
    fun attach(collector: ConsoleCollector): AutoCloseable = synchronized(lock) {
        val next = Connection(collector)
        while (pending.isNotEmpty()) {
            val line = pending.removeFirst()
            collector.append(0L, line.level, line.text, line.atMillis)
        }
        if (dropped > 0) {
            // 回放之后再写提示：两边容量相同时，先写提示会立刻被满缓冲挤掉。
            // 回放引起的收集器淘汰另由 droppedCount 记账，不与这里的启动期损失混算。
            collector.append(
                runId = 0L, level = "warn",
                text = "HostLog: 接线前宿主日志缓冲已丢弃 $dropped 行（容量 $capacity，丢最老）：日志有缺口",
                atMillis = nowMillis(),
            )
            dropped = 0L
        }
        connection = next
        AutoCloseable {
            synchronized(lock) {
                if (connection === next) connection = null
            }
        }
    }

    fun i(tag: String, text: String) = write("info", tag, text, null)
    fun w(tag: String, text: String, cause: Throwable? = null) = write("warn", tag, text, cause)
    fun e(tag: String, text: String, cause: Throwable? = null) = write("error", tag, text, cause)

    private fun write(level: String, tag: String, text: String, cause: Throwable?) {
        logcat(level, tag, text, cause)
        // 只隔离新增的诊断通道；不让镜像写失败改变原来的装配/闹钟/保活控制流。
        runCatching {
            val summary = buildString {
                append(tag).append(": ").append(text)
                if (cause != null) {
                    append("（").append(cause.javaClass.simpleName)
                    cause.message?.let { append(": ").append(it) }
                    append("）")
                }
            }
            val bounded = if (summary.length <= MAX_TEXT_CHARS) summary else summary.take(MAX_TEXT_CHARS - 1) + "…"
            synchronized(lock) {
                val atMillis = nowMillis()
                val target = connection?.collector
                if (target != null) {
                    target.append(0L, level, bounded, atMillis)
                } else {
                    if (pending.size == capacity) {
                        pending.removeFirst()
                        dropped++
                    }
                    pending.addLast(PendingLine(level, bounded, atMillis))
                }
            }
        }
    }

    companion object {
        /** 只截控制台镜像；logcat 仍保留原文与完整异常栈。 */
        internal const val MAX_TEXT_CHARS = 4_096
    }
}
