package com.autoscript.platform.system.device

/**
 * device 命名空间的契约 DTO/SPI（§9.3/§9.6/§12.2；`extras.ts` 的 Kotlin 对偶）。
 *
 * **来历**：原 `SystemHostContracts.kt`（shell/device/app/floating 四面合一份）在
 * 2026-10-01 的 D3「子包按命名空间对齐」里按面拆开，与实现/handler 同子包 ——
 * 契约随「仅 handler+impl 消费」判据迁自 `:domain` `SystemContracts.kt`（`:app`/`:ui`/
 * app-service 生产读面零引用；`DialogHost` 反例留 `:domain`，装配层 `PlatformWiring`
 * 生产参数面在读）。
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
