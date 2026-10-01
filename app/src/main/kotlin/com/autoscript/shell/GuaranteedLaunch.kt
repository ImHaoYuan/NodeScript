package com.autoscript.shell

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import java.util.concurrent.atomic.AtomicBoolean

/**
 * 起一段后台活，并保证 [onDone] **恰好**被调一次。
 *
 * 为什么需要它：把活从 `GlobalScope` 搬到有主的域（可取消）之后，多出一条
 * `GlobalScope` 时代不存在的失败形态 —— **域已 cancel 时 `launch` 的协程体根本不会跑**。
 * 对 `AppShellApplication.alarmWork` 这种"跑在广播 `goAsync()` 窗口里、窗口必须回执"
 * 的场景，漏调 `done()` = 广播挂到超时（ANR 级别的用户可见故障），比原来的泄漏更糟。
 * 反过来只在收口处兜底，正常路径就会双调（协程体已经调过一次），
 * `PendingResult.finish()` 双调是崩。
 *
 * 故用一次性闩：协程体结尾调一次，[Job.invokeOnCompletion] 再补一次 —— 谁先到算谁的，
 * 后到的被闩挡掉；协程体抛异常或被取消时 `finally` 仍会走，两条路都不会漏。域已取消时
 * `launch` 返回的是已完成的 Job，`invokeOnCompletion` **当场**在调用线程回调，
 * 所以"根本没跑起来"这一路也是同步回执。
 *
 * [block] 的失败不进 [Job] 的异常处理之外：调用方若在意，自己在 block 里记日志
 * （与 `GlobalScope.launch` 的语义相同 —— 这里不替调用方吞异常，也不把它变成崩溃）。
 */
internal fun CoroutineScope.launchGuaranteed(onDone: () -> Unit, block: suspend CoroutineScope.() -> Unit): Job {
    val latch = AtomicBoolean(false)
    val once = { if (latch.compareAndSet(false, true)) onDone() }
    return launch {
        try {
            block()
        } finally {
            once()
        }
    }.also { it.invokeOnCompletion { once() } }
}
