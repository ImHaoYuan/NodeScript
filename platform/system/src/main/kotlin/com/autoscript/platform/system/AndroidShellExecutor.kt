package com.autoscript.platform.system

import com.autoscript.domain.core.AutojsException
import com.autoscript.domain.core.ErrorCode
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runInterruptible
import kotlinx.coroutines.withContext
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.io.InputStream
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/**
 * `shell` 命名空间的 Android 实现（docs §9.6；SPI 见 [ShellExecutor]，
 * 语义层 handler 住 `:platform:capabilities` 的 `ShellNamespaceHandler`）。
 *
 * 三条契约在这里兑现，每条都有对应单测：
 *
 * 1. **超时是实现者义务**（铁律 3）：[ShellExecutor] 的 KDoc 明说「调用方只传建议值，
 *    实现不得无限等待」。所以超时到点就 `destroyForcibly()` 并抛
 *    [ErrorCode.ERR_TIMEOUT] —— 不是"返回已读到的半截输出"，更不是继续挂着。
 * 2. **任何退出路径都要收尸**：成功、超时、被上层取消（桥 TTL 到点会取消 handler 协程）
 *    三条路都走 `finally` 里的 `destroyForcibly()`。少这一条，脚本被杀之后
 *    `su`/`sh` 会变成孤儿进程留在设备上，而这在真机上只表现为"越跑越卡"，查不到源头。
 * 3. **双流必须并发读干**：管道缓冲区（Linux 默认 64KB）写满即阻塞子进程，
 *    先读干 stdout 再读 stderr 会在输出超过一屏时死锁 —— 所以两条读流在
 *    `waitFor` **之前**就起来了。
 *
 * **读流走独立线程，不是 `async` 子协程**（2026-10-01 修，见 `docs/backlog.md` A2）：
 * `InputStream.read` 阻塞期间**不响应协程取消**（协程取消只标记状态、线程中断也不保证
 * 唤醒它），而 `coroutineScope` 要等全部子协程结束才传播异常/取消 —— 于是超时抛出后
 * 卡在等两条读流上，`finally` 里的 `destroyForcibly()` 反而永远轮不到执行。
 * 真机形态就是「脚本设 1s 超时，实际卡 100s 且没杀掉 `su`」；子进程把管道写端交给
 * 孙进程时（`su -c …`、`sh -c "… &"`）必现 —— 那时杀掉直接子进程也换不来 EOF
 * （写端还握在孙进程手里）。现在读流跑在**可弃的守护线程**上（[PipeReader]），
 * 协程只 await 一个可中断的闩：超时/取消都能立刻返回，读线程随管道关闭自然结束，
 * 或在极端情况下作为孤立守护线程被丢弃（不占 `Dispatchers.IO` 的共享池）。
 *
 * 参数用 argv 数组而非拼串（`su -c "$command"` 那种）：命令里的引号/空格/`$` 一旦
 * 参与拼接就要靠转义猜，argv 形态让内核直接收参数，不经过第二层 shell 解析。
 *
 * **本类不做能力门禁**：ROOT 模式能不能用由装配层的 `PermissionFacade` 先判
 * （§9.5），本类只负责"已经决定要执行之后"的执行与收尸。`su` 不存在时
 * [launcher] 抛 [IOException]，这里如实折成 [ErrorCode.ERR_SERVICE_DISABLED]。
 */
class AndroidShellExecutor(
    private val launcher: ProcessLauncher = ProcessLauncher { Runtime.getRuntime().exec(it) },
) : ShellExecutor {

    /** 进程启动缝：真机走 [Runtime.exec]；单测注入以记录 argv / 供可控进程。 */
    fun interface ProcessLauncher {
        fun launch(argv: Array<String>): Process
    }

    override suspend fun exec(
        command: String,
        mode: ShellMode,
        timeoutMillis: Long,
    ): ShellResult {
        require(timeoutMillis > 0) { "shell 超时必须是正数（铁律 3：不允许无限等待），实际 $timeoutMillis" }
        val argv = argvFor(command, mode)
        return withContext(Dispatchers.IO) {
            val process = try {
                launcher.launch(argv)
            } catch (e: IOException) {
                throw AutojsException(
                    ErrorCode.ERR_SERVICE_DISABLED,
                    "shell 通道不可用（mode=$mode）：${e.message}",
                    e,
                )
            }
            val out = PipeReader(process.inputStream, "autoscript-shell-stdout").start()
            val err = PipeReader(process.errorStream, "autoscript-shell-stderr").start()
            try {
                // runInterruptible：桥 TTL 到点取消本协程时，阻塞中的 waitFor 才收得到中断
                //（否则取消只能等进程自己结束，"超时"就成了一句空话）。
                val finished = runInterruptible {
                    process.waitFor(timeoutMillis, TimeUnit.MILLISECONDS)
                }
                if (!finished) {
                    // 先杀再抛：抛出去之后本函数只剩 finally，中间没有任何顺序保证能证明
                    // "进程已强杀"这句话 —— 这里显式杀，也让读线程尽早拿到 EOF/重置。
                    process.destroyForcibly()
                    throw AutojsException(
                        ErrorCode.ERR_TIMEOUT,
                        "shell 超时 ${timeoutMillis}ms（进程已强杀）：$command",
                    )
                }
                // 进程已退出 ⇒ 写端关闭 ⇒ 两条读流应当**立刻** EOF（正常形态：剩余的
                // ≤64KB 还在内核管道缓冲里，读出来是微秒级的事）。宽限只兜一个例外：
                // 写端被孙进程继承（`sh -c "… &"`），此时输出不再属于这条命令，
                // 等到点就带着已读到的字节返回 —— 铁律 3 不允许为它无限等待。
                out.awaitEofOrGiveUp(DRAIN_GRACE_MILLIS)
                err.awaitEofOrGiveUp(DRAIN_GRACE_MILLIS)
                ShellResult(
                    code = process.exitValue(),
                    stdout = out.textOrNull(),
                    stderr = err.textOrNull(),
                )
            } finally {
                // 收尸兜底：超时/取消/异常路径都不留孤儿进程（见 KDoc 第 2 条）。
                if (process.isAlive) process.destroyForcibly()
            }
        }
    }

    /**
     * 单条管道的抽干器：**一条守护线程**把 [input] 读到 EOF 或读端失效，字节进内存缓冲。
     *
     * 为什么是裸线程而不是 `Dispatchers.IO` 上的协程（KDoc 顶部有完整病灶）：
     * 阻塞读不可取消，唯一可行的姿势是"跑在一条可以丢弃的线程上，协程只等一个可中断的闩"。
     * 用裸守护线程还有第二个好处 —— 被丢弃时不会占住 `Dispatchers.IO` 的共享工作线程
     * （那个池子是全 App 共用的，被管道读流占满会连累无关的业务）。
     */
    private class PipeReader(private val input: InputStream, name: String) {

        // ByteArrayOutputStream 的 write/toByteArray 自带同步：宽限到点后调用方拿到的是
        // "此刻已读到的字节"快照，不需要额外加锁。
        private val buffer = ByteArrayOutputStream(INITIAL_BUFFER_BYTES)
        private val eof = CountDownLatch(1)
        private val thread = Thread({ pump() }, name).apply { isDaemon = true }

        fun start(): PipeReader {
            thread.start()
            return this
        }

        private fun pump() {
            try {
                input.use {
                    val chunk = ByteArray(COPY_CHUNK_BYTES)
                    while (true) {
                        val n = it.read(chunk)
                        if (n < 0) break
                        if (n > 0) buffer.write(chunk, 0, n)
                    }
                }
            } catch (_: IOException) {
                // 进程被杀时读端被重置（EIO/"Stream closed"）是**正常收尾** —— 超时路径
                // 必然走到这里；已读到的字节照常交回。shell 面没有比这更细的分类可用。
            } finally {
                eof.countDown()
            }
        }

        /**
         * 等 EOF，最多等 [millis]，到点就放弃（带着已读到的字节继续，不抛错）。
         * 用 `runInterruptible` 包住：调用方协程被取消时能立刻返回，不再死等 ——
         * 这正是修复前那个"取消也挂住"的病灶在等待侧的对应修法。
         */
        suspend fun awaitEofOrGiveUp(millis: Long) {
            runInterruptible { eof.await(millis, TimeUnit.MILLISECONDS) }
        }

        fun textOrNull(): String? = buffer.toByteArray().toTextOrNull()
    }

    private companion object {
        /**
         * 进程退出后等读流收敛的宽限（见 [exec] 里的说明）：正常形态下用不到
         * （退出即 EOF），只为"写端被孙进程继承"这一种例外设上限 ——
         * 铁律 3 不允许无限等待，所以这里必须有数。
         */
        const val DRAIN_GRACE_MILLIS = 250L

        const val INITIAL_BUFFER_BYTES = 8 * 1024
        const val COPY_CHUNK_BYTES = 16 * 1024

        /**
         * 模式 → argv（§9.6 三通道）：
         * - [ShellMode.DEFAULT] / [ShellMode.ADB]：`sh -c <cmd>`（adb 通道下设备已身处 adb shell 内，
         *   不需要再套一层 adb 客户端）；
         * - [ShellMode.ROOT]：`su -c <cmd>` —— 命令作为**单个** argv 元素交给 su，由它转交自己的 sh。
         */
        fun argvFor(command: String, mode: ShellMode): Array<String> = when (mode) {
            ShellMode.DEFAULT, ShellMode.ADB -> arrayOf("sh", "-c", command)
            ShellMode.ROOT -> arrayOf("su", "-c", command)
        }

        /**
         * 字节 → 文本：**零字节即 null**（"该流没产出"，与 `ShellResult` 的可空语义对齐），
         * 非空则原样解码、**不裁剪**（尾换行是有信息的字节，裁掉是有损变换；
         * 要 `trim()` 是调用方的事）。非法 UTF-8 用替换字符而非抛异常 ——
         * shell 输出混二进制是常态，为它丢掉整条命令的结果不划算。
         */
        fun ByteArray.toTextOrNull(): String? =
            if (isEmpty()) null else toString(Charsets.UTF_8)
    }
}
