package com.autoscript.bridge

import com.autoscript.domain.bridge.AuthenticatedRunContext
import com.autoscript.domain.bridge.BridgeRequest
import com.autoscript.domain.bridge.BridgeResponse
import com.autoscript.domain.core.ErrorCode
import com.autoscript.domain.engine.EngineId
import com.autoscript.domain.permission.BridgeCapability
import com.autoscript.domain.permission.CapabilityMask
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertInstanceOf
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class BridgeRouterTest {

    private val router: BridgeRouter
        get() = BridgeRouter(RequestRegistry())

    @Test
    fun `echo round trip`() = runBlocking {
        val r = router
        assertTrue(r.register("echo") { req ->
            BridgeResponse.Ok(req.id, req.payload)
        })
        val resp = r.dispatch(BridgeRequest(7, "echo", "echo", "{\"x\":1}", 5_000))
        assertInstanceOf(BridgeResponse.Ok::class.java, resp)
        assertEquals(7L, (resp as BridgeResponse.Ok).id)
        assertEquals("{\"x\":1}", resp.payload)
    }

    @Test
    fun `unknown namespace returns ERR_NOT_IMPLEMENTED`() = runBlocking {
        val resp = router.dispatch(BridgeRequest(1, "ghost", "m", null, 5_000))
        val err = assertInstanceOf(BridgeResponse.Err::class.java, resp)
        assertEquals(ErrorCode.ERR_NOT_IMPLEMENTED.code, err.errorCode)
    }

    @Test
    fun `duplicate requestId rejected`() = runBlocking {
        val r = router
        // 首个 dispatch 以挂起态 handler 占住 requestId=5（未完成 → 仍在册），
        // 这样第二次同 id dispatch 才能命中 registry.register 的重复拒绝路径。
        val gate = CompletableDeferred<Unit>()
        r.register("echo") { req ->
            gate.await()                                   // 持续挂起，保持请求在册
            BridgeResponse.Ok(req.id, null)
        }
        val first = async { r.dispatch(BridgeRequest(5, "echo", "echo", null, 5_000)) }
        delay(50)                                           // 确保首个 dispatch 进入 registry
        val second = r.dispatch(BridgeRequest(5, "echo", "echo", null, 5_000))
        val err = assertInstanceOf(BridgeResponse.Err::class.java, second)
        assertEquals(ErrorCode.ERR_INVALID_PARAM.code, err.errorCode)
        gate.complete(Unit)                                // 放行首个，避免超时噪音
        first.await()
        Unit                                           // 显式收尾：void 返回值才被 JUnit5 视为测试
    }

    @Test
    fun `slow handler times out with ERR_TIMEOUT`() = runBlocking {
        val r = router
        r.register("slow") {
            delay(300)
            BridgeResponse.Ok(it.id, null)
        }
        val resp = r.dispatch(BridgeRequest(1, "slow", "m", null, 50))
        val err = assertInstanceOf(BridgeResponse.Err::class.java, resp)
        assertEquals(ErrorCode.ERR_TIMEOUT.code, err.errorCode)
    }

    @Test
    fun `handler exception mapped to ERR_INVALID_PARAM`() = runBlocking {
        val r = router
        r.register("boom") { throw IllegalStateException("炸了") }
        val resp = r.dispatch(BridgeRequest(1, "boom", "m", null, 5_000))
        val err = assertInstanceOf(BridgeResponse.Err::class.java, resp)
        assertEquals(ErrorCode.ERR_INVALID_PARAM.code, err.errorCode)
        assertTrue(err.detail.orEmpty().contains("炸了"))
    }

    // ── A5：桥面掩码在路由层 deny-by-default（§11） ─────────────────────

    /** 已认证调用方（网络入口必然带身份；本地直投是 caller = null 那条路）。 */
    private fun caller(mask: CapabilityMask) = AuthenticatedRunContext(EngineId(0), 1, 1, mask)

    @Test
    fun `掩码覆盖该面时放行`() = runBlocking {
        val r = router
        r.register("a11y") { req -> BridgeResponse.Ok(req.id, "ok") }
        val resp = withContext(caller(CapabilityMask.of(BridgeCapability.ACCESSIBILITY, BridgeCapability.INPUT_INJECTION))) {
            r.dispatch(BridgeRequest(1, "a11y", "findOne", null, 5_000))
        }
        assertInstanceOf(BridgeResponse.Ok::class.java, resp)
    }

    @Test
    fun `掩码缺该面时拒绝且不触达 handler`() = runBlocking {
        val r = router
        var touched = 0
        r.register("a11y") { req -> touched++; BridgeResponse.Ok(req.id, "ok") }
        val resp = withContext(caller(CapabilityMask.of(BridgeCapability.LOCAL_STORAGE))) {
            r.dispatch(BridgeRequest(1, "a11y", "findOne", null, 5_000))
        }
        assertEquals(ErrorCode.ERR_PERMISSION_DENIED.code, assertInstanceOf(BridgeResponse.Err::class.java, resp).errorCode)
        assertEquals(0, touched, "被拒的调用不得触达 handler（拒绝发生在路由之前）")
    }

    @Test
    fun `拒绝发生在 handler 查找之前——不泄露宿主挂了哪些面`() = runBlocking {
        // 未注册的命名空间：无掩码时是 ERR_NOT_IMPLEMENTED，掩码不足时必须是 PERMISSION_DENIED
        // （否则未授权调用方能用两种错误码的差别探出宿主挂了哪些面）。
        val r = router
        val denied = withContext(caller(CapabilityMask.of(BridgeCapability.LOCAL_STORAGE))) {
            r.dispatch(BridgeRequest(1, "ghost", "m", null, 5_000))
        }
        assertEquals(
            ErrorCode.ERR_PERMISSION_DENIED.code,
            assertInstanceOf(BridgeResponse.Err::class.java, denied).errorCode,
        )
    }

    @Test
    fun `未申报命名空间要全量——大而不足的掩码照样拒`() = runBlocking {
        // 未申报 = UNKNOWN_NAMESPACE_REQUIRED = ALL，所以只有全量掩码才过。
        // 用一个"很大但缺一位"的掩码钉住这一点（避免"大掩码=放行"的错觉）。
        val r = router
        r.register("ghost") { req -> BridgeResponse.Ok(req.id, "ok") }
        val almostAll = CapabilityMask.ALL.minus(BridgeCapability.SENSORS)
        val denied = withContext(caller(almostAll)) {
            r.dispatch(BridgeRequest(1, "ghost", "m", null, 5_000))
        }
        assertEquals(
            ErrorCode.ERR_PERMISSION_DENIED.code,
            assertInstanceOf(BridgeResponse.Err::class.java, denied).errorCode,
            "未申报命名空间要 ALL：缺一位也拒（不因掩码大而放行）",
        )
        // 全量掩码才过掩码闸 —— 未注册时落到"未实现"（说明确实过了授权闸）。
        val passedGate = withContext(caller(CapabilityMask.ALL)) {
            r.dispatch(BridgeRequest(2, "unregistered", "m", null, 5_000))
        }
        assertEquals(
            ErrorCode.ERR_NOT_IMPLEMENTED.code,
            assertInstanceOf(BridgeResponse.Err::class.java, passedGate).errorCode,
        )
    }

    @Test
    fun `本地可信调用（无身份）不受掩码限制`() = runBlocking {
        val r = router
        r.register("a11y") { req -> BridgeResponse.Ok(req.id, "ok") }
        // 无 AuthenticatedRunContext：宿主/UI 直投那条路，授权由"能拿到宿主对象"承担。
        assertInstanceOf(BridgeResponse.Ok::class.java, r.dispatch(BridgeRequest(1, "a11y", "findOne", null, 5_000)))
    }
}