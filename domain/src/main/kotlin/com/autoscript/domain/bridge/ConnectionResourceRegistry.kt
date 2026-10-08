package com.autoscript.domain.bridge

import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicLong

/**
 * 桥连接级资源收口（§7.5 连接生命周期 × §9.2 会话资源）。
 *
 * **为什么需要它**：投屏会话是**进程级单资源**，而它属于**一条桥连接** ——
 * 脚本进程崩了、被看门狗掐了、socket 断了、宿主 `close()` 了，那条连接上的会话
 * 都不该活着（`VirtualDisplay` + `mediaProjection` 前台服务会一直挂在那儿，
 * 用户看到通知栏一条"正在投屏"却没有任何脚本在跑）。
 *
 * 收口点因此必须在**连接层**：`NewlineFrameServer` 的每一条连接在
 * abort / dispose / 自然退出时都要问一次"这条连接留下了什么要还的"。
 * 但 `:bridge:java` 不该认识投屏（那是 `:platform:*` 的实现细节），于是这里只放
 * **一个不透明的收口口**：谁开的资源谁在开的时候登记一个"怎么还"的回调。
 *
 * **契约**（四条，逐条都有对应的可观测后果）：
 * - **登记即可靠**：拿到的 [Handle] 一旦登记成功，回调**一定**被调用**恰好一次**
 *   （连接撤销、连接正常结束、或显式 [Handle.release]）；
 * - **撤销即收口**：连接结束前跑完所有回调，之后**再登记也立刻兑现** ——
 *   "登记与撤销赛跑"不会留下孤儿资源（这是 `open` 进行中被撤销那条路）；
 * - **幂等**：重复撤销、撤销后再 [Handle.release]，回调都只跑一次；
 * - **不抛**：回调里的异常被吞掉并继续（收口路径上抛异常只会让别的资源也还不掉），
 *   但**不静默** —— 经 [onError] 上报（默认不做事，宿主可接线到日志）。
 *
 * 实例是**每连接一个**（[revoked] 是单标志，不是多连接表）：连接与注册表一一对应，
 * 跨连接共享会让"撤销"的含义变成"全部撤销"。
 *
 * 线程安全：登记与撤销可来自任意线程（连接线程、主线程、撤销线程）。
 */
class ConnectionResourceRegistry(
    private val onError: (Throwable) -> Unit = {},
) {
    private val nextId = AtomicLong(1)
    private val entries = ConcurrentHashMap<Long, () -> Unit>()

    @Volatile
    private var revoked = false

    /** 连接级资源句柄：显式 [release]，或连接结束时由 [revokeAll] 收口（恰好一次）。 */
    inner class Handle internal constructor(private val id: Long) {
        /** 显式收口（例如会话已经自己关掉了）。幂等；连接级撤销随后不会重复调用。 */
        fun release() = runEntry(id)
    }

    /**
     * 登记一份"怎么还"（[close] 必须幂等且不抛；它可能在任意线程上被调）。
     *
     * @return 句柄；**已经撤销过的连接**登记时会立刻执行一次 [close] ——
     *   迟到的登记不会留下孤儿资源。
     */
    fun register(close: () -> Unit): Handle {
        val id = nextId.getAndIncrement()
        entries[id] = close
        if (revoked) runEntry(id)
        return Handle(id)
    }

    /** 连接撤销/结束：跑完这条连接登记过的全部回调（幂等；之后新登记立刻兑现）。 */
    fun revokeAll() {
        revoked = true
        entries.keys.toList().forEach { runEntry(it) }
    }

    /** 在册资源数（测试与诊断用；收口后应为 0）。 */
    fun size(): Int = entries.size

    // 这里**刻意**接 `Throwable` 而不是 `Exception`：登记进来的收口回调由设备层写
    // （`VirtualDisplay.release` 之类），那些调用在异常路径上抛什么不由我们决定，
    // 而漏掉一个 = 其余资源全留在半开态。`onError` 是它的唯一去处，不吞。
    @Suppress("TooGenericExceptionCaught")
    private fun runEntry(id: Long) {
        // `remove` 的返回值就是"恰好一次"的凭据：谁拿到谁负责跑，没拿到就说明已跑过。
        val close = entries.remove(id) ?: return
        try {
            close()
        } catch (t: Throwable) {
            // 收口路径不抛：一份资源还不掉，不能让其余资源也留在半开态。
            onError(t)
        }
    }
}
