package com.autoscript.platform.system.shell

/**
 * shell 命名空间的契约 DTO/SPI（§9.3/§9.6/§12.2；`extras.ts` 的 Kotlin 对偶）。
 *
 * **来历**：原 `SystemHostContracts.kt`（shell/device/app/floating 四面合一份）在
 * 2026-10-01 的 D3「子包按命名空间对齐」里按面拆开，与实现/handler 同子包 ——
 * 契约随「仅 handler+impl 消费」判据迁自 `:domain` `SystemContracts.kt`（`:app`/`:ui`/
 * app-service 生产读面零引用；`DialogHost` 反例留 `:domain`，装配层 `PlatformWiring`
 * 生产参数面在读）。
 *
 * **非法即拒**：构造期 `require`（空命令/负超时由 handler 挡，本面只管结果形状），handler 据此折叠
 * ERR_INVALID_PARAM，绝不把垃圾发往平台层。
 */

// ── shell（§9.6）────────────────────────────────────────────────────

/** 执行通道（§9.6）：DEFAULT 普通 shell / ROOT 经 `su -c` / ADB（设备侧已在 adb shell 内）。 */
enum class ShellMode { DEFAULT, ROOT, ADB }

/**
 * shell 执行结果（与 JS `extras.ts` 的 `ShellResult` 逐字对齐：code/stdout/stderr）。
 * [stdout]/[stderr] 可空 = 该流没产出；绝不拿空串冒充「有输出但为空」。
 */
data class ShellResult(
    val code: Int,
    val stdout: String?,
    val stderr: String?,
) {
    val isSuccess: Boolean get() = code == 0
}

/**
 * shell 执行 SPI（§9.6）。实现住 `:platform:system`（`Runtime.exec("sh","-c",…)`
 * + 双流读干 + `waitFor(timeout)`）。
 *
 * 超时是**实现方契约**而非可选项：`child_process` 缺失的副作用（§10：Node 侧无 spawn）
 * 由本 SPI 在宿主侧兑现，所以超时必须由实现强制 —— 调用方只传建议值，
 * 实现不得无限等待（铁律 3：每次跨进程操作必有 TTL）。
 */
interface ShellExecutor {
    suspend fun exec(command: String, mode: ShellMode, timeoutMillis: Long): ShellResult
}
