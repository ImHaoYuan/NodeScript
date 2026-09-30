package com.autoscript.platform.system

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * 契约锚定 + 校验测试（shell/device/floatingWindow 面；§9.3/§9.6，
 * `extras.ts` 的 Kotlin 对偶）—— 2026-09-30 审查步骤 6 自 `:domain`
 * `SystemContractsTest` 拆入：契约随「仅 handler+impl 消费」判据迁本模块，
 * 测试同批走（grep 判据见步骤 6 提交信息）。对话框两例留 `:domain`。
 *
 * 守两件事：
 * 1. **形状冻结**：DTO 字段与 JS facade 的返回体逐字对齐（`code/stdout/stderr`、
 *    悬浮窗 null 即 wrap content）—— 桥只透传，任何一侧改名都会在另一侧变成
 *    `undefined`，故在此锚死；
 * 2. **非法即拒**：构造期 `require` 覆盖空型号 / SDK 越界 / 负尺寸，
 *    handler 据此折叠 ERR_INVALID_PARAM，绝不把垃圾发往平台层。
 */
class SystemHostContractsTest {

    // ── shell ──────────────────────────────────────────────────────

    @Test
    fun `ShellResult 按退出码判成败，空流与空串是两回事`() {
        assertTrue(ShellResult(0, "", "").isSuccess)
        assertFalse(ShellResult(1, null, "boom").isSuccess)
        // null = 该流没产出（JS 侧 `stdout ?? null` 语义）；空串 = 有输出但为空。
        assertNull(ShellResult(0, null, null).stdout)
        assertEquals("", ShellResult(0, "", "").stdout)
        Unit
    }

    // ── device ─────────────────────────────────────────────────────

    @Test
    fun `DeviceProfile 拒绝空型号与非法 SDK`() {
        assertThrows(IllegalArgumentException::class.java) { DeviceProfile(model = "", sdkInt = 34) }
        assertThrows(IllegalArgumentException::class.java) { DeviceProfile(model = "Pixel", sdkInt = 0) }
        assertEquals(34, DeviceProfile("Pixel 8", 34).sdkInt)
        Unit
    }

    // ── floatingWindow ─────────────────────────────────────────────

    @Test
    fun `悬浮窗尺寸拒绝非正值，null 即 wrap content`() {
        assertThrows(IllegalArgumentException::class.java) { FloatingWindowSpec("t", -1, 100) }
        assertThrows(IllegalArgumentException::class.java) { FloatingWindowSpec("t", 0, 200) }
        assertThrows(IllegalArgumentException::class.java) { FloatingWindowSpec("t", 200, 0) }
        // null = wrap content（JS facade 不传 width/height 时的形态）；标题可空
        assertNull(FloatingWindowSpec.DEFAULT.title)
        assertNull(FloatingWindowSpec.DEFAULT.width)
        assertNull(FloatingWindowSpec.DEFAULT.height)
        // 单边固定、另一边 wrap content 合法（§9.4 常见形态）
        assertEquals(200, FloatingWindowSpec("t", 200, null).width)
        Unit
    }

    // ── shell 模式枚举面 ───────────────────────────────────────────

    @Test
    fun `ShellMode 枚举名与 JS facade 的字面量一致`() {
        // extras.ts 只有 exec（DEFAULT）与 shell(同一路径)；ROOT/ADB 是宿主侧扩展
        assertEquals(listOf("DEFAULT", "ROOT", "ADB"), ShellMode.entries.map { it.name })
        Unit
    }
}
