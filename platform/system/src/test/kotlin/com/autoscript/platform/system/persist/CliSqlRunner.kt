package com.autoscript.platform.system.persist

import com.autoscript.domain.json.DomainJson
import java.nio.file.Path
import java.util.concurrent.TimeUnit

/**
 * [SqliteIntentStore.Runner] 的**本机**实现：把同一份 SQL 喂宿主 `sqlite3` CLI。
 *
 * **它为什么存在**（这是本轨最该被问的一件事）：`android.database.sqlite` 是 Android 桩，
 * 本机 JVM 跑不了，而「表形状对不对、两个部分唯一索引拦不拦得住、`changes()` 条件插入
 * 成不成立、`AUTOINCREMENT` 会不会复用 id」恰恰是**语义**，不是 Android 细节。仓里没有
 * Robolectric（也不该为这一条引进来），所以本机验证的唯一路子是：让 SQL 文本的生成
 * （[IntentStoreSql]）与语义层（[SqliteIntentStore]）都不碰 Android，本机拿**同一个
 * `sqlite3` 引擎**（宿主 3.46.1；Android 内置的是同一个 SQLite，版本略有差异）跑真语句。
 *
 * **它证什么、不证什么**（诚实边界，别把这份测试读成"真机已验证"）：
 * - 证：SQL 文本与语义层的全部行为（契约套件 `IntentStoreContract` 的每一条）在真 SQLite 上成立；
 * - **不证**：`AndroidSqliteRunner` 本身（`SQLiteOpenHelper` 的建库/升级路径、`rawQuery`
 *   游标取值、`PRAGMA synchronous=FULL` 的实际生效）—— 那半边只有真机能验，本轨未验。
 *
 * **协议**：一个脚本 = `PRAGMA` + `BEGIN` +（每 op：DML 逐条、query、标记行）+ `COMMIT`，
 * 喂 `sqlite3 -json -bail` 的 stdin。用 `-bail` 是必须的：约束冲突必须让**整个事务回滚**
 * 并且**后面一句都不许跑**，否则 `sealAndReopen` 的「封旧 + 开新」会在冲突后继续执行下去。
 * 标记行（[IntentStoreSql.MARKER]）用来切分各 op 的结果 —— CLI 只吐一个 JSON 数组，
 * 而零行的 SELECT 什么都不吐，边界只能显式写出来。
 *
 * 冲突分类靠退出码：`-bail` 下**致命 SQL 错误**（含唯一约束）退出码 1、stderr 带
 * `constraint failed`；**非致命运行时错误**（如除零）退出码是扩展错误码（如 1811）。
 * 两者分得很开，不靠错误文案猜。
 */
internal class CliSqlRunner(private val dbFile: Path) : SqliteIntentStore.Runner {

    override fun ddl(statements: List<String>) {
        runScript(statements, inTransaction = false)
    }

    override fun run(ops: List<SqlOp>): List<List<Map<String, Any?>>> {
        val body = ArrayList<String>(ops.size * 3 + 2)
        for (op in ops) {
            body += op.dml
            body += op.query
            body += MARKER_SQL
        }
        val out = runScript(body, inTransaction = true)
        // 输出形如 [ <op 结果…>, <标记>, <op 结果…>, <标记>, … ]：按标记切成每 op 一段。
        val groups = ArrayList<List<Map<String, Any?>>>()
        var current = ArrayList<Map<String, Any?>>()
        for (row in out) {
            if (row[MARKER_COLUMN] == IntentStoreSql.MARKER) {
                groups += current
                current = ArrayList()
            } else {
                current += row
            }
        }
        check(groups.size == ops.size) {
            "CLI 输出与 op 数对不上（${groups.size} vs ${ops.size}）—— 标记行丢了？"
        }
        return groups
    }

    override fun query(sql: String): List<Map<String, Any?>> = runScript(listOf(sql), inTransaction = false)

    private fun runScript(statements: List<String>, inTransaction: Boolean): List<Map<String, Any?>> {
        // 每条语句一个 argv：`sqlite3` 逐条处理，任一条出错即按 `-bail` 停下（剩下的 argv 不跑）。
        val argv = ArrayList<String>()
        argv += "sqlite3"
        argv += "-json"
        argv += "-bail"
        // 忙等重试（5s）：契约套件里有一条**多线程并发**用例，而 CLI 每次调用是一个独立
        // 进程 —— 没有这个 timeout 时并发的写者会直接拿到 `database is locked (5)`
        // （本机实测），那是**锁竞争**不是幂等锚点拒绝，用例会误判。真机侧是同一个
        // `SQLiteDatabase`（进程内串行化），不存在这条噪声。
        argv += "-cmd"
        argv += ".timeout 5000"
        argv += dbFile.toAbsolutePath().toString()
        argv += IntentStoreSql.OPEN_PRAGMAS
        if (inTransaction) argv += "BEGIN"
        argv += statements
        if (inTransaction) argv += "COMMIT"

        val proc = ProcessBuilder(argv).start()
        proc.outputStream.close()
        // stderr 在**独立线程**上排空：两条管道都读才不会在输出量大时互等（与
        // `ProcessBuilderLauncher` 的排水纪律同一条理由，只是这里体量小得多）。
        val errBuf = StringBuilder()
        val errDrain = Thread {
            proc.errorStream.readBytes().toString(Charsets.UTF_8).let { errBuf.append(it) }
        }
        errDrain.isDaemon = true
        errDrain.start()
        val stdout = proc.inputStream.readBytes().toString(Charsets.UTF_8)
        val done = proc.waitFor(60, TimeUnit.SECONDS)
        errDrain.join(5_000)
        val stderr = errBuf.toString()
        if (!done) {
            proc.destroyForcibly()
            error("sqlite3 超时未退出（$dbFile）")
        }
        if (proc.exitValue() != 0) {
            // 唯一约束（PRIMARY KEY / UNIQUE / NOT NULL）→ 本层的冲突类型；
            // 其余（库损坏、SQL 语法错、磁盘满）原样抛出 —— 它们是故障不是判决。
            if (stderr.contains("constraint failed")) {
                throw ConstraintViolationException(stderr.trim())
            }
            error("sqlite3 退出码 ${proc.exitValue()}：$stderr")
        }
        return parseJsonRows(stdout)
    }

    /**
     * `-json` 输出 → 行表。空输出 = 零行（不是错误，见 KDoc）。
     *
     * **要按顶层值逐个切**：一次调用里每个有结果的 SELECT 各吐一个 JSON 数组，**结果之间
     * 只有换行**；而多行的数组自己也是跨行 pretty-print 的（`[\n{…},\n{…}]`）——
     * 所以既不能整段当一个 JSON 解（"尾部多余字符"），也不能按行切（会把一个数组切两半）。
     * 这里按括号深度扫顶层值（字符串与转义内不计数），逐个 `DomainJson.decode`。
     */
    private fun parseJsonRows(text: String): List<Map<String, Any?>> =
        splitTopLevelJsonValues(text).flatMap { chunk ->
            val value = DomainJson.decode(chunk)
            val arr = value as? DomainJson.Value.Arr
                ?: error("sqlite3 -json 输出不是数组：$chunk")
            arr.items.map { item ->
                val obj = item as? DomainJson.Value.Obj ?: error("sqlite3 -json 行不是对象：$item")
                obj.fields.mapValues { (k, v) ->
                    when (v) {
                        is DomainJson.Value.S -> v.v
                        is DomainJson.Value.N -> v.raw.toLongOrNull()
                            ?: error("sqlite3 -json 列 $k 非整数（${v.raw}）")
                        DomainJson.Value.Null -> null
                        else -> error("sqlite3 -json 列 $k 值型越界")
                    }
                }
            }
        }

    /** 扫出顶层 JSON 值（括号深度归零即一个值；字符串与转义内的括号不计数）。 */
    private fun splitTopLevelJsonValues(text: String): List<String> {
        val out = ArrayList<String>()
        var depth = 0
        var inString = false
        var escaped = false
        var start = -1
        for ((i, c) in text.withIndex()) {
            if (inString) {
                when {
                    escaped -> escaped = false
                    c == '\\' -> escaped = true
                    c == '"' -> inString = false
                }
                continue
            }
            when (c) {
                '"' -> {
                    inString = true
                    if (depth == 0) start = i
                }
                '[', '{' -> {
                    if (depth == 0) start = i
                    depth++
                }
                ']', '}' -> {
                    depth--
                    if (depth == 0 && start >= 0) {
                        out += text.substring(start, i + 1)
                        start = -1
                    }
                }
            }
        }
        return out
    }

    override fun close() {
        // 无长驻连接：每次调用起一个进程（本机测试用，不是生产路径）。
    }

    private companion object {
        const val MARKER_COLUMN = "m"

        /** 标记语句的单一事实来源是 [IntentStoreSql.MARKER]（生产侧同一份）。 */
        val MARKER_SQL: String = "SELECT '${IntentStoreSql.MARKER}' AS $MARKER_COLUMN"
    }
}
