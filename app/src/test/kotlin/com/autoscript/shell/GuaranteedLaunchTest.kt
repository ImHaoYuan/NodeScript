package com.autoscript.shell

import kotlinx.coroutines.CoroutineExceptionHandler
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.cancel
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference

/**
 * [launchGuaranteed] 的不变量：**收口回调恰好一次**，四种路径都算数。
 *
 * 这条不变量是"把 `GlobalScope.launch` 换成有主域"的必要代价 —— 没有它，
 * 域一 cancel，广播 `goAsync()` 窗口就没人回执了（见 [launchGuaranteed] 的 KDoc）。
 * 所以四个用例逐一钉住：正常、域已取消（协程体没跑）、协程体抛异常、挂起中被取消。
 */
class GuaranteedLaunchTest {

    /** 域带异常处理器：默认处理器会把异常打到 stderr，测试里看着像失败。 */
    private fun scope(handler: CoroutineExceptionHandler? = null) = CoroutineScope(
        SupervisorJob() + Dispatchers.Default + (handler ?: CoroutineExceptionHandler { _, _ -> }),
    )

    /** 回调要么已到，要么断言失败 —— 不靠 sleep 猜。 */
    private fun await(latch: CountDownLatch, what: String) {
        assertTrue(latch.await(5, TimeUnit.SECONDS), "$what 没被回调（广播窗口会挂到超时）")
        Thread.sleep(50)   // 再等一拍：若收口处会双调，这里能看见
    }

    @Test
    fun `正常路径 done 恰好一次`() {
        val calls = AtomicInteger(0)
        val latch = CountDownLatch(1)
        val scope = scope()
        scope.launchGuaranteed({ calls.incrementAndGet(); latch.countDown() }) { }
        await(latch, "done")
        scope.cancel()     // 收口后不得再补一次
        Thread.sleep(50)
        assertEquals(1, calls.get())
    }

    @Test
    fun `域已取消时协程体不跑 done 仍然恰好一次`() {
        val calls = AtomicInteger(0)
        val latch = CountDownLatch(1)
        val body = AtomicInteger(0)
        val scope = scope()
        scope.cancel()
        scope.launchGuaranteed({ calls.incrementAndGet(); latch.countDown() }) { body.incrementAndGet() }
        await(latch, "done")
        assertEquals(0, body.get(), "域已取消，协程体不该跑")
        assertEquals(1, calls.get())
    }

    @Test
    fun `协程体抛异常 done 仍然恰好一次（异常不吞）`() {
        val calls = AtomicInteger(0)
        val latch = CountDownLatch(1)
        val caught = AtomicReference<Throwable?>(null)
        val scope = scope(CoroutineExceptionHandler { _, t -> caught.set(t) })
        scope.launchGuaranteed({ calls.incrementAndGet(); latch.countDown() }) { error("协程体炸了") }
        await(latch, "done")
        assertEquals(1, calls.get())
        assertTrue(caught.get() is IllegalStateException, "异常应交回域的处理器，不能被吞掉")
    }

    @Test
    fun `挂起中被取消 done 仍然恰好一次`() {
        val calls = AtomicInteger(0)
        val latch = CountDownLatch(1)
        val entered = CountDownLatch(1)
        val scope = scope()
        scope.launchGuaranteed({ calls.incrementAndGet(); latch.countDown() }) {
            entered.countDown()
            awaitCancellation()
        }
        assertTrue(entered.await(5, TimeUnit.SECONDS), "协程体没进去")
        scope.cancel()
        await(latch, "done")
        assertEquals(1, calls.get())
    }
}
