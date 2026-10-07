package com.autoscript.shell

import com.autoscript.appservice.scheduler.core.InMemoryIntentLog
import com.autoscript.appservice.scheduler.core.SchedulerProvider
import com.autoscript.appservice.scheduler.core.TriggerHandle
import com.autoscript.domain.bridge.BridgeRequest
import com.autoscript.domain.bridge.BridgeResponse
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertInstanceOf
import org.junit.jupiter.api.Test

/**
 * `workManager` 的**装配层恒挂载**验证（§12.2 接线现状表）——
 * 2026-09-30 审查步骤 6：handler 本体迁 `:app-service:scheduler` 后，挂载缝回到
 * `:app` 侧测（scheduler 模块见不到 `AppShell`/引擎假件，那半测不了）：
 * `AppShell.assemble` 不经注入束就把 `workManager` 挂上 Router，建任务即达。
 * handler 的方法面/错误分类测试随迁 `:app-service:scheduler`
 * （`WorkManagerNamespaceHandlerTest`）。
 */
class WorkManagerMountTest {

    private class NoopProvider : SchedulerProvider {
        override suspend fun registerTrigger(targetFireAtMillis: Long, taskId: String): TriggerHandle =
            TriggerHandle { }
        override suspend fun cancelTrigger(handle: TriggerHandle) = handle.cancel()
    }

    @Test
    fun `装配壳恒挂 workManager：无需注入即可建任务`() = runBlocking {
        val shell = AppShell.assemble(
            engineFactory = { id, _ -> FakeEngineForDispatcher(id) },
            schedulerProvider = NoopProvider(),
            intentLog = InMemoryIntentLog(),
        )
        shell.use {
            val r = shell.router.dispatch(
                BridgeRequest(
                    1, "workManager", "create",
                    """{"id":"w1","name":"n","projectId":"p","scriptPath":"a.js","schedule":{"kind":"once","delaySeconds":60}}""",
                    5_000,
                ),
            )
            assertInstanceOf(BridgeResponse.Ok::class.java, r)
            assertEquals(listOf("w1"), shell.scheduler.tasks().map { it.id })
        }
        Unit
    }
}
