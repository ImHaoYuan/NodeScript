package com.autoscript.platform.system

import com.autoscript.domain.core.AutojsException
import com.autoscript.domain.core.ErrorCode
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeoutOrNull
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.io.ByteArrayInputStream
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

/**
 * `shell` Android 实现的契约测试（docs §9.6）：超时、收尸、双流、argv 形态。
 *
 * 用**真** `Process` 替身（[FakeProcess]）而不是 mock 框架：这里要验的正是
 * "管道写满会不会死锁""超时到底杀没杀进程"这类与真实 IO 语义绑定的事，
 * mock 会把它们正好抽象掉。
 *
 * **替身的强度决定测试的强度**（2026-10-01 的教训，见 `docs/backlog.md` A2）：
 * 原先两条读流都是 `ByteArrayInputStream`（**立刻 EOF**），于是"读流不可取消会把
 * 超时路径挂死"这条真机病灶在测试里根本不存在 —— 四条超时/取消用例全绿，
 * 而真机上 `su -c` 把管道写端交给孙进程时是「设 1s 超时、实际卡 100s 且没杀」。
 * 现在用 [HeldStream]（吐完字节后永不 EOF，模拟写端被孙进程持有）复现该形态。
 */
class AndroidShellExecutorTest {

    // ── argv 形态（§9.6 三通道）─────────────────────────────────────

    @Test
    fun `三种模式的 argv 形态：ROOT 走 su，其余走 sh -c`() {
        val seen = ArrayList<Array<String>>()
        val exec = AndroidShellExecutor(record(seen))
        runBlocking {
            exec.exec("echo hi", ShellMode.DEFAULT, 5_000)
            exec.exec("echo hi", ShellMode.ADB, 5_000)
            exec.exec("id", ShellMode.ROOT, 5_000)
        }
        assertEquals(listOf("sh", "-c", "echo hi"), seen[0].toList())
        assertEquals(listOf("sh", "-c", "echo hi"), seen[1].toList())
        assertEquals(listOf("su", "-c", "id"), seen[2].toList())
    }

    @Test
    fun `命令里的引号与美元符原样进 argv，不经过第二层拼接`() {
        val seen = ArrayList<Array<String>>()
        val cmd = "echo \"\$HOME\" && echo 'a b'"
        runBlocking { AndroidShellExecutor(record(seen)).exec(cmd, ShellMode.DEFAULT, 5_000) }
        // 命令作为**单个** argv 元素：拼接转义的问题不存在，内核直接收参数。
        assertEquals(3, seen[0].size)
        assertEquals(cmd, seen[0][2])
    }

    // ── 结果语义 ────────────────────────────────────────────────────

    @Test
    fun `成功路径：退出码与双流原样回，尾换行不裁剪`() {
        val proc = FakeProcess(exitCode = 0, stdout = "out\n".toByteArray(), stderr = "".toByteArray())
        val r = runBlocking { AndroidShellExecutor({ proc }).exec("x", ShellMode.DEFAULT, 5_000) }
        assertEquals(0, r.code)
        assertEquals("out\n", r.stdout)   // 不 trim：尾换行是有信息的字节
        assertNull(r.stderr)              // 零字节 = 该流没产出（≠ 空串）
        assertTrue(r.isSuccess)
    }

    @Test
    fun `非零退出码不是异常：原样交给 JS 侧判`() {
        val proc = FakeProcess(exitCode = 127, stdout = ByteArray(0), stderr = "not found\n".toByteArray())
        val r = runBlocking { AndroidShellExecutor({ proc }).exec("nope", ShellMode.DEFAULT, 5_000) }
        assertEquals(127, r.code)
        assertNull(r.stdout)
        assertEquals("not found\n", r.stderr)
        assertTrue(!r.isSuccess)
    }

    // ── 超时与收尸（铁律 3）─────────────────────────────────────────

    @Test
    fun `超时抛 ERR_TIMEOUT 且进程被强杀`() {
        val proc = FakeProcess(exitCode = 0, hangMillis = 60_000)
        val e = runBlocking {
            runCatching { AndroidShellExecutor({ proc }).exec("sleep 60", ShellMode.DEFAULT, 150) }.exceptionOrNull()
        }
        val err = e as? AutojsException ?: error("期望 AutojsException，实际 $e")
        assertEquals(ErrorCode.ERR_TIMEOUT, err.error)
        assertTrue(proc.destroyed.get(), "超时路径必须销毁子进程（否则留下孤儿 su/sh）")
    }

    @Test
    fun `上层取消（桥 TTL 到点）也收尸`() = runBlocking {
        val proc = FakeProcess(exitCode = 0, hangMillis = 60_000)
        val exec = AndroidShellExecutor({ proc })
        // launch(Dispatchers.Default)：runBlocking 的单线程接下来要被 [FakeProcess.waited].await
        // 占住，协程若排在同一条线程上就永远轮不到它跑（那就变成"测了个没启动的任务"）。
        val job = launch(Dispatchers.Default) { exec.exec("sleep 60", ShellMode.DEFAULT, 60_000) }
        // 等它真的进到 waitFor，再取消 —— 否则测的是"还没起进程就取消"。
        assertTrue(proc.waited.await(5, TimeUnit.SECONDS), "exec 没进到 waitFor")
        job.cancel()
        withTimeoutOrNull(5_000) { job.join() }
        assertTrue(proc.destroyed.get(), "取消路径必须销毁子进程")
    }

    @Test
    fun `进程起不来（su 不存在）折成 ERR_SERVICE_DISABLED`() {
        val exec = AndroidShellExecutor { throw IOException("Cannot run program \"su\"") }
        val e = runBlocking {
            runCatching { exec.exec("id", ShellMode.ROOT, 5_000) }.exceptionOrNull()
        }
        val err = e as? AutojsException ?: error("期望 AutojsException，实际 $e")
        assertEquals(ErrorCode.ERR_SERVICE_DISABLED, err.error)
    }

    @Test
    fun `非正超时被构造期拒绝（铁律 3：不允许无限等待）`() {
        val exec = AndroidShellExecutor(record(ArrayList()))
        val e = runBlocking { runCatching { exec.exec("x", ShellMode.DEFAULT, 0) }.exceptionOrNull() }
        assertTrue(e is IllegalArgumentException, "0 超时应当场拒绝，实际 $e")
    }

    // ── 读流不可取消（2026-10-01 回归：写端被孙进程持有）─────────────
    // 病灶：超时/取消抛出后 coroutineScope 要等两条 readBytes 子协程结束才传播，
    // 而 destroyForcibly() 在那个作用域**外**的 finally 里 —— 读流不 EOF 就永远
    // 等不到，进程也没杀。下面三条用 [HeldStream] 把那个形态钉住。

    @Test
    fun `超时：写端被孙进程持有（读流永不 EOF）也立刻返回并强杀`() {
        val held = HeldStream("partial".toByteArray())
        val proc = FakeProcess(exitCode = 0, hangMillis = 60_000, outStream = held, errStream = HeldStream(ByteArray(0)))
        try {
            val r = runBlocking {
                withDeadline(5_000) { AndroidShellExecutor({ proc }).exec("su -c 'sleep 100'", ShellMode.ROOT, 150) }
            }
            val result = r ?: error("exec 没在 5s 内返回：读流不 EOF 把超时路径挂死了")
            val err = result.exceptionOrNull() as? AutojsException ?: error("期望 AutojsException，实际 $result")
            assertEquals(ErrorCode.ERR_TIMEOUT, err.error)
            assertTrue(proc.destroyed.get(), "超时路径必须强杀子进程（`su -c` 留下的是孤儿 su）")
        } finally {
            held.release()
        }
    }

    @Test
    fun `取消：写端被孙进程持有（读流永不 EOF）也立刻收尸返回`() {
        val held = HeldStream(ByteArray(0))
        val proc = FakeProcess(exitCode = 0, hangMillis = 60_000, outStream = held, errStream = HeldStream(ByteArray(0)))
        try {
            val exec = AndroidShellExecutor({ proc })
            // 独立 scope：泄漏的读线程不能挂在 runBlocking 的作业树上，否则测试自己也跟着挂。
            val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
            try {
                val job = scope.launch { runCatching { exec.exec("sleep 100", ShellMode.DEFAULT, 60_000) } }
                assertTrue(proc.waited.await(5, TimeUnit.SECONDS), "exec 没进到 waitFor")
                job.cancel()
                val joined = runBlocking { withTimeoutOrNull(5_000) { job.join() } }
                assertNotNull(joined, "取消后 exec 没在 5s 内返回：读流不 EOF 把取消路径挂死了")
                assertTrue(proc.destroyed.get(), "取消路径必须销毁子进程")
            } finally {
                scope.cancel()
            }
        } finally {
            held.release()
        }
    }

    @Test
    fun `成功路径：进程已退出但写端被孙进程持有，宽限到点带着已读字节返回`() {
        val held = HeldStream("done\n".toByteArray())
        val proc = FakeProcess(exitCode = 0, outStream = held, errStream = HeldStream(ByteArray(0)))
        try {
            val t0 = System.nanoTime()
            // 同样走 withDeadline：修复前这条会在 `out.await()` 上永久挂住，
            // 用 runBlocking 直写的话"挂住"就等于把整个测试 JVM 拖死，而不是给出一条红断言。
            val r = runBlocking {
                withDeadline(5_000) { AndroidShellExecutor({ proc }).exec("echo done &", ShellMode.DEFAULT, 5_000) }
            }
            val ms = (System.nanoTime() - t0) / 1_000_000
            val result = r?.getOrNull() ?: error("exec 没在 5s 内返回：读流永不 EOF 把成功路径也挂死了")
            assertEquals(0, result.code)
            assertEquals("done\n", result.stdout)   // 已读到的字节照常交回，不丢
            assertNull(result.stderr)
            assertTrue(ms < 3_000, "宽限必须有上界（铁律 3：不允许无限等待），实际 ${ms}ms")
        } finally {
            held.release()
        }
    }

    // ── 双流并发读干（死锁回归）─────────────────────────────────────

    @Test
    fun `stdout 超过管道缓冲区也不会死锁`() {
        // 256KB > Linux 管道默认 64KB：串行读（先 stdout 读干再读 stderr）会在这里挂住。
        val big = ByteArray(256 * 1024) { 'x'.code.toByte() }
        val proc = FakeProcess(exitCode = 0, stdout = big, stderr = "err".toByteArray())
        val r = runBlocking { AndroidShellExecutor({ proc }).exec("cat big", ShellMode.DEFAULT, 30_000) }
        assertEquals(big.size, r.stdout!!.length)
        assertEquals("err", r.stderr)
    }

    // ── 工具 ────────────────────────────────────────────────────────

    /**
     * 在**独立 scope** 里跑 [block]，最多等 [millis]；没返回就回 null。
     *
     * 不用裸 `runBlocking { withTimeoutOrNull { … } }`：runBlocking 收尾时会等自己的
     * 全部子协程，而被挂住的读流恰恰是不可取消的子协程 —— 那么"卡住"就表现为整个
     * 测试进程挂死，而不是一条红色断言（这正是修复前那条病灶的形状）。
     * 这里的 `scope.cancel()` 只标记取消、不 join，卡住的作业泄漏成守护线程后测试照常收场。
     */
    private suspend fun <T> withDeadline(millis: Long, block: suspend CoroutineScope.() -> T): Result<T>? {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        return try {
            val job = scope.async { runCatching { block() } }
            withTimeoutOrNull(millis) { job.await() }
        } finally {
            scope.cancel()
        }
    }

    /** 只回一次 argv 记录的空进程（无输出、立即退出）。 */
    private fun record(seen: MutableList<Array<String>>): AndroidShellExecutor.ProcessLauncher =
        AndroidShellExecutor.ProcessLauncher { argv ->
            seen += argv
            FakeProcess(exitCode = 0)
        }

    /**
     * 真 `Process` 的最小替身：可控退出码、可控双流、可控"卡住不退出"。
     * [hangMillis] > 0 时 [waitFor] 会一直阻塞（除非被中断/销毁），用来验超时与取消。
     */
    private class FakeProcess(
        private val exitCode: Int,
        stdout: ByteArray = ByteArray(0),
        stderr: ByteArray = ByteArray(0),
        private val hangMillis: Long = 0,
        outStream: InputStream = ByteArrayInputStream(stdout),
        errStream: InputStream = ByteArrayInputStream(stderr),
    ) : Process() {

        val destroyed = AtomicBoolean(false)
        val waited = CountDownLatch(1)

        private val out: InputStream = outStream
        private val err: InputStream = errStream

        override fun getInputStream(): InputStream = out
        override fun getErrorStream(): InputStream = err
        override fun getOutputStream(): OutputStream = OutputStream.nullOutputStream()

        override fun waitFor(): Int {
            waited.countDown()
            if (hangMillis > 0) CountDownLatch(1).await(hangMillis, TimeUnit.MILLISECONDS)
            return exitCode
        }

        override fun waitFor(timeout: Long, unit: TimeUnit): Boolean {
            waited.countDown()
            if (hangMillis <= 0) return true
            // 真进程语义：到点没退出就回 false（且**不**自己退出）。
            CountDownLatch(1).await(timeout, unit)
            return false
        }

        override fun exitValue(): Int = exitCode
        override fun isAlive(): Boolean = hangMillis > 0 && !destroyed.get()
        override fun destroy() { destroyed.set(true) }
        override fun destroyForcibly(): Process {
            destroyed.set(true)
            return this
        }
    }

    /**
     * **不 EOF 的管道**：吐完 [payload] 就阻塞，直到 [release]。
     *
     * 模拟"写端被孙进程继承"（`su -c …` / `sh -c "… &"`）：直接子进程被杀掉也换不来
     * EOF，因为写端还握在孙进程手里 —— 真机上这就是那个"超时形同虚设"的现场。
     * 阻塞不响应中断：真管道的阻塞读就是这样（这也正是不能用协程取消来收场的理由）。
     */
    private class HeldStream(private val payload: ByteArray) : InputStream() {

        private val release = CountDownLatch(1)
        private var pos = 0

        fun release() = release.countDown()

        override fun read(): Int {
            if (pos < payload.size) return payload[pos++].toInt() and 0xff
            blockUntilReleased()
            return -1
        }

        override fun read(b: ByteArray, off: Int, len: Int): Int {
            if (len == 0) return 0
            if (pos < payload.size) {
                val n = minOf(len, payload.size - pos)
                System.arraycopy(payload, pos, b, off, n)
                pos += n
                return n
            }
            blockUntilReleased()
            return -1
        }

        private fun blockUntilReleased() {
            while (true) {
                try {
                    if (release.await(1, TimeUnit.SECONDS)) return
                } catch (_: InterruptedException) {
                    // 真管道的阻塞读不因中断而返回：这里也照旧等下去。
                }
            }
        }
    }
}
