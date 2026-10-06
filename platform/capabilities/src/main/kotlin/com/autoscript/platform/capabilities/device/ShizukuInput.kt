package com.autoscript.platform.capabilities.device

import com.autoscript.domain.automation.InputChannel
import com.autoscript.domain.core.AutojsException
import com.autoscript.domain.core.ErrorCode
import java.io.InputStream
import java.util.concurrent.TimeUnit

/**
 * Shizuku（adb 通道）的设备面：**唯一的 `android.*` + `rikka.shizuku.*` 接触点**
 * （§9.3 三通道里的 adb 那条）。
 *
 * **为什么 adb 通道非得有它**：应用自己 fork 的 `sh` 身份仍是应用 uid，`input` 注不进事件；
 * 要让注入以 **shell uid** 发生，必须借一个由 adb 启动的服务进程 —— Shizuku 就是那个
 * 服务（用户装 Shizuku、用 `adb shell sh /sdcard/…/start.sh` 起它、再授权本应用）。
 *
 * **为什么本文件不 import `rikka.shizuku.*`**：那是**冻结文件**（`gradle/libs.versions.toml`）
 * 里的新依赖，且该依赖只在**真机**上有意义（JVM 单测跑在 mock android.jar 上，碰
 * `Shizuku` 静态初始化即炸）。所以与 `:app` 既有做法一致：**反射调用**，依赖声明成
 * `compileOnly`（编得过、不进 APK 的运行时必需面 —— 缺它只是本通道不可用，不是崩溃）。
 * 反射的失败面（类不在、方法签名变了）**全部折成 `ERR_PERMISSION_DENIED`**：
 * 「Shizuku 没装」与「Shizuku 版本不兼容」对用户是同一句话 —— 去装/去更新。
 *
 * **未真机验证**：设备道 2026-10-06 已裁（backlog B3/E3）。JVM 侧只能钉「Shizuku 缺席时
 * 如实拒绝」这条；真机上的「装好 Shizuku 后能不能注进去」尚无人跑过。
 */
object ShizukuInput {

    /** Shizuku 主类的全名（`dev.rikka.shizuku` 的公开入口）。 */
    private const val SHIZUKU_CLASS = "rikka.shizuku.Shizuku"

    /** 单条注入命令的超时（与 `ShellInputProvider.DEFAULT_TIMEOUT_MILLIS` 同量级）。 */
    const val DEFAULT_TIMEOUT_MILLIS = 5_000L

    /**
     * 跑一条命令，返回 `(exitCode, stderr)`。
     *
     * 失败一律抛 [AutojsException] `ERR_PERMISSION_DENIED`（`detail` 说清是哪一步）——
     * **不返回一个假的退出码**：调用方（`ShellInputProvider`）要靠退出码判「系统拒绝
     * 这次注入」，拿假码混进去会让「Shizuku 挂了」看起来像「这次点击被拒」。
     */
    fun run(command: String, timeoutMillis: Long = DEFAULT_TIMEOUT_MILLIS): Pair<Int, String?> {
        val shizuku = shizukuClass()
        val binder = step("Shizuku 服务未运行（binder 为 null）—— 请先启动 Shizuku 并授权本应用") {
            shizuku.getMethod("getBinder").invoke(null)
        }
        val service = step("Shizuku 服务接口不可用（IShizukuService 形状不符）") {
            val stub = Class.forName("moe.shizuku.server.IShizukuService\$Stub")
            stub.getMethod("asInterface", android.os.IBinder::class.java).invoke(null, binder)
        }
        val process = step("Shizuku newProcess 调用失败（服务版本不兼容？）") {
            service.javaClass
                .getMethod("newProcess", Array<String>::class.java, Array<String>::class.java, String::class.java)
                .invoke(service, arrayOf("sh", "-c", command), null, null)
        }
        return try {
            drain(process, timeoutMillis)
        } catch (e: InterruptedException) {
            Thread.currentThread().interrupt()
            throw denied("Shizuku 命令被中断", e)
        }
    }

    /**
     * 反射调用的一步：null 结果与反射异常都折成同一个「这一步不行」。
     *
     * 抽出来是为了让 [run] 读起来是一条直线（每一步一行），也把「哪一步失败了」的
     * 措辞收在一处 —— 用户看到的是一句话，不该是一串 `InvocationTargetException`。
     */
    private inline fun <T : Any> step(failure: String, call: () -> T?): T = try {
        call() ?: throw denied(failure, null)
    } catch (e: ReflectiveOperationException) {
        throw denied("$failure（反射失败：${e.javaClass.simpleName}）", e)
    }

    /**
     * 等远端进程结束并取退出码。
     *
     * **stderr 必须排空**：Shizuku 的远端进程同样是管道，不读满会反压住对端
     * （与 `:engine:node-process` 的排水线程同一条账）。这里只留尾部 4 KiB 用于报错 ——
     * 我们要的是「为什么失败」，不是完整输出。
     */
    private fun drain(process: Any, timeoutMillis: Long): Pair<Int, String?> {
        val err = process.javaClass.getMethod("getErrorStream").invoke(process) as? InputStream
        val tail = StringBuilder()
        val reader = err?.let { stream ->
            Thread({
                try {
                    stream.bufferedReader().useLines { lines ->
                        lines.forEach { line ->
                            if (tail.length < STDERR_TAIL_CHARS) tail.append(line).append('\n')
                        }
                    }
                } catch (_: Exception) {
                    // 排水线程的收尾异常不影响判定：退出码才是判据（同 §8.5 的排水纪律）
                }
            }, "shizuku-stderr").apply { isDaemon = true; start() }
        }
        val waitForTimeout = process.javaClass.getMethod("waitForTimeout", Long::class.java, TimeUnit::class.java)
        val finished = waitForTimeout.invoke(process, timeoutMillis, TimeUnit.MILLISECONDS) as Boolean
        if (!finished) {
            runCatching { process.javaClass.getMethod("destroy").invoke(process) }
            throw denied("Shizuku 命令超时 ${timeoutMillis}ms（已尝试终止）", null)
        }
        reader?.join(JOIN_MILLIS)
        val code = process.javaClass.getMethod("exitValue").invoke(process) as Int
        return code to tail.toString().ifBlank { null }
    }

    /**
     * 通道可用性探测（装配期用）：**装没装 + 服务活没活**。
     *
     * 两问都要：只问「类在不在」会把「装了但服务没启动」报成可用，用户拿到的错误
     * 就从「去启动 Shizuku」退化成「注入失败」（少了那一步该去哪做）。
     * 探测本身**不抛** —— 它是个问句，答案就是 true/false。
     */
    fun isAvailable(): Boolean {
        // 反射探测的失败面很宽（类不在 / 方法改名 / 静态初始化炸 / 权限）—— 一律"不可用"。
        // 这里是**唯一**允许 `catch (Throwable)` 的地方：它是问句不是执行路径，
        // 且失败的下游语义是确定的（通道不接线 → 调用方拿 ERR_PERMISSION_DENIED）。
        //
        // 两问都要：binder 拿得到（服务在）+ pingBinder 回 true（服务活着）。只问前者会把
        // 「装了但服务没启动」报成可用，用户拿到的错误就从「去启动 Shizuku」退化成「注入失败」。
        return try {
            val shizuku = shizukuClass()
            shizuku.getMethod("getBinder").invoke(null) != null &&
                (shizuku.getMethod("pingBinder").invoke(null) as? Boolean ?: false)
        } catch (_: Throwable) {
            false
        }
    }

    private fun shizukuClass(): Class<*> = try {
        Class.forName(SHIZUKU_CLASS)
    } catch (_: ClassNotFoundException) {
        throw denied("Shizuku 未安装（找不到 $SHIZUKU_CLASS）—— adb 输入通道需要 Shizuku", null)
    }

    private fun denied(detail: String, cause: Throwable?) = AutojsException(
        ErrorCode.ERR_PERMISSION_DENIED,
        "adb 输入通道不可用：$detail",
        cause,
    )

    private const val STDERR_TAIL_CHARS = 4 * 1024
    private const val JOIN_MILLIS = 500L

    /** 本通道的枚举值（装配层用它登记 `channels` 表）。 */
    val channel: InputChannel get() = InputChannel.ADB
}
