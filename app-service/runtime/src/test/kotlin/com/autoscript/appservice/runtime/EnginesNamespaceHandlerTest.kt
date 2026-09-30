package com.autoscript.appservice.runtime

import com.autoscript.domain.bridge.BridgeRequest
import com.autoscript.domain.bridge.BridgeResponse
import com.autoscript.domain.engine.EngineId
import com.autoscript.domain.json.DomainJson
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertInstanceOf
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class EnginesNamespaceHandlerTest {

    private fun handler(capacity: Int = 1): Pair<EnginesNamespaceHandler, MutableList<FakeEngine>> {
        val engines = MutableList(capacity) { FakeEngine(EngineId(it)) }
        val pool = FixedEnginePool({ id -> engines[id.poolIndex] }, capacity)
        return EnginesNamespaceHandler(RuntimeController(pool)) to engines
    }

    /** 桥侧 Request 工厂（步骤 4 后自定义 Request 已删；缺省 TTL = 常规桥请求量级）。 */
    private fun enginesReq(id: Long, method: String, payload: String?, ttlMillis: Long = 30_000) =
        BridgeRequest(id, "engines", method, payload, ttlMillis)

    private fun execPayload(
        projectId: String = "p1",
        scriptPath: String = "a.js",
        extra: String = "",
    ): String = """{"projectId":"$projectId","scriptPath":"$scriptPath"$extra}"""

    @Test
    fun `exec 成功回 runId 与 handle`() = runBlocking {
        val (h, engines) = handler()
        val resp = assertInstanceOf(
            BridgeResponse.Ok::class.java,
            h.handle(enginesReq(1, "exec", """{"projectId":"p1","scriptPath":"a.js","args":["x","y"],"runNonce":"n1"}""")),
        )
        val o = DomainJson.decodeObject(resp.payload!!)
        val runId = (o["runId"] as DomainJson.Value.N).raw.toLong()
        val handle = o["handle"] as DomainJson.Value.Obj
        assertEquals(runId, ((handle.fields["refId"] as DomainJson.Value.N).raw.toLong()))
        assertEquals("1", (handle.fields["generation"] as DomainJson.Value.N).raw)
        // 幂等锚点透传引擎（§8.5）
        assertEquals("n1", engines[0].executed.single().runNonce)
        assertEquals(listOf("x", "y"), engines[0].executed.single().args)
    }

    @Test
    fun `exec 缺字段回 ERR_INVALID_PARAM`() = runBlocking {
        val (h, _) = handler()
        val cases = listOf(
            null,
            """{"projectId":"p1"}""",
            """{"projectId":"","scriptPath":"a.js"}""",
            "不是json",
            """{"projectId":"p1","scriptPath":"a.js","args":"x"}""",
        )
        for ((i, p) in cases.withIndex()) {
            val resp = assertInstanceOf(
                BridgeResponse.Err::class.java,
                h.handle(enginesReq(10L + i, "exec", p)),
            )
            assertEquals("ERR_INVALID_PARAM", resp.errorCode, "case $i: $p")
        }
    }

    @Test
    fun `exec 满池排队超时回 ERR_TIMEOUT`() = runBlocking {
        val (h, _) = handler()
        val first = assertInstanceOf(
            BridgeResponse.Ok::class.java,
            h.handle(enginesReq(1, "exec", execPayload())),
        )
        val held =
            (DomainJson.decodeObject(first.payload!!)["runId"] as DomainJson.Value.N).raw.toLong()
        // 排队上限 = payload waitTimeoutMillis 优先，否则桥侧 TTL：槽位被 held 占着，
        // 第二个请求未带显式上限，等满 300ms TTL → ERR_TIMEOUT，绝不无限挂住（§7.4 每次跨进程操作必有 TTL）。
        val queued = assertInstanceOf(
            BridgeResponse.Err::class.java,
            h.handle(
                enginesReq(
                    2,
                    "exec",
                    execPayload("p2", "b.js"),
                    ttlMillis = 300,
                ),
            ),
        )
        assertEquals("ERR_TIMEOUT", queued.errorCode, "满池 + TTL 到期 → ERR_TIMEOUT")
        // 在途者不受排队失败影响：stop 后槽位归池，同参数请求随即拿到槽（池容量未缩水）。
        val stopHeld = assertInstanceOf(
            BridgeResponse.Ok::class.java,
            h.handle(
                enginesReq(3, "stop", """{"runId":$held}""", ttlMillis = 5_000),
            ),
        )
        assertEquals("true", stopHeld.payload)
        val retry = assertInstanceOf(
            BridgeResponse.Ok::class.java,
            h.handle(
                enginesReq(4, "exec", execPayload("p2", "b.js"), ttlMillis = 5_000),
            ),
        )
        val retryId =
            (DomainJson.decodeObject(retry.payload!!)["runId"] as DomainJson.Value.N).raw.toLong()
        assertTrue(retryId != held, "释放后的槽位应分配给新 run")
        Unit                                           // 显式收尾：void 返回值才被 JUnit5 视为测试
    }

    @Test
    fun `exec 排队上限 payload 显式值优先于桥 TTL`() = runBlocking {
        val (h, _) = handler()
        val first = assertInstanceOf(
            BridgeResponse.Ok::class.java,
            h.handle(enginesReq(1, "exec", execPayload())),
        )
        val held =
            (DomainJson.decodeObject(first.payload!!)["runId"] as DomainJson.Value.N).raw.toLong()
        val begin = System.currentTimeMillis()
        val queued = assertInstanceOf(
            BridgeResponse.Err::class.java,
            h.handle(
                enginesReq(
                    2,
                    "exec",
                    execPayload("p2", "b.js", ",\"waitTimeoutMillis\":200"),
                    ttlMillis = 30_000,
                ),
            ),
        )
        val elapsed = System.currentTimeMillis() - begin
        assertEquals("ERR_TIMEOUT", queued.errorCode, "显式排队上限到期 → ERR_TIMEOUT")
        assertTrue(elapsed < 10_000, "显式上限应先于 TTL 到期（实际 ${elapsed}ms）")
        h.handle(enginesReq(3, "stop", "{\"runId\":$held}"))
        Unit
    }

    @Test
    fun `exec 非法 waitTimeoutMillis 回 ERR_INVALID_PARAM`() = runBlocking {
        val (h, _) = handler()
        val resp = assertInstanceOf(
            BridgeResponse.Err::class.java,
            h.handle(
                enginesReq(
                    1,
                    "exec",
                    execPayload("p1", "a.js", ",\"waitTimeoutMillis\":\"soon\""),
                ),
            ),
        )
        assertEquals("ERR_INVALID_PARAM", resp.errorCode, "排队上限非数字 → 参数错误")
        Unit
    }

    @Test
    fun `exec 引擎启动失败回 ERR_ENGINE_CRASHED`() = runBlocking {
        val engines = MutableList(1) { FakeEngine(EngineId(it)) }.also { it[0].failOnExecute = true }
        val pool = FixedEnginePool({ id -> engines[id.poolIndex] }, 1)
        val h = EnginesNamespaceHandler(RuntimeController(pool))
        val resp = assertInstanceOf(
            BridgeResponse.Err::class.java,
            h.handle(enginesReq(1, "exec", execPayload())),
        )
        assertEquals("ERR_ENGINE_CRASHED", resp.errorCode)
        assertTrue(resp.detail.orEmpty().contains("fake boot failure"))
    }

    @Test
    fun `stop 干净回 true，未知 runId 回 ERR_NOT_FOUND`() = runBlocking {
        val (h, _) = handler()
        val exec = assertInstanceOf(
            BridgeResponse.Ok::class.java,
            h.handle(enginesReq(1, "exec", execPayload())),
        )
        val runId = (DomainJson.decodeObject(exec.payload!!)["runId"] as DomainJson.Value.N).raw
        val stopped = assertInstanceOf(
            BridgeResponse.Ok::class.java,
            h.handle(enginesReq(2, "stop", """{"runId":$runId}""")),
        )
        assertEquals("true", stopped.payload)
        // 二次 stop：如实 NOT_FOUND，不静默吞掉
        val gone = assertInstanceOf(
            BridgeResponse.Err::class.java,
            h.handle(enginesReq(3, "stop", """{"runId":$runId}""")),
        )
        assertEquals("ERR_NOT_FOUND", gone.errorCode)
        // 非法 runId 载荷
        val bad = assertInstanceOf(
            BridgeResponse.Err::class.java,
            h.handle(enginesReq(4, "stop", """{"runId":"x"}""")),
        )
        assertEquals("ERR_INVALID_PARAM", bad.errorCode)
    }

    @Test
    fun `poolStats 回 capacity-free-busy`() = runBlocking {
        val (h, _) = handler()
        val before = assertInstanceOf(
            BridgeResponse.Ok::class.java,
            h.handle(enginesReq(1, "poolStats", null)),
        )
        assertEquals("""{"capacity":1,"free":1,"busy":0}""", before.payload)
        val exec = assertInstanceOf(
            BridgeResponse.Ok::class.java,
            h.handle(enginesReq(2, "exec", execPayload())),
        )
        val runId = (DomainJson.decodeObject(exec.payload!!)["runId"] as DomainJson.Value.N).raw
        val during = assertInstanceOf(
            BridgeResponse.Ok::class.java,
            h.handle(enginesReq(3, "poolStats", null)),
        )
        assertEquals("""{"capacity":1,"free":0,"busy":1}""", during.payload)
        h.handle(enginesReq(4, "stop", """{"runId":$runId}"""))
        Unit                                           // 显式收尾：void 返回值才被 JUnit5 视为测试
    }

    @Test
    fun `heartbeat 打到未知 runId 回 false 且不建账`() = runBlocking {
        val engines = MutableList(1) { FakeEngine(EngineId(it)) }
        val controller = RuntimeController(FixedEnginePool({ id -> engines[id.poolIndex] }, 1))
        val h = EnginesNamespaceHandler(controller)
        val resp = assertInstanceOf(
            BridgeResponse.Ok::class.java,
            h.handle(enginesReq(1, "heartbeat", """{"runId":999,"seq":1}""")),
        )
        assertEquals("false", resp.payload, "未知 runId → Ok false（不是调用方错误，不 4xx）")
        assertTrue(controller.heartbeats().trackedRuns().isEmpty(), "无主心跳不得建账")
    }

    @Test
    fun `heartbeat 记账到宿主账本，重复 seq 不被采纳`() = runBlocking {
        val (h, _) = handler()
        val exec = assertInstanceOf(
            BridgeResponse.Ok::class.java,
            h.handle(enginesReq(1, "exec", execPayload())),
        )
        val runId = (DomainJson.decodeObject(exec.payload!!)["runId"] as DomainJson.Value.N).raw.toLong()

        val first = assertInstanceOf(
            BridgeResponse.Ok::class.java,
            h.handle(enginesReq(2, "heartbeat", """{"runId":$runId,"seq":5}""")),
        )
        assertEquals("true", first.payload)
        val dup = assertInstanceOf(
            BridgeResponse.Ok::class.java,
            h.handle(enginesReq(3, "heartbeat", """{"runId":$runId,"seq":5}""")),
        )
        assertEquals("false", dup.payload, "同 seq 不刷时间戳：积压帧不得让死掉的 run 装作活着")

        val bad = assertInstanceOf(
            BridgeResponse.Err::class.java,
            h.handle(enginesReq(4, "heartbeat", """{"runId":$runId}""")),
        )
        assertEquals("ERR_INVALID_PARAM", bad.errorCode)
        h.handle(enginesReq(5, "stop", """{"runId":$runId}"""))
        Unit
    }

    @Test
    fun `未知方法回 ERR_NOT_IMPLEMENTED`() = runBlocking {
        val (h, _) = handler()
        val resp = assertInstanceOf(
            BridgeResponse.Err::class.java,
            h.handle(enginesReq(1, "reboot", null)),
        )
        assertEquals("ERR_NOT_IMPLEMENTED", resp.errorCode)
    }

    @Test
    fun `status 在途回引擎状态名，结算后如实 NOT_FOUND 不伪造 STOPPED`() = runBlocking {
        val (h, _) = handler()
        val exec = assertInstanceOf(
            BridgeResponse.Ok::class.java,
            h.handle(enginesReq(1, "exec", execPayload())),
        )
        val runId = (DomainJson.decodeObject(exec.payload!!)["runId"] as DomainJson.Value.N).raw

        val live = assertInstanceOf(
            BridgeResponse.Ok::class.java,
            h.handle(enginesReq(2, "status", "{\"runId\":$runId}")),
        )
        assertEquals("\"RUNNING\"", live.payload, "FakeEngine execute 后即 RUNNING（枚举名逐字，JS 字面量对齐）")

        h.handle(enginesReq(3, "stop", "{\"runId\":$runId}"))
        val gone = assertInstanceOf(
            BridgeResponse.Err::class.java,
            h.handle(enginesReq(4, "status", "{\"runId\":$runId}")),
        )
        assertEquals("ERR_NOT_FOUND", gone.errorCode, "结算后无状态可读：不得伪造 STOPPED 掩盖 CRASHED")
    }

    @Test
    fun `status 未知 runId 与非法载荷`() = runBlocking {
        val (h, _) = handler()
        val gone = assertInstanceOf(
            BridgeResponse.Err::class.java,
            h.handle(enginesReq(1, "status", "{\"runId\":999}")),
        )
        assertEquals("ERR_NOT_FOUND", gone.errorCode)
        val bad = assertInstanceOf(
            BridgeResponse.Err::class.java,
            h.handle(enginesReq(2, "status", "{\"runId\":\"x\"}")),
        )
        assertEquals("ERR_INVALID_PARAM", bad.errorCode)
    }

    @Test
    fun `channel 建查发拉关全链路`() = runBlocking {
        val (h, _) = handler()
        // 建（复用同名）
        val created = assertInstanceOf(
            BridgeResponse.Ok::class.java,
            h.handle(enginesReq(1, "channel", """{"name":"progress"}""")),
        )
        val co = DomainJson.decodeObject(created.payload!!)
        assertEquals("progress", (co["name"] as DomainJson.Value.S).v)
        val channelId = (co["channelId"] as DomainJson.Value.N).raw
        val again = assertInstanceOf(
            BridgeResponse.Ok::class.java,
            h.handle(enginesReq(2, "channel", """{"name":"progress"}""")),
        )
        assertEquals(created.payload, again.payload, "同名通道复用")

        // 发两条
        h.handle(enginesReq(3, "channelEmit", """{"channelId":$channelId,"event":"tick","payload":"1"}"""))
        h.handle(enginesReq(4, "channelEmit", """{"channelId":$channelId,"event":"tick"}"""))

        // 拉（游标分页）
        val page1 = assertInstanceOf(
            BridgeResponse.Ok::class.java,
            h.handle(enginesReq(5, "channelDrain", """{"channelId":$channelId,"sinceSeq":0,"max":1}""")),
        )
        val p1 = DomainJson.decodeObject(page1.payload!!)
        assertEquals("1", (p1["last"] as DomainJson.Value.N).raw)
        assertEquals(1, (p1["events"] as DomainJson.Value.Arr).items.size)
        val last1 = (p1["last"] as DomainJson.Value.N).raw.toLong()
        val page2 = assertInstanceOf(
            BridgeResponse.Ok::class.java,
            h.handle(enginesReq(6, "channelDrain", """{"channelId":$channelId,"sinceSeq":$last1}""")),
        )
        val p2 = DomainJson.decodeObject(page2.payload!!)
        assertEquals(1, (p2["events"] as DomainJson.Value.Arr).items.size)
        assertEquals("2", (p2["last"] as DomainJson.Value.N).raw)

        // 关 → 再发/拉如实 NOT_FOUND；同名重建得新 id
        val closed = assertInstanceOf(
            BridgeResponse.Ok::class.java,
            h.handle(enginesReq(7, "channelClose", """{"channelId":$channelId}""")),
        )
        assertEquals("true", closed.payload)
        val emitGone = assertInstanceOf(
            BridgeResponse.Err::class.java,
            h.handle(enginesReq(8, "channelEmit", """{"channelId":$channelId,"event":"x"}""")),
        )
        assertEquals("ERR_NOT_FOUND", emitGone.errorCode)
        val rebuilt = assertInstanceOf(
            BridgeResponse.Ok::class.java,
            h.handle(enginesReq(9, "channel", """{"name":"progress"}""")),
        )
        val newId = (DomainJson.decodeObject(rebuilt.payload!!)["channelId"] as DomainJson.Value.N).raw
        assertTrue(newId != channelId, "关闭后重建得新 id")
    }

    @Test
    fun `channel 非法载荷与空名`() = runBlocking {
        val (h, _) = handler()
        for ((i, p) in listOf(null, """{"name":""}""", """{"name":1}""").withIndex()) {
            val resp = assertInstanceOf(
                BridgeResponse.Err::class.java,
                h.handle(enginesReq(20L + i, "channel", p)),
            )
            assertEquals("ERR_INVALID_PARAM", resp.errorCode)
        }
        val emitBad = assertInstanceOf(
            BridgeResponse.Err::class.java,
            h.handle(enginesReq(30, "channelEmit", """{"channelId":999,"event":"x"}""")),
        )
        assertEquals("ERR_NOT_FOUND", emitBad.errorCode)
    }

    @Test
    fun `channel 有界丢最老`() = runBlocking {
        val engines = MutableList(1) { FakeEngine(EngineId(it)) }
        val pool = FixedEnginePool({ id -> engines[id.poolIndex] }, 1)
        val h = EnginesNamespaceHandler(RuntimeController(pool), channelCapacity = 2)
        val created = assertInstanceOf(
            BridgeResponse.Ok::class.java,
            h.handle(enginesReq(1, "channel", """{"name":"c"}""")),
        )
        val channelId = (DomainJson.decodeObject(created.payload!!)["channelId"] as DomainJson.Value.N).raw
        repeat(4) { i ->
            h.handle(enginesReq(10L + i, "channelEmit", """{"channelId":$channelId,"event":"e$i"}"""))
        }
        val drained = assertInstanceOf(
            BridgeResponse.Ok::class.java,
            h.handle(enginesReq(20, "channelDrain", """{"channelId":$channelId,"sinceSeq":0}""")),
        )
        val events = (DomainJson.decodeObject(drained.payload!!)["events"] as DomainJson.Value.Arr).items
        assertEquals(2, events.size)
        val names = events.map { ((it as DomainJson.Value.Obj).fields["event"] as DomainJson.Value.S).v }
        assertEquals(listOf("e2", "e3"), names)
    }

    @Test
    fun `channel 并发 emit 不丢`() = runBlocking {
        val (h, _) = handler()
        val created = assertInstanceOf(
            BridgeResponse.Ok::class.java,
            h.handle(enginesReq(1, "channel", """{"name":"cc"}""")),
        )
        val channelId = (DomainJson.decodeObject(created.payload!!)["channelId"] as DomainJson.Value.N).raw
        (1..50).map { i ->
            async {
                h.handle(enginesReq(100L + i, "channelEmit", """{"channelId":$channelId,"event":"e$i"}"""))
            }
        }.awaitAll()
        val drained = assertInstanceOf(
            BridgeResponse.Ok::class.java,
            h.handle(enginesReq(200, "channelDrain", """{"channelId":$channelId,"sinceSeq":0,"max":100}""")),
        )
        val events = (DomainJson.decodeObject(drained.payload!!)["events"] as DomainJson.Value.Arr).items
        assertEquals(50, events.size)
    }
}
