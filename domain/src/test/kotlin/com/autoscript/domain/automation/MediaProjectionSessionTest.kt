package com.autoscript.domain.automation

import com.autoscript.domain.bridge.AuthenticatedRunContext
import com.autoscript.domain.core.AutojsException
import com.autoscript.domain.core.ErrorCode
import com.autoscript.domain.engine.EngineId
import com.autoscript.domain.permission.CapabilityMask
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotEquals
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows

/**
 * 投屏会话语义面的 `:domain` 判据（§9.2 安全面）—— 这里只验**契约**，
 * 不碰 Android：归属怎么取、错误怎么分类、帧怎么比。
 *
 * 三态身份是本文件的重点（集成验收补充明确要求**显式区分**）：
 * - **合法身份**：协程上下文里带着 [AuthenticatedRunContext] → 取到归属；
 * - **缺身份**：上下文里没有 → 如实 `ERR_PERMISSION_DENIED`，**绝不放行**；
 * - **越权身份**：有身份但**不是**会话主人 → 归属比较不等（判据在语义层/设备层用同一条）。
 */
class MediaProjectionSessionTest {

    private fun ctx(runId: Long, connection: Long = 1L) =
        AuthenticatedRunContext(EngineId(0), runId, connection, CapabilityMask.ALL)
    @Test
    fun `合法身份取到归属`() = runBlocking {
        val owner = withContext(ctx(runId = 42L, connection = 7L)) { mediaProjectionOwnerOrThrow() }
        assertEquals(MediaProjectionSessionOwner(EngineId(0), 42L, 7L), owner)
        Unit
    }

    @Test
    fun `缺身份如实 PERMISSION_DENIED 绝不放行`() {
        val e = assertThrows<AutojsException> { runBlocking { mediaProjectionOwnerOrThrow() } }
        assertEquals(ErrorCode.ERR_PERMISSION_DENIED, e.error)
    }

    @Test
    fun `越权身份与主人不等 —— 归属按三元组（含连接号）比`() {
        val owner = MediaProjectionSessionOwner(EngineId(0), 42L, 1L)
        assertEquals(owner, MediaProjectionSessionOwner(EngineId(0), 42L, 1L), "同一条连接 = 同一主人")
        assertNotEquals(owner, MediaProjectionSessionOwner(EngineId(0), 42L, 2L), "同 run 的另一条连接不是主人")
        assertNotEquals(owner, MediaProjectionSessionOwner(EngineId(0), 43L, 1L), "另一次执行不是主人")
        assertNotEquals(owner, MediaProjectionSessionOwner(EngineId(1), 42L, 1L), "另一个引擎槽不是主人")
    }

    @Test
    fun `失败分类映射到桥面错误码`() {
        assertEquals(
            ErrorCode.ERR_CAPTURE_DENIED,
            MediaProjectionOpenException(MediaProjectionOpenFailure.DENIED, "用户取消").toAutojsException().error,
        )
        assertEquals(
            ErrorCode.ERR_SERVICE_DISABLED,
            MediaProjectionOpenException(MediaProjectionOpenFailure.UNAVAILABLE, "没有投屏通道").toAutojsException().error,
        )
    }

    @Test
    fun `RawFrame 按内容相等 —— 像素一样就是同一帧`() {
        val a = RawFrame(byteArrayOf(1, 2, 3, 4), 1, 1)
        val b = RawFrame(byteArrayOf(1, 2, 3, 4), 1, 1)
        val c = RawFrame(byteArrayOf(1, 2, 3, 5), 1, 1)
        assertEquals(a, b)
        assertEquals(a.hashCode(), b.hashCode())
        assertNotEquals(a, c)
    }
}
