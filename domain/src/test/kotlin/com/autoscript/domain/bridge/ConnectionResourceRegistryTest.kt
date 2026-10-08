package com.autoscript.domain.bridge

import com.autoscript.domain.automation.registerConnectionResource
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * 连接级资源收口（§7.5 × §9.2）的判据。这套口子是"脚本崩了但投屏还挂着"那条漏的堵点，
 * 所以它的四条性质各有对应的失败形态：
 *
 * - **恰好一次**：收两次会把新会话的资源也拆了（收口不幂等 = 别人的会话被顺手停掉）；
 * - **撤销后登记立刻兑现**：`open` 进行中被撤销时，登记往往晚于撤销 ——
 *   不兑现就是孤儿资源（通知栏挂着"正在投屏"却没有任何脚本在跑）；
 * - **不抛**：一份资源还不掉不能让其余资源也留在半开态；
 * - **隔离**：两个连接各一份注册表，撤销一条不许碰另一条。
 */
class ConnectionResourceRegistryTest {

    @Test
    fun `撤销跑完登记的回调，恰好一次`() {
        val registry = ConnectionResourceRegistry()
        var calls = 0
        registry.register { calls++ }
        registry.revokeAll()
        assertEquals(1, calls)
        registry.revokeAll()
        assertEquals(1, calls, "重复撤销不许再跑一遍")
        assertEquals(0, registry.size())
    }

    @Test
    fun `显式 release 后连接撤销不再重复跑`() {
        val registry = ConnectionResourceRegistry()
        var calls = 0
        val handle = registry.register { calls++ }
        handle.release()
        assertEquals(1, calls)
        registry.revokeAll()
        assertEquals(1, calls)
    }

    @Test
    fun `撤销之后才登记 —— 立刻兑现，不留孤儿`() {
        val registry = ConnectionResourceRegistry()
        registry.revokeAll()
        var calls = 0
        registry.register { calls++ }
        assertEquals(1, calls, "连接已经没了，登记的资源必须当场收掉")
        assertEquals(0, registry.size())
    }

    @Test
    fun `回调抛异常不阻断其余收口`() {
        val errors = mutableListOf<Throwable>()
        val registry = ConnectionResourceRegistry { errors += it }
        var second = 0
        registry.register { error("收口炸了") }
        registry.register { second++ }
        registry.revokeAll()
        assertEquals(1, second, "一份资源还不掉，其余的仍必须收")
        assertEquals(1, errors.size)
        assertEquals(0, registry.size())
    }

    @Test
    fun `两份注册表互不影响 —— 撤销一条连接不动另一条`() {
        val a = ConnectionResourceRegistry()
        val b = ConnectionResourceRegistry()
        var aCalls = 0
        var bCalls = 0
        a.register { aCalls++ }
        b.register { bCalls++ }
        a.revokeAll()
        assertEquals(1, aCalls)
        assertEquals(0, bCalls, "撤销连接 A 不许动连接 B 的资源")
        assertFalse(b.size() == 0)
        b.revokeAll()
        assertEquals(1, bCalls)
        assertTrue(a.size() == 0 && b.size() == 0)
    }

    @Test
    fun `身份上下文默认带一个空注册表 —— 三参数构造照旧可用`() {
        val ctx = AuthenticatedRunContext(com.autoscript.domain.engine.EngineId(0), 1L, 1L)
        var calls = 0
        ctx.registerConnectionResource { calls++ }
        ctx.resources.revokeAll()
        assertEquals(1, calls)
    }
}
