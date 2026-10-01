package com.autoscript.shell

import com.autoscript.appservice.runtime.ProcessMonitor
import com.autoscript.domain.bridge.HandleRef
import com.autoscript.domain.engine.EngineId
import com.autoscript.domain.engine.EngineRunReceipt
import com.autoscript.domain.engine.EngineRunRequest
import com.autoscript.domain.engine.EngineStatus
import com.autoscript.domain.engine.KillCause
import com.autoscript.domain.engine.ScriptEngine
import com.autoscript.domain.engine.StopResult
import java.util.concurrent.atomic.AtomicLong

private val fakeRunIds = AtomicLong(1)

/** 测试替身引擎（对齐 runtime FakeEngine 语义；:app test 不可见其 test source，自备）。 */
class FakeEngineForDispatcher(
    override val id: EngineId,
    override val pid: Int? = null,
    /** 脚本执行体 run 起来后多久"自退出"（真机：脚本跑完宿主推 STOPPED）；null = 永不退出（悬挂）。 */
    @Volatile var autoExitAfterMillis: Long? = 50,
) : ScriptEngine {
    val executed = mutableListOf<EngineRunRequest>()
    /** 已分配的 EngineRunReceipt.runId（顺序 = execute 顺序）：供断言 engineRunId 关联。 */
    val receiptRunIds = mutableListOf<Long>()
    var stopResult: StopResult = StopResult.Clean
    var failOnExecute = false
    var killCalls = 0
    var statusToReturn: EngineStatus = EngineStatus.IDLE

    override suspend fun execute(run: EngineRunRequest): EngineRunReceipt {
        if (failOnExecute) throw IllegalStateException("fake boot failure")
        executed += run
        statusToReturn = EngineStatus.RUNNING
        val runId = fakeRunIds.getAndIncrement()
        val wait = autoExitAfterMillis
        if (wait != null) {
            // 真机语义：脚本跑完宿主推 STOPPED（daemon 线程，不阻塞测试结束）。
            Thread({
                try {
                    Thread.sleep(wait)
                } catch (_: InterruptedException) {
                    return@Thread
                }
                if (statusToReturn == EngineStatus.RUNNING) statusToReturn = EngineStatus.STOPPED
            }, "fake-engine-autoexit-$runId").also { it.isDaemon = true }.start()
        }
        receiptRunIds += runId
        return EngineRunReceipt(runId = runId, handle = HandleRef(refId = runId, generation = 1))
    }

    override suspend fun stop(): StopResult {
        statusToReturn = when (stopResult) {
            StopResult.Clean -> EngineStatus.STOPPED
            else -> EngineStatus.QUIESCING
        }
        return stopResult
    }

    override suspend fun kill(): KillCause {
        killCalls++
        statusToReturn = EngineStatus.CRASHED
        return KillCause.REQUESTED
    }

    override suspend fun status(): EngineStatus = statusToReturn
}

/**
 * 假 `/proc`：把 [ProcessMonitor] 的两个读取缝换成固定文本（生产 = 真读 `/proc/<pid>/stat`）。
 *
 * **为什么 :app 的测试必须换掉**：本包里的假引擎都报 `pid = 4242`，而 `AppShell` /
 * `AppShellKit` 缺省注入**生产** `ProcessMonitor` —— 于是「这个 pid 是不是活的、RSS 多少」
 * 由**跑测试的那台机器**决定：本机没这个 pid → 采样回 null → 走"量不到"那支；CI runner 上
 * 4242 恰好是某个 Gradle/Java 守护进程（RSS 数百 MB 起）→ 越过 `rssHardLimitBytes`（512MB）
 * → 判 `Kill(OOM)`，看门狗真的去杀。同一个测试在两台机器上判两样事，**且红的那个更"真"**。
 * 裁决输入必须由测试自己钉死，不能借宿主环境的 /proc。
 *
 * 取值刻意落在 Healthy 那一支（首采样 CPU 0.0%、RSS 8MB 远低于 512MB 硬阈值）：判死本身
 * 由 `:app-service:runtime` 的 `ProcessMonitorTest` / `EngineWatchdogTest` 覆盖。
 */
fun fakeProcMonitor(): ProcessMonitor = ProcessMonitor(
    statReader = ProcessMonitor.statReaderOf(FAKE_PROC_STAT),
    statusReader = ProcessMonitor.statusReaderOf(FAKE_PROC_STATUS),
)

/** 假 `/proc/<pid>/stat`：字段补到 utime/stime（字段 14/15），给恒定的 100/0。 */
private const val FAKE_PROC_STAT =
    "4242 (node) R 1 4242 4242 0 -1 4194560 0 0 0 0 100 0 0 0 20 0 0 0 0 0 0 0 0 0 0 0 0 0 0 0 0"

private const val FAKE_PROC_STATUS = "Name:\tnode\nUmask:\t0022\nState:\tR\nVmRSS:\t8192 kB\nThreads:\t1\n"
