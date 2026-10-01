package com.autoscript.platform.system.floatingWindow

import com.autoscript.domain.bridge.HandleRef


/**
 * floatingWindow 命名空间的契约 DTO/SPI（§9.3/§9.6/§12.2；`extras.ts` 的 Kotlin 对偶）。
 *
 * **来历**：原 `SystemHostContracts.kt`（shell/device/app/floating 四面合一份）在
 * 2026-10-01 的 D3「子包按命名空间对齐」里按面拆开，与实现/handler 同子包 ——
 * 契约随「仅 handler+impl 消费」判据迁自 `:domain` `SystemContracts.kt`（`:app`/`:ui`/
 * app-service 生产读面零引用；`DialogHost` 反例留 `:domain`，装配层 `PlatformWiring`
 * 生产参数面在读）。
 *
 * **非法即拒**：构造期 `require`（负尺寸），handler 据此折叠
 * ERR_INVALID_PARAM，绝不把垃圾发往平台层。
 */

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
