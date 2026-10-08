package com.autoscript.shell

import com.autoscript.domain.automation.ScreenConsentHolder
import com.autoscript.domain.automation.ScreenConsentHost
import com.autoscript.domain.automation.ScreenConsentInbox
import com.autoscript.domain.automation.ScreenConsentOutcome
import com.autoscript.domain.core.AutojsException
import com.autoscript.domain.core.ErrorCode
import com.autoscript.platform.capabilities.device.AndroidScreenConsentToken
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertInstanceOf
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows

/**
 * 投屏同意征询（§9.2）：宿主邮箱 → 一次系统对话框 → 设备层凭据。
 *
 * 三种落点**都必须如实失败**，且**一律 `ERR_CAPTURE_DENIED`**：
 * - 没有存活的界面（宿主未注册）→ 没人能问用户；
 * - 宿主拉不起对话框 → 这次没能把用户送到授权页；
 * - 用户取消 → 如实回取消。
 *
 * 另有一条与安全面相关的判据：**同意凭据是一次性的** —— API 34 起系统不复用同意，
 * 一份凭据换第二条会话必须失败（`AndroidMediaProjectionSessions.open` 的第二道闸）。
 */
class AndroidScreenConsentBrokerTest {

    /** 假宿主：结果由用例给定（`launched` 控制"系统里有没有这个界面"）。 */
    private class FakeHost(private val outcome: ScreenConsentOutcome) : ScreenConsentHost {
        var detachedCalls = 0
        override suspend fun requestScreenConsent(): ScreenConsentOutcome = outcome
        override fun requestScreenConsentDetached(): Boolean {
            detachedCalls++
            return outcome.launched
        }
    }

    private val inbox = ScreenConsentInbox()
    private val broker = AndroidScreenConsentBroker(inbox)

    @AfterEach
    fun clearHolder() {
        ScreenConsentHolder.host = null
        ScreenConsentInbox.shared.clear()
    }

    private fun assertDenied(block: suspend () -> Unit): AutojsException {
        val e = assertThrows<AutojsException> { runBlocking { block() } }
        assertEquals(ErrorCode.ERR_CAPTURE_DENIED, e.error)
        return e
    }

    @Test
    fun `没有存活界面 —— 如实拒绝，不改走 a11y 截图`() {
        ScreenConsentHolder.host = null
        val e = assertDenied { broker.requestConsent() }
        assertTrue(e.message!!.contains("界面"), "错误消息要说明是「没人能问用户」，实际：${e.message}")
    }

    @Test
    fun `对话框拉不起来 —— 如实拒绝`() {
        ScreenConsentHolder.host = FakeHost(ScreenConsentOutcome(launched = false, resultCode = 0, payload = null))
        assertDenied { broker.requestConsent() }
    }

    @Test
    fun `用户取消 —— 凭据如实 granted=false`() = runBlocking {
        ScreenConsentHolder.host = FakeHost(ScreenConsentOutcome(launched = true, resultCode = 0, payload = null))
        val token = assertInstanceOf(AndroidScreenConsentToken::class.java, broker.requestConsent())
        assertFalse(token.granted, "resultCode=0 不是 RESULT_OK：如实回取消")
        Unit
    }

    @Test
    fun `用户同意 —— 凭据一次性（第二道闸由设备层把关）`() = runBlocking {
        ScreenConsentHolder.host = FakeHost(ScreenConsentOutcome(launched = true, resultCode = -1, payload = null))
        val token = assertInstanceOf(AndroidScreenConsentToken::class.java, broker.requestConsent())
        assertTrue(token.granted, "resultCode=-1 就是 Activity.RESULT_OK")
        assertTrue(token.consume(), "第一次取用成功")
        assertFalse(token.consume(), "同一份同意换不了第二条会话（API 34+ 语义）")
        Unit
    }

    @Test
    fun `能力中心「去授权」走 detached 那条 —— 只问拉起来没有`() {
        val host = FakeHost(ScreenConsentOutcome(launched = true, resultCode = -1, payload = null))
        ScreenConsentHolder.host = host
        assertTrue(host.requestScreenConsentDetached())
        assertEquals(1, host.detachedCalls)
        // detached 本身**不产生也不消费**凭据：它只把对话框交给系统，结果要等
        // `onActivityResult` 回调（那条路才往待领口 offer）。所以此刻待领口必须是空的
        // —— 空着才是诚实的（没有"已授权"这回事），下一次 `startCapturer` 会照常征询。
        assertNull(ScreenConsentInbox.shared.claim(), "detached 不该凭空造出一份凭据")
        Unit
    }

    // ── 待领凭据（能力中心「去授权」的结果不丢） ─────────────────────

    @Test
    fun `待领凭据先被领走 —— 不再弹一次对话框`() = runBlocking {
        val host = FakeHost(ScreenConsentOutcome(launched = false, resultCode = 0, payload = null))
        ScreenConsentHolder.host = host
        inbox.offer(ScreenConsentOutcome(launched = true, resultCode = -1, payload = null))
        val token = assertInstanceOf(AndroidScreenConsentToken::class.java, broker.requestConsent())
        assertTrue(token.granted, "用户已经在能力中心同意过：这一次直接换凭据，不让用户重做一遍")
        Unit
    }

    @Test
    fun `待领凭据只能领一次 —— 第二次如实回到问用户那条路`() = runBlocking {
        inbox.offer(ScreenConsentOutcome(launched = true, resultCode = -1, payload = null))
        assertInstanceOf(AndroidScreenConsentToken::class.java, broker.requestConsent())
        // 第二次：待领口已空，且没有界面可问 → 如实拒绝（不是"再拿一份同意"）。
        ScreenConsentHolder.host = null
        assertDenied { broker.requestConsent() }
        Unit
    }

    @Test
    fun `非 granted 的待领结果不会被领走 —— 收进来也开不了会话`() = runBlocking {
        inbox.offer(ScreenConsentOutcome(launched = true, resultCode = 0, payload = null))
        ScreenConsentHolder.host = null
        assertDenied { broker.requestConsent() }
        Unit
    }

    @Test
    fun `detached 那次同意被下一次请求领走 —— 之后是新一轮征询，不是上一张陈票`() = runBlocking {
        // 能力中心那条路的完整形状：宿主把对话框拉起来（detached 只回"拉起来了没有"），
        // 用户同意后由**回调**把结果放进待领口 —— 与 `MainActivity.consentLauncher`
        // 回调里那一行 `ScreenConsentInbox.shared.offer(outcome)` 同形。
        // 判据是"detached 的结果真的被消费掉一次，且只有一次"：第一次请求领走它
        // （不再弹对话框），第二次请求必须是**新的一轮征询**。
        val sharedBroker = AndroidScreenConsentBroker(ScreenConsentInbox.shared)
        val host = object : ScreenConsentHost {
            var suspendCalls = 0
            var detachedCalls = 0
            override suspend fun requestScreenConsent(): ScreenConsentOutcome {
                suspendCalls++
                // 走到这里说明待领口是空的 —— 这是新的一轮，回一份新的同意。
                return ScreenConsentOutcome(launched = true, resultCode = -1, payload = null, detail = "第二次征询")
            }

            override fun requestScreenConsentDetached(): Boolean {
                detachedCalls++
                ScreenConsentInbox.shared.offer(
                    ScreenConsentOutcome(launched = true, resultCode = -1, payload = null, detail = "能力中心那次"),
                )
                return true
            }
        }
        ScreenConsentHolder.host = host

        assertTrue(host.requestScreenConsentDetached(), "能力中心那次：对话框拉起来了")
        assertEquals(1, host.detachedCalls)

        val first = assertInstanceOf(AndroidScreenConsentToken::class.java, sharedBroker.requestConsent())
        assertTrue(first.granted, "领到的必须是能力中心那次留下的同意")
        assertEquals(0, host.suspendCalls, "待领凭据还在时**不许**再弹一次对话框（那会让用户白做一遍）")

        // 第二次：待领口已空 —— 如实走回征询那条路，拿到的是**新**的一轮（不是复用上一张）。
        val second = assertInstanceOf(AndroidScreenConsentToken::class.java, sharedBroker.requestConsent())
        assertEquals(1, host.suspendCalls, "陈票已被消费：这一次必须重新征询")
        assertTrue(second.consume(), "新凭据可用")
        assertFalse(second === first, "第二次拿到的不是同一份凭据对象")
        Unit
    }
}
