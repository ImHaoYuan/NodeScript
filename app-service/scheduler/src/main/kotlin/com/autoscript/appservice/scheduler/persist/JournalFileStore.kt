package com.autoscript.appservice.scheduler.persist

import java.io.ByteArrayOutputStream
import java.io.IOException
import java.nio.ByteBuffer
import java.nio.channels.FileChannel
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardOpenOption
import java.util.concurrent.atomic.AtomicLong

/**
 * jsonl 追加式意图存储（**当前全平台的生产实现**，含 Android —— `AppShellKit` 装的就是它；
 * §8.5 写的 SQLite 目标形态为何不在这里，见 [IntentStore] 的说明）。
 *
 * 持久化形态：`<dir>/intent-log.jsonl`，每行一条 record：
 * - `{"op":"start","runId":N,...}` —— START 行
 * - `{"op":"seal","runId":N,"outcome":"NAME","detail":...,"at":M}` —— 终态行
 *
 * 崩溃持久纪律（§8.5「append-only + 启动即回放」）：
 * - 每次写后 `FileChannel.force(true)`（数据+元数据落盘）——代价已知：调度写放大，P0 可接受；
 * - 启动 replay 全量重建内存索引；**容忍最后一条半行**（写中断）——截断到行边界继续；
 * - runId 由 `max(已有 runId)+1` 分配（单调、崩溃后不复用）；
 * - 幂等锚点原子：单写者锁内查「存活 nonce 集合 / 已提交 nonce 集合」后写盘——
 *   与 SQLite 部分唯一索引等价的单进程内原子（多进程并发由 :main 单例调度器排除）。
 */
class JournalFileStore(private val dir: Path) : IntentStore {

    private val file: Path = dir.resolve("intent-log.jsonl")
    private val channel: FileChannel
    private val writeLock = Any()

    private val rows = sortedMapOf<Long, IntentStore.StoredRow>()          // runId → 行（replay 重建）
    private val liveNonce = HashMap<String, Long>()                         // 存活 nonce → runId
    private val committedNonce = HashMap<String, Long>()                    // 已真终态 nonce → runId
    private val nextId = AtomicLong(1)

    init {
        Files.createDirectories(dir)
        replay()
        channel = FileChannel.open(
            file,
            StandardOpenOption.CREATE, StandardOpenOption.WRITE, StandardOpenOption.APPEND,
        )
    }

    // —— replay：**流式**逐行读，截断容忍最后半行，重建索引 ——
    // 注：单一全局写锁（writeLock）保护的内存视图是查询真值；replay 只在构造期跑（尚无并发）。

    /**
     * 回放整个日志，把内存索引（行表 + 存活/已提交 nonce 表）折出来。
     *
     * **流式，不 `readAllBytes`**：日志按设计只追加、从不清理，一台常跑任务的机器上
     * 它会长到几十上百 MB —— 一次性读进堆等于"启动时按日志大小要内存"，而回放需要的
     * 只是行与行之间互相独立这一点。这里按块读、按 `\n` 切、逐行折叠，峰值内存与
     * 文件大小无关（只剩行缓冲 = 最肥的那一条 START 行）。
     *
     * 行边界先按**字节**找 `\n`、整行再 UTF-8 解码：UTF-8 多字节字符被读取块切开
     * 也不会解坏（`String(chunk)` 那种按块解码的写法会）。
     *
     * 尾部没有 `\n` 的半行丢弃（写中断留下的残行，§8.5「容忍最后一条半行」）——
     * 与写路径的 force 组口径一致：没落完的组等于没发生。
     */
    private fun replay() {
        if (!Files.exists(file)) return
        Files.newInputStream(file).use { input ->
            val chunk = ByteArray(REPLAY_CHUNK_BYTES)
            val line = ByteArrayOutputStream(256)
            while (true) {
                val n = input.read(chunk)
                if (n < 0) break
                var from = 0
                while (true) {
                    val nl = indexOfNewline(chunk, from, n)
                    if (nl < 0) {
                        line.write(chunk, from, n - from)   // 本块没有完整行尾：攒着
                        break
                    }
                    line.write(chunk, from, nl - from)
                    fold(String(line.toByteArray(), StandardCharsets.UTF_8))
                    line.reset()
                    from = nl + 1
                }
            }
            // 走到这里若 line 还有内容 = 尾部半行：**丢弃**（不 fold），与旧实现同口径。
        }
        // runId 单调：每个 Seal 指向的行必有先行的 Start，故行表的最大键就是已分配的最大 runId。
        // **空表要单独走**：`SortedMap.lastKey()` 是 Java 方法，空表上抛 NoSuchElementException
        // （不是返回 null）——「文件存在但一行完整行都没有」（刚建的空文件、或只有半行）是常态。
        nextId.set(if (rows.isEmpty()) 1L else rows.lastKey() + 1)
    }

    /** 折叠一行到内存索引（与写路径同构：Start 建行、Seal 封口；空白行跳过）。 */
    private fun fold(line: String) {
        if (line.isBlank()) return
        when (val rec = JournalCodec.parse(line)) {
            is Rec.Start -> {
                val row = rec.toStoredRow()
                rows[row.runId] = row
                liveNonce[row.start.runNonce] = row.runId
            }
            is Rec.Seal -> {
                val old = rows[rec.runId] ?: return
                val sealed = old.copy(outcome = rec.outcome, committedAtMillis = rec.atMillis)
                rows[rec.runId] = sealed
                liveNonce.remove(old.start.runNonce)
                if (rec.outcome.name != "INTERRUPTED") committedNonce[old.start.runNonce] = rec.runId
            }
        }
    }

    /** 在 `chunk[from, end)` 里找 `\n`；-1 = 本块内没有完整行尾。 */
    private fun indexOfNewline(chunk: ByteArray, from: Int, end: Int): Int {
        for (i in from until end) if (chunk[i] == '\n'.code.toByte()) return i
        return -1
    }

    // —— 写路径：单写者锁 + 追加 + fsync ——

    override fun insertStart(row: IntentStore.StartRow): Long = synchronized(writeLock) {
        if (row.runNonce in liveNonce) {
            throw IllegalStateException("runNonce 已有未完成的 STARTED 行，拒绝重复投递: ${row.runNonce}")
        }
        // 与 InMemoryIntentLog 对齐：已 COMMIT 的 nonce 同样拒绝直投（唯一合法重投路径是 reopen）。
        if (row.runNonce in committedNonce) {
            throw IllegalStateException("runNonce 已有 COMMIT，拒绝重复投递: ${row.runNonce}")
        }
        val runId = nextId.getAndIncrement()
        appendLine(JournalCodec.start(runId, row))
        rows[runId] = IntentStore.StoredRow(runId, row, null, null)
        liveNonce[row.runNonce] = runId
        runId
    }

    override fun seal(runId: Long, outcome: IntentStore.StoredOutcome): IntentStore.StoredRow? =
        synchronized(writeLock) {
            val old = rows[runId] ?: return null
            if (old.outcome != null) return old            // 幂等：已终态返回现态
            val nonce = old.start.runNonce
            if (outcome.name != "INTERRUPTED" && nonce in committedNonce) {
                throw IllegalStateException("runNonce 已 COMMIT，拒绝重复副作用: $nonce")
            }
            val at = System.currentTimeMillis()
            appendLine(JournalCodec.seal(runId, outcome, at))
            val sealed = old.copy(outcome = outcome, committedAtMillis = at)
            rows[runId] = sealed
            liveNonce.remove(nonce)
            if (outcome.name != "INTERRUPTED") committedNonce[nonce] = runId
            sealed
        }

    override fun sealAndReopen(oldRunId: Long): Long = synchronized(writeLock) {
        val old = rows[oldRunId] ?: throw IllegalArgumentException("旧 runId 不存在，无法重开: $oldRunId")
        require(old.outcome == null) { "旧意向已 COMMIT，不能重开: $oldRunId" }
        val now = System.currentTimeMillis()
        // 同一 force 组：两条行要么都落盘要么都不落（崩溃中间态 = 旧行仍存活，重放可重试）
        val newId = nextId.getAndIncrement()
        appendLine(
            JournalCodec.seal(oldRunId, IntentStore.StoredOutcome.INTERRUPTED, now) +
                JournalCodec.start(newId, old.start.copy(startedAtMillis = now)),
        )
        val sealed = old.copy(outcome = IntentStore.StoredOutcome.INTERRUPTED, committedAtMillis = now)
        rows[oldRunId] = sealed
        liveNonce.remove(old.start.runNonce)
        val fresh = IntentStore.StoredRow(newId, old.start.copy(startedAtMillis = now), null, null)
        rows[newId] = fresh
        liveNonce[old.start.runNonce] = newId
        newId
    }

    override fun liveRows(): List<IntentStore.StoredRow> = synchronized(writeLock) {
        rows.values.filter { it.outcome == null }
    }

    override fun allRows(): List<IntentStore.StoredRow> = synchronized(writeLock) { rows.values.toList() }

    override fun hasCommittedNonce(nonce: String): Boolean = synchronized(writeLock) { nonce in committedNonce }

    override fun hasLiveNonce(nonce: String): Boolean = synchronized(writeLock) { nonce in liveNonce }

    override fun close() {
        synchronized(writeLock) { channel.force(true); channel.close() }
    }

    private fun appendLine(s: String) {
        val buf = ByteBuffer.wrap(s.toByteArray(StandardCharsets.UTF_8))
        while (buf.hasRemaining()) channel.write(buf)
        channel.force(true)
    }

    // —— 行编解码（手写极简 JSON：字段集冻结、无嵌套数组；引号转义只出 \" 与 \\） ——

    internal sealed interface Rec {
        data class Start(val runId: Long, val row: IntentStore.StartRow) : Rec {
            fun toStoredRow() = IntentStore.StoredRow(runId, row, null, null)
        }

        data class Seal(val runId: Long, val outcome: IntentStore.StoredOutcome, val atMillis: Long) : Rec
    }

    internal object JournalCodec {

        fun start(runId: Long, r: IntentStore.StartRow): String = buildString {
            append("""{"op":"start","runId":""").append(runId)
            append(""","projectId":""").append(q(r.projectId))
            append(""","scriptPath":""").append(q(r.scriptPath))
            append(""","runNonce":""").append(q(r.runNonce))
            append(""","trigger":""").append(q(r.trigger))
            append(""","screen":""").append(q(r.screen))
            append(""","scheduledAt":""").append(r.scheduledAtMillis)
            append(""","startedAt":""").append(r.startedAtMillis)
            append(""","deadlineAt":""")
            if (r.deadlineMillis == null) append("null") else append(r.deadlineMillis)
            append(""","args":""").append(JsonLine.quoteAll(r.args))
            append(""","timeoutMillis":""")
            if (r.timeoutMillis == null) append("null") else append(r.timeoutMillis)
            append("}\n")
        }

        fun seal(runId: Long, o: IntentStore.StoredOutcome, at: Long): String = buildString {
            append("""{"op":"seal","runId":""").append(runId)
            append(""","outcome":""").append(q(o.name))
            append(""","detail":""")
            if (o.detail == null) append("null") else append(q(o.detail))
            append(""","at":""").append(at)
            append("}\n")
        }

        fun parse(line: String): Rec {
            val fields = JsonLine.parse(line)
            return when (fields["op"]) {
                "start" -> Rec.Start(
                    fields.long("runId"),
                    IntentStore.StartRow(
                        projectId = fields.str("projectId"),
                        scriptPath = fields.str("scriptPath"),
                        runNonce = fields.str("runNonce"),
                        trigger = fields.str("trigger"),
                        screen = fields.str("screen"),
                        scheduledAtMillis = fields.long("scheduledAt"),
                        startedAtMillis = fields.long("startedAt"),
                        deadlineMillis = fields.optLong("deadlineAt"),
                        args = fields.optStrList("args"),
                        timeoutMillis = fields.optLong("timeoutMillis"),
                    ),
                )
                "seal" -> Rec.Seal(
                    fields.long("runId"),
                    IntentStore.StoredOutcome(fields.str("outcome"), fields.optStr("detail")),
                    fields.long("at"),
                )
                else -> throw IOException("journal 行损坏（op=${fields["op"]}）")
            }
        }

        private fun q(s: String): String = JsonLine.quote(s)
        // 解析器见共享 [JsonLine]（同一冻结行格式，单测与生产同一份解析）。
    }

    private companion object {
        /** 回放读取块：足够摊薄 syscall，又不给启动路径加常数内存。 */
        const val REPLAY_CHUNK_BYTES = 64 * 1024
    }
}
