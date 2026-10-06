package com.autoscript.platform.system.device

/**
 * device 命名空间的契约 DTO/SPI（§9.3/§9.6/§12.2；`extras.ts` 的 Kotlin 对偶）。
 *
 * 沿革（原 `SystemHostContracts.kt` 四面合一份 → 按面拆开，契约随「仅 handler+impl 消费」
 * 判据迁自 `:domain`）见 `SystemNamespaces` 的类注释，此处不重复。
 *
 * **非法即拒**：构造期 `require`（空型号/SDK 越界），handler 据此折叠
 * ERR_INVALID_PARAM，绝不把垃圾发往平台层。
 */

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
