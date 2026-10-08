package com.autoscript.appservice.scheduler.persist

import com.autoscript.domain.scripts.IntentStore
import com.autoscript.domain.scripts.IntentStoreContract
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Path

/**
 * §8.5 契约套件打在 [JournalFileStore] 上 —— **与 `SqliteIntentStore` 跑的是同一组用例**
 * （`IntentStoreContract` 住 `:domain` 的 testFixtures，两个实现各继承一次）。
 *
 * 本类不做任何额外断言：它存在的意义就是「同一份规格跑两个实现」。
 * 存储引擎替换（§8.5 jsonl → SQLite）时，两个实现的行为差异只可能在这里现形。
 *
 * 环境门禁：无（纯 JVM + 本机文件系统）—— 本类在任何环境都必须真跑。
 */
class JournalFileStoreContractTest : IntentStoreContract() {

    @TempDir
    lateinit var dir: Path

    /** 每次都是**新实例**（契约要求）—— 同目录，因此等价于"重启后 replay"。 */
    override fun open(): IntentStore = JournalFileStore(dir)
}
