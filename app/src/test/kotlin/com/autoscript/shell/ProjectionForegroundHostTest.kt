package com.autoscript.shell

import com.autoscript.platform.capabilities.device.ForegroundLease
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * 投屏前台的**代际语义**（§9.2 / §8.7）—— 纯 JVM（`ProjectionForegroundHost` 不碰 Android）。
 *
 * 代际这套东西不是洁癖，每一条都对应一个现场能看到的故障：
 * - 没有代际时，旧会话的迟到 `stop()` 会把**新会话**的前台撤掉
 *   （表现："重开投屏后立刻显示已停止"，而用户明明没停过）；
 * - 没有"放弃"标记时，超时放弃的那次 `start()` 之后服务可能才真正起来
 *   （孤儿 FGS：通知栏挂着"正在投屏"却没有任何会话）；
 * - 等待者不带代际时，一次早已超时的调用会被**属于另一代**的确认唤醒。
 *
 * 真机行为（`startForeground` 真的成功/被拒）只能在设备上验；这里钉的是**判断**。
 */
class ProjectionForegroundHostTest {

    /** 每个用例独立起代际：`nextLease` 单调递增，用例之间不共享语义（只共享计数器）。 */
    private fun lease(): ForegroundLease = ProjectionForegroundHost.nextLease()

    @Test
    fun `确认当前代 —— 等待者被叫醒且代际记在册`() {
        val l = lease()
        val seen = mutableListOf<Boolean>()
        ProjectionForegroundHost.awaitConfirmation(l.generation) { seen += it }
        assertTrue(seen.isEmpty(), "服务还没确认：等待者必须挂着")
        assertTrue(ProjectionForegroundHost.confirm(l.generation))
        assertEquals(listOf(true), seen)
        assertTrue(ProjectionForegroundHost.running)
        ProjectionForegroundHost.stop(l.generation) {}
    }

    @Test
    fun `已经在前台的那一代 —— 登记等待者立刻放行`() {
        val l = lease()
        ProjectionForegroundHost.confirm(l.generation)
        val seen = mutableListOf<Boolean>()
        ProjectionForegroundHost.awaitConfirmation(l.generation) { seen += it }
        assertEquals(listOf(true), seen, "同一代重复等待要立刻放行（否则等的人一直挂到超时）")
        ProjectionForegroundHost.stop(l.generation) {}
    }

    @Test
    fun `旧代际的 stop 停不掉新代际 —— 重开投屏不会被上一次的迟到收口撤掉`() {
        val old = lease()
        val fresh = lease()
        ProjectionForegroundHost.confirm(fresh.generation)
        var stopped = false
        assertFalse(
            ProjectionForegroundHost.stop(old.generation) { stopped = true },
            "不是这一代：如实不动手",
        )
        assertFalse(stopped, "旧代际的收口**不许**碰新会话的前台服务")
        assertTrue(ProjectionForegroundHost.running, "新会话的前台必须还在")
        assertTrue(ProjectionForegroundHost.stop(fresh.generation) { stopped = true })
        assertTrue(stopped)
        assertFalse(ProjectionForegroundHost.running)
    }

    @Test
    fun `放弃后迟到的启动自己退场 —— 不留孤儿 FGS`() {
        val l = lease()
        ProjectionForegroundHost.abandon(l.generation)
        assertFalse(
            ProjectionForegroundHost.confirm(l.generation),
            "这一代已被放弃：服务迟到才起来时必须自行退场（否则通知栏挂着「正在投屏」却没有会话）",
        )
        assertFalse(ProjectionForegroundHost.running)
    }

    @Test
    fun `放弃会撤下等待者 —— 超时的那次不被后到的确认唤醒`() {
        val l = lease()
        val seen = mutableListOf<Boolean>()
        ProjectionForegroundHost.awaitConfirmation(l.generation) { seen += it }
        ProjectionForegroundHost.abandon(l.generation)
        ProjectionForegroundHost.confirm(l.generation)
        assertTrue(seen.isEmpty(), "已放弃的那一代的等待者不许再被叫醒")
    }

    @Test
    fun `撤下等待者 —— 属于另一代的确认叫不醒它`() {
        val l = lease()
        val seen = mutableListOf<Boolean>()
        ProjectionForegroundHost.awaitConfirmation(l.generation) { seen += it }
        ProjectionForegroundHost.cancelWait(l.generation)
        ProjectionForegroundHost.confirm(l.generation)
        assertTrue(seen.isEmpty(), "等待者已撤：一次早已超时的调用不许被后到的确认唤醒")
        ProjectionForegroundHost.stop(l.generation) {}
    }

    @Test
    fun `服务被销毁 —— 等确认的人如实拿到「没了」`() {
        val l = lease()
        val seen = mutableListOf<Boolean>()
        ProjectionForegroundHost.awaitConfirmation(l.generation) { seen += it }
        ProjectionForegroundHost.onServiceDestroyed()
        assertEquals(listOf(false), seen, "界面/服务没了要如实回 false，不能让调用方一直挂着")
        assertFalse(ProjectionForegroundHost.running)
    }

    @Test
    fun `停止后同一代再确认会被当成新前台 —— 状态不粘`() {
        val l = lease()
        ProjectionForegroundHost.confirm(l.generation)
        ProjectionForegroundHost.stop(l.generation) {}
        assertFalse(ProjectionForegroundHost.running)
        // 同一代再确认（服务侧可能重复收到启动请求）：如实接受，状态与事实一致。
        assertTrue(ProjectionForegroundHost.confirm(l.generation))
        assertTrue(ProjectionForegroundHost.running)
        ProjectionForegroundHost.stop(l.generation) {}
    }
}
