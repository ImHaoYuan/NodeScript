package com.autoscript.platform.capabilities.device

/**
 * mediaProjection 前台类型的接触面（§8.7/§9.2）。
 *
 * **为什么不复用 `ForegroundKeeper` 那条保活服务**（这是本文件存在的理由）：
 * 1. **生命周期必须互相独立**：保活随脚本运行在跑，投屏随一次用户同意在跑。
 *    并进一个服务后，任一边的停止都会牵动另一边 —— 用户从通知栏停掉投屏，
 *    会顺手把正在跑脚本的保活前台也撤了（反之亦然）；
 * 2. **`onStartCommand` 契约不同**：保活那条是 token + 期限 → `WakeLockLedger` 记账，
 *    硬塞进投屏会让"账上有 token ⇔ 真有锁"这条不变量多出一个不记账的分支；
 * 3. **通知通道不同**：用户应当能单独关掉投屏那条常驻提示而不影响保活提示。
 *
 * （注意：`foregroundServiceType` 本身是 **bitmask**，一个服务声明多种类型是合法的 ——
 * 所以"一个服务只能有一种类型"**不是**理由，真正的理由是上面这三条生命周期/契约边界。）
 *
 * **代际（generation）是这套接口的核心**：投屏前台的起停与"哪一次会话"绑定。
 * 没有代际时，旧会话的迟到 `stop()` 会把**新会话**的前台撤掉（表现是"重开投屏后
 * 立刻显示已停止"），而超时放弃的那次 `start()` 之后服务可能才真正起来（孤儿 FGS，
 * 通知栏挂着"正在投屏"却没有任何会话）。所以：
 * - [start] 发一个**新的**代际号（单调递增），并等到**这一个代际**被系统确认；
 * - [stop] 必须带代际号：只停"还是这个代际"的前台，否则如实不动手；
 * - 超时/失败时调用方用 [abandon] 明确放弃这一代 —— 服务侧据此在迟到启动时**自己退场**。
 *
 * 实现住 `:app` 的 shell 包（那里才有 `Service` 与清单）；JVM 替身实现本接口即可测
 * 全套代际判据，不需要 Android。
 */
interface ProjectionForeground {

    /**
     * 进投屏前台并**等到系统确认**（不是"我请求过"）。
     *
     * 为什么必须等到：API 34+ 要求在 `getMediaProjection()`/`createVirtualDisplay()` **之前**
     * 已有一条 `mediaProjection` 类型的前台服务在跑，否则抛 `SecurityException`。
     * `startForegroundService` 是异步的（服务在主线程进前台），所以这里等一个有界确认。
     *
     * **绝不阻塞调用线程**：确认信号由主线程上的服务发出，而 `open` 完全可能在主线程上
     * 被调（能力中心那条「去授权」路径），阻塞主线程 = 等自己，必然超时。所以本方法是
     * **挂起**的（等待期间让出线程），且等待**有界**（超时即放弃这一代）。
     *
     * @return 本代际的凭据；`null` = 超时/权限缺/系统拒 —— 调用方**不得**继续开投影
     *   （开了就是 SecurityException），并应调用 [abandon] 放弃这一代。
     */
    suspend fun start(): ForegroundLease?

    /**
     * 退投屏前台（幂等）：**只有代际还对得上**时才动手。
     *
     * @return true = 确实停了这一代；false = 前台已不是这一代（已被别处停掉 / 已被
     *   新会话接管）—— 如实不动手，绝不把新会话的前台拆了。
     */
    fun stop(lease: ForegroundLease): Boolean

    /**
     * 放弃这一代（超时/开投影失败时调用）：服务若**迟到**才起来，看到自己这一代已被
     * 放弃就立即退场 —— 不留孤儿 FGS。
     *
     * 幂等；对已经停掉的代际调用无副作用。
     */
    fun abandon(lease: ForegroundLease)
}

/**
 * 一次投屏前台租约（代际凭据）：[generation] 单调递增，一次会话一个。
 *
 * 为什么不是"裸 stop()"：收口有三条来路（脚本 `close`、系统 `onStop` 回调、
 * 连接撤销/熄屏裁剪），没有代际时旧来路的那次 stop 会把新会话的前台撤掉。
 */
data class ForegroundLease(val generation: Long)
