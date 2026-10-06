package com.autoscript.engine.nodeprocess

import com.autoscript.domain.core.AutojsException
import com.autoscript.domain.core.ErrorCode
import com.autoscript.domain.engine.EngineId
import com.autoscript.domain.engine.EngineRunRequest
import com.autoscript.domain.engine.EngineStatus
import com.autoscript.domain.engine.KillCause
import com.autoscript.domain.engine.RunSummary
import com.autoscript.domain.engine.StopResult
import java.nio.file.Files
import java.nio.file.Path
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir

/**
 * **真起进程**的集成测试（`ProcessBuilderLauncher` + PATH 上的 `node`，与 `SocketE2EHostTest`
 * 同一环境假设：本机与 CI runner 都有 node）。钉死的是假缝钉不了的三件事：
 * 1. 真 pid（>0、≠自身）随 receipt 出，退出后 `pid` 回 null（§8.4 口径）；
 * 2. 真 SIGTERM → [StopResult.Clean] → 状态 STOPPED；真 SIGKILL → 状态 CRASHED（不看退出码）；
 * 3. 真退出码 → STOPPED(exit 0)/CRASHED(exit≠0) 的状态推导在真实 wait 语义下成立。
 *
 * 桥/socket **不在本测范围**（离线 spawn：不注入 HOST_SOCKET，脚本不连宿主）——
 * 桥链的本地验证归 `:app` 的 e2e（assemble→trigger→spawn→结算 全栈）。
 */
class NodeProcessEngineRealSpawnTest {

    @TempDir
    lateinit var dir: Path

    private val files: Path get() = dir.resolve("files")

    private fun writeScript(body: String): Path {
        val p = files.resolve("scripts").resolve("p1").resolve("s.js")
        Files.createDirectories(p.parent)
        Files.write(p, body.toByteArray())
        return p
    }

    private fun engine(grace: Long = 5_000) = NodeProcessEngine(
        EngineId(0),
        NodeEngineConfig(filesDir = files, hostBinary = Path.of("node"), stopGraceMillis = grace),
    )

    private fun request() = EngineRunRequest(projectId = "p1", scriptPath = "s.js")

    /** 轮询到期望状态（真进程有启动/退出时延；超时回最后观测值，由断言面报真实状态）。 */
    private suspend fun awaitStatus(e: NodeProcessEngine, want: EngineStatus, timeoutMillis: Long = 15_000): EngineStatus {
        val deadline = System.currentTimeMillis() + timeoutMillis
        while (System.currentTimeMillis() < deadline) {
            val s = e.status()
            if (s == want) return s
            delay(25)
        }
        return e.status()
    }

    /**
     * 自身 pid（仅本桌面单测用）：`ProcessHandle` 同样**不在 Android 平台 API 表**里
     * （`api-versions.xml` 整类缺席，与 `Process.pid()` 同因，见 `ProcessLauncher.kt` KDoc），
     * 直接引用会被 lint 的 `NewApi` 抓 —— 反射取，拿不到回 null（断言仍成立但弱化）。
     */
    private fun selfPid(): Long? = try {
        val ph = Class.forName("java.lang.ProcessHandle")
        ph.getMethod("pid").invoke(ph.getMethod("current").invoke(null)) as Long
    } catch (_: ReflectiveOperationException) {
        null
    }

    @Test
    fun `真起 node——pid 快照为真，退出0 STOPPED 且 pid 回 null`() {
        writeScript("process.exit(0)")
        val e = engine()
        val receipt = runBlocking { e.execute(request()) }

        val self = selfPid()
        // 落成局部再判：receipt 是 :domain 类型，跨模块 public val 不给 smart cast。
        val childPid = receipt.pid
        assertTrue(childPid != null && childPid > 0, "receipt.pid 必须是真子进程 pid：${childPid}")
        assertNotEquals(self, childPid?.toLong(), "pid 绝不给自身（§8.4：会把看门狗引到杀主进程）")
        assertEquals(childPid, e.pid, "存活期 ScriptEngine.pid = 当前子进程 pid")

        val settled = runBlocking { awaitStatus(e, EngineStatus.STOPPED) }
        assertEquals(EngineStatus.STOPPED, settled, "自然退出 0 → STOPPED")
        assertNull(e.pid, "已退出 pid 回 null（§8.4：绝不留一个死 pid 让看门狗去采）")
    }

    @Test
    fun `真起 node——非零退出推导 CRASHED`() {
        writeScript("process.exit(3)")
        val e = engine()
        runBlocking { e.execute(request()) }
        val settled = runBlocking { awaitStatus(e, EngineStatus.CRASHED) }
        assertEquals(EngineStatus.CRASHED, settled, "自然退出 ≠0 → CRASHED")
    }

    /**
     * B11 主体：真 node 往 stderr 写病因后非零退出 —— 排水线程捕获的尾部必须能被
     * [ScriptEngine.lastRunSummary] 读到（假缝钉不了"真管道真排水真环形缓冲"这一条）。
     */
    @Test
    fun `真起 node——stderr 尾部被捕获，lastRunSummary 带退出码与病因`() {
        writeScript(
            "process.stderr.write('TypeError: boom is not a function\\n'); process.exit(3)",
        )
        val e = engine()
        runBlocking { e.execute(request()) }
        assertEquals(EngineStatus.CRASHED, runBlocking { awaitStatus(e, EngineStatus.CRASHED) })

        val summary = runBlocking { e.lastRunSummary() }
        assertEquals(3, summary?.exitCode, "退出码随摘要出来（真 wait 语义）")
        assertTrue(
            summary?.stderrTail?.contains("TypeError: boom is not a function") == true,
            "stderr 尾部被排水线程捕获（stdout 那条仍然读即弃）：${summary?.stderrTail}",
        )
    }

    /**
     * B11 边界：stdout 仍然读即弃 —— 只往 stdout 写、stderr 干净的崩溃，摘要里不得出现 stdout 内容
     * （`redirectErrorStream(false)` 的意义就在这条：合流会把 stdout 噪声当成病因）。
     */
    @Test
    fun `真起 node——stdout 仍读即弃，不进摘要`() {
        writeScript("process.stdout.write('NOISE-ON-STDOUT\\n'); process.exit(4)")
        val e = engine()
        runBlocking { e.execute(request()) }
        assertEquals(EngineStatus.CRASHED, runBlocking { awaitStatus(e, EngineStatus.CRASHED) })

        val summary = runBlocking { e.lastRunSummary() }
        assertEquals(4, summary?.exitCode)
        assertTrue(
            summary?.stderrTail?.contains("NOISE-ON-STDOUT") != true,
            "stdout 内容不得混进 stderr 摘要：${summary?.stderrTail}",
        )
    }

    @Test
    fun `真 SIGTERM——stop 回 Clean，状态 STOPPED`() {
        writeScript("setInterval(() => {}, 1000)")
        val e = engine()
        runBlocking { e.execute(request()) }
        assertEquals(EngineStatus.RUNNING, runBlocking { awaitStatus(e, EngineStatus.RUNNING, 5_000) })

        val result = runBlocking { e.stop() }
        assertEquals(StopResult.Clean, result, "真 node 收 SIGTERM 后在宽限期内退净 → Clean")
        assertEquals(EngineStatus.STOPPED, runBlocking { awaitStatus(e, EngineStatus.STOPPED, 5_000) })
        assertNull(e.pid)
    }

    @Test
    fun `真 SIGKILL——kill 后状态钉死 CRASHED，返回 REQUESTED`() {
        writeScript("setInterval(() => {}, 1000)")
        val e = engine()
        runBlocking { e.execute(request()) }
        assertEquals(EngineStatus.RUNNING, runBlocking { awaitStatus(e, EngineStatus.RUNNING, 5_000) })

        assertEquals(KillCause.REQUESTED, runBlocking { e.kill() }, "返回值只供诊断（归因在调用方 recycle(cause)）")
        // 强杀不看退出码（128+sig 因平台而异）——killRequested 钉死 CRASHED
        assertEquals(EngineStatus.CRASHED, runBlocking { awaitStatus(e, EngineStatus.CRASHED, 5_000) })
        assertNull(e.pid)
    }

    @Test
    fun `PATH 名宿主缺位——spawn IOException 折叠 ERR_IO 点名 cmd`() {
        writeScript("process.exit(0)")
        val e = NodeProcessEngine(
            EngineId(0),
            NodeEngineConfig(filesDir = files, hostBinary = Path.of("no-such-host-binary-xyz")),
        )
        val ex = assertThrows(AutojsException::class.java) { runBlocking { e.execute(request()) } }
        assertEquals(ErrorCode.ERR_IO, ex.error)
        assertTrue(ex.message!!.contains("no-such-host-binary-xyz"), "${ex.message}")
        assertEquals(EngineStatus.IDLE, runBlocking { e.status() }, "spawn 失败无进程 → 仍 IDLE")
    }
}
