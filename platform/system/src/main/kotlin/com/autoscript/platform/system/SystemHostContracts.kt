package com.autoscript.platform.system

import com.autoscript.domain.bridge.HandleRef

/**
 * `shell` / `device` / `app` / `floatingWindow` 四个命名空间的契约 DTO/SPI
 * （§9.3/§9.6/§12.2；`extras.ts` 的 Kotlin 对偶）—— 2026-09-30 审查步骤 6 自
 * `:domain` `SystemContracts.kt` 拆入：handler 语义层与实现同批迁
 * `:platform:system`，契约随「仅 handler+impl 消费」判据随迁（`:app`/`:ui`/
 * app-service 生产读面零引用 —— grep 判定见步骤 6 提交信息；DialogHost 反例留
 * `:domain`，装配层 PlatformWiring 生产参数面在读）。
 *
 * **非法即拒**：构造期 `require`（空型号/SDK 越界/负尺寸），handler 据此折叠
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

// ── device（§9.6）───────────────────────────────────────────────────

/** 设备信息 P0 最小集（§12.3 `auto.device`）：型号 + SDK 版本，其余字段 P2。 */
data class DeviceProfile(
    val model: String,
    val sdkInt: Int,
) {
    init {
        require(model.isNotBlank()) { "device.model 不得为空" }
        require(sdkInt >= 1) { "device.sdkInt 必须 ≥ 1，实际 $sdkInt" }
    }
}

/** 设备信息 SPI（§9.6）。实现住 `:platform:system`（`Build.MODEL` / `Build.VERSION.SDK_INT`）。 */
interface DeviceInfoProvider {
    fun profile(): DeviceProfile
}

// ── app（§9.3/§12.2）────────────────────────────────────────────────

/**
 * 应用开关 SPI。实现住 `:platform:system`（PackageManager）。
 * - [launch] 回 false = 找不到/起不来（**不是异常**：JS facade 用 `=== true` 判成败，
 *   抛错会让 try/catch 策略退化成「只有崩了才算失败」）；
 * - [currentPackage] 回 null = 取不到前台包（无权限/无前台窗口），如实给 null 不给空串。
 */
interface AppLauncher {
    suspend fun launch(packageName: String): Boolean
    suspend fun currentPackage(): String?
}

// ── floatingWindow（§9.4）───────────────────────────────────────────

/**
 * 悬浮窗创建规格（§9.4）：标题 + 逻辑像素尺寸。
 * [width]/[height] 为 null = wrap content（宿主按内容测量）；非 null 时必须 > 0。
 */
data class FloatingWindowSpec(
    val title: String?,
    val width: Int?,
    val height: Int?,
) {
    init {
        require(width == null || width > 0) { "悬浮窗宽度必须 > 0 或 null（wrap content），实际 $width" }
        require(height == null || height > 0) { "悬浮窗高度必须 > 0 或 null（wrap content），实际 $height" }
    }

    companion object {
        /** 缺省规格：无标题 + 双向 wrap content（JS facade 不传 width/height 时的形态）。 */
        val DEFAULT = FloatingWindowSpec(title = null, width = null, height = null)
    }
}

/**
 * 悬浮窗宿主 SPI（§9.4）：`TYPE_ACCESSIBILITY_OVERLAY`（可信窗口易保持）+
 * `SYSTEM_ALERT_WINDOW` 回退由实现决定；窗口类型选择是平台细节，不进领域契约。
 *
 * 返回 [HandleRef]（§7.4）：句柄带 generation，跨代/已关闭 → ERR_STALE_HANDLE。
 * [close] 幂等（未知句柄由实现抛分类错误，handler 折叠，不静默成功）。
 */
interface FloatingWindowHost {
    suspend fun create(spec: FloatingWindowSpec): HandleRef
    suspend fun close(ref: HandleRef)
}
