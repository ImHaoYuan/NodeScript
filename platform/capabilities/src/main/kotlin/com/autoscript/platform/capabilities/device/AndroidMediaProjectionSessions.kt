package com.autoscript.platform.capabilities.device

import android.app.KeyguardManager
import android.content.Context
import android.graphics.PixelFormat
import android.hardware.display.DisplayManager
import android.hardware.display.VirtualDisplay
import android.media.Image
import android.media.ImageReader
import android.media.projection.MediaProjection
import android.media.projection.MediaProjectionManager
import android.os.Handler
import android.os.Looper
import android.view.WindowManager
import com.autoscript.domain.automation.LeasedMediaProjectionSession
import com.autoscript.domain.automation.MediaProjectionOpenException
import com.autoscript.domain.automation.MediaProjectionOpenFailure
import com.autoscript.domain.automation.MediaProjectionSessionOwner
import com.autoscript.domain.automation.MediaProjectionSessionState
import com.autoscript.domain.automation.MediaProjectionSessions
import com.autoscript.domain.automation.RawFrame
import com.autoscript.domain.automation.ScreenConsentToken
import com.autoscript.domain.automation.ScreenPolicy
import com.autoscript.domain.automation.ScreenSnapshot
import com.autoscript.domain.automation.SessionResourceLease
import com.autoscript.domain.core.AutojsException
import com.autoscript.domain.core.ErrorCode
import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withTimeoutOrNull

/**
 * 系统投屏同意的凭据（§9.2）：设备层对 `(resultCode, Intent)` 那一对的封装。
 *
 * **一次性**（[consume] 取用即置位）：API 34 起系统不再复用同意凭据 —— 每次
 * `getMediaProjection` 都要一次新的用户确认，所以同一份凭据**不许**换第二条会话
 * （重复使用如实 `ERR_CAPTURE_DENIED`，不假装还能开）。
 */
class AndroidScreenConsentToken(
    val resultCode: Int,
    val data: android.content.Intent?,
    /** 结果是否是一次成功同意（`Activity.RESULT_OK`）；false = 用户取消/系统拒。 */
    val granted: Boolean,
) : ScreenConsentToken {
    @Volatile
    private var used = false

    /** 取用（恰好一次）；第二次回 false。 */
    fun consume(): Boolean = synchronized(this) {
        if (used) return false
        used = true
        true
    }
}

/**
 * MediaProjection 会话的设备实现（§9.2）：**唯一碰 `android.media.projection.*` 的地方**。
 *
 * ## 时序（API 34 的硬要求，顺序反了就是 `SecurityException`）
 * 1. 起 mediaProjection 前台类型并**等到系统确认**（[ProjectionForeground.start]）；
 * 2. `getMediaProjection(resultCode, data)`；
 * 3. `registerCallback`（**必须在 createVirtualDisplay 之前**，否则系统可能在 display
 *    建好与回调注册之间停止投影，那次停止就没人接得住）；
 * 4. `createVirtualDisplay(...)` → `ImageReader` 的 Surface。
 *
 * ## 生命周期所有权（这一版的核心，逐条对应一个真实故障）
 * - **开/关状态机**（[lock] 下的 `opening`/`openAbandoned`）：并发第二个 `open` 如实拒绝
 *   （不是两个都过关去抢同一条 `mediaProjection`）；`open` 进行中被 [closeCurrent]/
 *   连接撤销时，本次在**每个检查点**发现并放弃 —— 不会"撤销完了又被提交上去"。
 * - **取消原样传播**：`open` 挂起点被取消（脚本崩了/连接断了）时先还清已拿到的系统资源，
 *   再抛 `CancellationException` —— 不吞成 `UNAVAILABLE`（那会让调用方以为"开失败了、
 *   可以重试"，而实际上这次取消意味着"不要了"）。
 * - **收口凭租约、恰好一次**：`VirtualDisplay`/`ImageReader`/`MediaProjection`/前台服务
 *   的释放走 [teardown]，由 `closed` 的 CAS 保证恰好一次；前台那一步带**代际**
 *   （[ForegroundLease]），所以旧会话的迟到收口**停不掉新会话的前台服务**。
 * - **系统 `onStop` 既回收也清当前**：回调先把 `current` 摘掉再还资源 ——
 *   只置一个 `stopped` 标志会让 `current != null` 永久挡住重开。
 *
 * ## 不自动重新征询授权
 * 系统收回投屏后会话转 `STOPPED`（**不自动重新弹系统对话框**，§9.2：由能力中心引导
 * 用户重授权）—— 自动弹一次是"替用户做决定"，且脚本无从知晓。
 *
 * ## 诚实的 FLAG_SECURE 边界
 * MediaProjection 的合成输出对安全窗的处理由系统决定（多数 ROM 给黑块）。本实现
 * **读不到**窗口 flag，因此
 * - **不声称**能识别安全窗，也**不**用"整帧全黑 ⇒ 安全窗"这种猜测冒充精确识别
 *   （黑屏可能只是用户真的在看黑界面）；
 * - 锁屏态走**系统查询**（`KeyguardManager`）做策略预检，与 a11y 路径同一条
 *   [ScreenPolicy]（分类错误而非黑图）；
 * - 安全窗若真的黑掉，脚本拿到的是黑帧 —— 这一点如实写在这里，不粉饰。
 *
 * ## 不接 `onCapturedContentResize`
 * 系统改了被投内容尺寸（旋转/分屏）时本实现不跟着 resize（ImageReader 尺寸在建会话时
 * 固定），下一次 `nextFrame` 仍按原尺寸给帧 —— 尺寸不匹配时系统会缩放填充，画面可能变形。
 */
class AndroidMediaProjectionSessions(
    context: Context,
    private val foreground: ProjectionForeground,
    private val frameWaitMillis: Long = DEFAULT_FRAME_WAIT_MILLIS,
) : MediaProjectionSessions {

    private val appContext = context.applicationContext
    private val mainHandler = Handler(Looper.getMainLooper())

    /** 开/关状态机与在册会话的唯一锁（状态转换都在锁内，系统调用都在锁外）。 */
    private val lock = Any()

    /** 有一条 `open` 正在跑（第二个并发 `open` 如实拒绝，而不是两条都去抢投影）。 */
    private var opening = false

    /** 正在开的那条会话的归属（`revokeOwner` 要能在 `open` 中途收掉它）。 */
    private var openingOwner: MediaProjectionSessionOwner? = null

    /** `open` 进行中被收口（`closeCurrent`/`revokeOwner`）：本次放弃，不提交到 [current]。 */
    private var openAbandoned = false

    private var current: Live? = null
    private var nextLeaseId = 1L

    /** 一条在册会话：设备资源 + 归属租约 + 前台代际。 */
    private class Live(
        val lease: SessionLease,
        val owner: MediaProjectionSessionOwner,
        val projection: MediaProjection,
        val reader: ImageReader,
        val display: VirtualDisplay,
        val callback: MediaProjection.Callback,
        val foregroundLease: ForegroundLease,
        val frames: Channel<Unit>,
    ) {
        /** 系统已收回投屏（`onStop`）—— 句柄还在，但不可再用。 */
        @Volatile
        var stopped: Boolean = false

        /** 资源是否已经还过（收口**恰好一次**的凭据）。 */
        val closed = AtomicBoolean(false)
    }

    /** 租约实现（`:domain` 的收口凭据；号由 [nextLeaseId] 单调发放，绝不复用）。 */
    private class SessionLease(
        override val leaseId: Long,
        override val owner: MediaProjectionSessionOwner,
    ) : SessionResourceLease

    override val state: MediaProjectionSessionState
        get() = when (val live = current) {
            null -> MediaProjectionSessionState.IDLE
            else -> if (live.stopped) MediaProjectionSessionState.STOPPED else MediaProjectionSessionState.ACTIVE
        }

    override val owner: MediaProjectionSessionOwner? get() = current?.owner

    @Suppress("TooGenericExceptionCaught") // 建会话的失败面很宽（系统/厂商实现），但都必须还清资源
    override suspend fun open(
        consent: ScreenConsentToken?,
        owner: MediaProjectionSessionOwner,
    ): LeasedMediaProjectionSession {
        val token = consent as? AndroidScreenConsentToken
            ?: throw MediaProjectionOpenException(
                MediaProjectionOpenFailure.DENIED,
                "没有系统投屏同意的结果（凭据缺失或类型不符）",
            )
        if (!token.granted) {
            throw MediaProjectionOpenException(MediaProjectionOpenFailure.DENIED, "用户取消了投屏授权")
        }
        if (!token.consume()) {
            throw MediaProjectionOpenException(
                MediaProjectionOpenFailure.DENIED,
                "本次系统同意已被用过（API 34+ 一次同意只换一条会话）",
            )
        }
        val data = token.data
            ?: throw MediaProjectionOpenException(MediaProjectionOpenFailure.DENIED, "同意结果里没有投屏凭据")

        // 状态机占位：并发第二个 open 拒绝；本次开的过程中被收口则下面每个检查点放弃。
        synchronized(lock) {
            if (current != null) {
                throw MediaProjectionOpenException(
                    MediaProjectionOpenFailure.DENIED,
                    "已有一条投屏会话在跑（一台设备同时只有一条）",
                )
            }
            if (opening) {
                throw MediaProjectionOpenException(
                    MediaProjectionOpenFailure.DENIED,
                    "已有一条投屏会话正在开（同一时刻只允许一条）",
                )
            }
            opening = true
            openingOwner = owner
            openAbandoned = false
        }
        var fgLease: ForegroundLease? = null
        var projection: MediaProjection? = null
        var reader: ImageReader? = null
        var display: VirtualDisplay? = null
        var callback: StopCallback? = null
        var frames: Channel<Unit>? = null
        var committed: Live? = null
        try {
            // 1) 前台类型先起、等到系统确认 —— API 34+ 的硬前提（顺序不可反）。
            fgLease = foreground.start()
                ?: throw MediaProjectionOpenException(
                    MediaProjectionOpenFailure.UNAVAILABLE,
                    "mediaProjection 前台服务起不来（缺 FOREGROUND_SERVICE_MEDIA_PROJECTION 或被系统拒）",
                )
            // 检查点①：前台那一步是阻塞的，期间可能已经被取消/被收口。
            checkAlive()
            val manager = appContext.getSystemService(MediaProjectionManager::class.java)
                ?: throw MediaProjectionOpenException(MediaProjectionOpenFailure.UNAVAILABLE, "MediaProjectionManager 不可得")
            projection = try {
                manager.getMediaProjection(token.resultCode, data)
            } catch (e: SecurityException) {
                throw MediaProjectionOpenException(MediaProjectionOpenFailure.DENIED, "系统拒绝投屏", e)
            } ?: throw MediaProjectionOpenException(
                MediaProjectionOpenFailure.DENIED,
                "系统没有给出投屏对象（凭据无效或已被用过）",
            )
            // 检查点②：拿投影之后、建显示之前。
            checkAlive()
            val size = screenSize()
            reader = ImageReader.newInstance(size.first, size.second, PixelFormat.RGBA_8888, MAX_IMAGES)
            frames = Channel(Channel.CONFLATED)
            val stopCallback = StopCallback { onProjectionStopped(projection) }
            callback = stopCallback
            // 2) 回调先注册（见类 KDoc 时序第 3 条）。
            projection.registerCallback(stopCallback, mainHandler)
            display = projection.createVirtualDisplay(
                DISPLAY_NAME,
                size.first,
                size.second,
                densityDpi(),
                DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR,
                reader.surface,
                null,
                mainHandler,
            ) ?: error("createVirtualDisplay 回 null")

            val accepted = synchronized(lock) {
                if (openAbandoned) {
                    false
                } else {
                    // 租约号在**提交点**发放（单调递增、绝不复用）：并发/迟到的收口
                    // 只有拿到同一号才算"还是我那条"。
                    val live = Live(
                        lease = SessionLease(nextLeaseId++, owner),
                        owner = owner,
                        projection = projection,
                        reader = reader,
                        display = display,
                        callback = stopCallback,
                        foregroundLease = fgLease,
                        frames = frames,
                    )
                    current = live
                    committed = live
                    true
                }
            }
            // 检查点③：建好之后才发现被收口 —— 还清资源再放弃（绝不提交上去）。
            if (!accepted) {
                // 走到这里 `committed` 仍是 null（收口路径没提交），资源都在局部变量上。
                throw CancellationException("投屏会话开启过程中被收口（连接撤销/熄屏裁剪）")
            }
            return DeviceSession(committed ?: error("会话已提交但句柄缺失"))
        } catch (e: CancellationException) {
            releasePartial(fgLease, projection, reader, display, callback, frames, committed)
            throw e
        } catch (e: MediaProjectionOpenException) {
            releasePartial(fgLease, projection, reader, display, callback, frames, committed)
            throw e
        } catch (t: Throwable) {
            releasePartial(fgLease, projection, reader, display, callback, frames, committed)
            throw MediaProjectionOpenException(
                MediaProjectionOpenFailure.UNAVAILABLE,
                "建立虚拟显示失败（${t.javaClass.simpleName}: ${t.message}）",
                t,
            )
        } finally {
            synchronized(lock) {
                opening = false
                openingOwner = null
            }
        }
    }

    /**
     * 检查点：本次 `open` 是否已被取消/被收口。
     *
     * 两个条件都要看：协程取消（脚本崩了/连接断了）与 `closeCurrent` 的放弃标记
     * （后者不一定伴随取消 —— 熄屏裁剪是在别的协程里调的）。
     */
    private suspend fun checkAlive() {
        currentCoroutineContext().ensureActive()
        if (synchronized(lock) { openAbandoned }) {
            throw CancellationException("投屏会话开启过程中被收口（连接撤销/熄屏裁剪）")
        }
    }

    /**
     * 还清 `open` 中途拿到的系统资源（**不含**在册会话 —— 那条走 [teardown]）。
     *
     * 逐项 `runCatching`：一份资源还不掉，不能让其余资源也留在半开态。
     * 前台那一步用**本代的租约**（[ProjectionForeground.stop] 带代际校验），
     * 所以它停不掉别人后来起的那一代。
     */
    private fun releasePartial(
        fgLease: ForegroundLease?,
        projection: MediaProjection?,
        reader: ImageReader?,
        display: VirtualDisplay?,
        callback: MediaProjection.Callback?,
        frames: Channel<Unit>?,
        committed: Live?,
    ) {
        // 已提交到 current 的那条（检查点③的放弃路径）走完整收口：它已经是"在册会话"了，
        // 资源形状与在册会话一模一样。`teardown` 的 CAS 保证它不会被还第二次。
        if (committed != null) {
            synchronized(lock) { if (current === committed) current = null }
            teardown(committed)
            return
        }
        frames?.close()
        display?.let { runCatching { it.release() } }
        reader?.let { runCatching { it.close() } }
        if (projection != null && callback != null) {
            runCatching { projection.unregisterCallback(callback) }
        }
        projection?.let { runCatching { it.stop() } }
        if (fgLease != null) {
            // 停不掉就**明确放弃**这一代：服务若迟到才起来，看到自己已被放弃会自己退场。
            if (!foreground.stop(fgLease)) foreground.abandon(fgLease)
        }
    }

    override fun close(lease: SessionResourceLease): Boolean {
        val live = synchronized(lock) {
            val c = current ?: return false
            // 租约对不上 = 它已经不是当前会话（已被系统收回/被新会话顶掉）：**不动手**。
            if (c.lease.leaseId != lease.leaseId || c.owner != lease.owner) return false
            current = null
            c
        }
        teardown(live)
        return true
    }

    override fun closeCurrent(): Boolean {
        val live = synchronized(lock) {
            // 正在开的那条也要放弃（否则它建完就提交，收口等于没发生）。
            openAbandoned = true
            val c = current ?: return false
            current = null
            c
        }
        teardown(live)
        return true
    }

    /**
     * 按归属收口（连接/执行撤销）：**正在开的那条也算**。
     *
     * 为什么必须覆盖"正在开的那条"：`open` 里有一段挂起（等前台确认/拿投影/建显示），
     * 撤销完全可能落在这一段里；只管已提交的会话，那条就会在建好之后照样提交上去
     * （用户看到"脚本已经没了但通知栏还在投屏"）。
     *
     * 别的归属一概不动 —— 同一台设备上别的连接（别的脚本）不该被牵连。
     */
    override fun revokeOwner(owner: MediaProjectionSessionOwner): Boolean {
        var revoked = false
        val live = synchronized(lock) {
            if (opening && openingOwner == owner) {
                openAbandoned = true
                revoked = true
            }
            val c = current
            if (c != null && c.owner == owner) {
                current = null
                revoked = true
                c
            } else {
                null
            }
        }
        if (live != null) teardown(live)
        return revoked
    }

    /**
     * 系统说停就停（`MediaProjection.Callback.onStop`）：**先摘 `current` 再还资源**。
     *
     * 只置一个 `stopped` 标志是错的 —— `current` 还在场会永久挡住重开
     * （"系统停过之后就再也开不了"）。所以这里做完整收口。
     *
     * **按投影对象身份认领**：`onStop` 可能在旧会话收口之后才到（系统回调排在主线程
     * 队列里），直接收"当前会话"会把**新会话**拆掉。只认 `projection === live.projection`
     * 那一条；[Live.closed] 的 CAS 再兜一层"恰好一次"。
     */
    private fun onProjectionStopped(projection: MediaProjection) {
        val live = synchronized(lock) {
            val c = current ?: return
            if (c.projection !== projection) return
            current = null
            c
        }
        teardown(live)
    }

    /**
     * 还清一条在册会话的系统资源（**恰好一次**）。
     *
     * 前台那一步带代际（[ForegroundLease]）：旧会话的迟到收口停不掉新会话的前台服务
     * —— 这是"独立 FGS + 代际"合起来才成立的性质，缺任一条都会让重开投屏被立刻停掉。
     */
    private fun teardown(live: Live) {
        if (!live.closed.compareAndSet(false, true)) return
        live.stopped = true
        live.frames.close()
        runCatching { live.reader.setOnImageAvailableListener(null, null) }
        runCatching { live.display.release() }
        runCatching { live.reader.close() }
        runCatching { live.projection.unregisterCallback(live.callback) }
        runCatching { live.projection.stop() }
        if (!foreground.stop(live.foregroundLease)) foreground.abandon(live.foregroundLease)
    }

    /** 会话句柄（设备面）：状态读 [Live.stopped]，不另存一份会漂的布尔。 */
    private inner class DeviceSession(private val live: Live) : LeasedMediaProjectionSession {

        override val lease: SessionResourceLease get() = live.lease
        override val width: Int get() = live.reader.width
        override val height: Int get() = live.reader.height
        override val owner: MediaProjectionSessionOwner get() = live.owner

        override val state: MediaProjectionSessionState
            get() = if (live.stopped) MediaProjectionSessionState.STOPPED else MediaProjectionSessionState.ACTIVE

        init {
            // 监听器在建句柄时装上（只装一次）：帧信号走 CONFLATED channel ——
            // 系统可能在一帧都没被消费时连续回调，channel 只保留"有新帧"这一位事实。
            live.reader.setOnImageAvailableListener({ live.frames.trySend(Unit) }, mainHandler)
        }

        override suspend fun nextFrame(): RawFrame {
            if (state != MediaProjectionSessionState.ACTIVE) {
                throw AutojsException(
                    ErrorCode.ERR_CAPTURE_DENIED,
                    "投屏会话已停止（用户/系统收回），需重新授权；本实现不自动重试授权",
                )
            }
            // 锁屏：走系统查询的策略预检（分类错误而非黑图，§8.8）。
            // 安全窗读不到 —— 见类 KDoc 的诚实边界，不在此编造识别。
            ScreenPolicy.requireCapturable(
                ScreenSnapshot(locked = keyguardLocked(), secureForeground = false, hasWindows = true),
            )
            val image = awaitImage()
                ?: throw AutojsException(ErrorCode.ERR_TIMEOUT, "等待投屏帧超时（${frameWaitMillis}ms）")
            return try {
                rgbaOf(image)
            } finally {
                // **Image 必须及时 close**：ImageReader 的对象池只有 MAX_IMAGES 个缓冲，
                // 不还回去，几帧之后 acquire 就永远拿不到东西（表现是"投屏跑一会儿就卡死"）。
                runCatching { image.close() }
            }
        }

        override suspend fun close() {
            // 幂等：把自己那一份系统资源还掉（`teardown` 的 CAS 保证恰好一次）。
            synchronized(lock) { if (current === live) current = null }
            teardown(live)
        }

        /**
         * 等下一帧（**有界**）。
         *
         * 三条都是踩过的坑：
         * - **先试一次 `acquireLatestImage`**：ImageReader 里可能已经有帧（上一帧没人取），
         *   而"有新帧"的回调不会再响一次 —— 只等信号会等满整个超时，表现是"投屏卡在
         *   第一帧之后"；
         * - **用 channel 而不是自己写的信号量**：`Semaphore(0)` 这类"许可为 0"的构造在
         *   kotlinx 里是非法的（每次取帧直接 `IllegalArgumentException`）；
         * - **协程取消/超时即退出**：`withTimeoutOrNull` 兜住，不留悬挂等待
         *   （取消时 channel 不必由这里关 —— 会话收口会关它）。
         */
        private suspend fun awaitImage(): Image? {
            live.reader.acquireLatestImage()?.let { return it }
            return withTimeoutOrNull(frameWaitMillis) {
                var image: Image? = null
                while (image == null) {
                    live.frames.receive()          // 等到"可能有新帧"的信号
                    image = live.reader.acquireLatestImage()
                }
                image
            }
        }

        /**
         * `Image` → [RawFrame]（紧密打包 RGBA，`width*height*4`，R,G,B,A 序）——
         * `ImageAnalyzer.ingest` 的像素契约（§18-8(b) 帧表共用）。
         *
         * **逐行按 `rowStride` 步进、逐像素按 `pixelStride` 取**：`ImageReader` 的缓冲区
         * 行间通常有填充（`rowStride > width*4`），直接整块读会把填充当成像素（画面斜切，
         * 且**只在某些设备上出现** —— 最难查的那一类）。`pixelStride` 同理：不假设它是 4。
         *
         * 缓冲是**共享内存**（`Image` 关闭即失效），所以这里**必须复制**出自有字节数组，
         * 不能把 buffer 交出去（那是"帧的内容随下一次投屏消失"）。
         */
        private fun rgbaOf(image: Image): RawFrame {
            val plane = image.planes.firstOrNull()
                ?: throw AutojsException(ErrorCode.ERR_IO, "投屏帧没有像素平面")
            val width = image.width
            val height = image.height
            requireFrameShape(width, height, plane.pixelStride)
            val buffer = plane.buffer
            val pixelStride = plane.pixelStride
            val rowStride = plane.rowStride
            val out = ByteArray(width * height * 4)
            val row = ByteArray(rowStride)
            var o = 0
            for (y in 0 until height) {
                buffer.position(y * rowStride)
                val n = minOf(rowStride, buffer.remaining())
                buffer.get(row, 0, n)
                for (x in 0 until width) {
                    val p = x * pixelStride
                    if (p + 3 >= n) break
                    out[o++] = row[p]         // R（RGBA_8888 的内存序）
                    out[o++] = row[p + 1]     // G
                    out[o++] = row[p + 2]     // B
                    out[o++] = row[p + 3]     // A
                }
            }
            return RawFrame(out, width, height)
        }

        private fun requireFrameShape(width: Int, height: Int, pixelStride: Int) {
            if (width <= 0 || height <= 0) {
                throw AutojsException(ErrorCode.ERR_IO, "投屏帧尺寸非法 ${width}x$height")
            }
            if (pixelStride < 4) {
                throw AutojsException(ErrorCode.ERR_IO, "投屏帧像素步长非法 $pixelStride（RGBA 至少 4）")
            }
        }
    }

    /**
     * `MediaProjection.Callback` 的具名实现（匿名合成名进不了 ArchUnit 的服务面豁免名单）。
     *
     * 构造参数叫 `notify` 而不是 `onStop`：后者会与 `override fun onStop()` **同名**，
     * 方法体里写 `onStop()` 会解析成递归调用自己（编译器报 "recursive problem"）。
     */
    private class StopCallback(private val notify: () -> Unit) : MediaProjection.Callback() {
        override fun onStop() = notify()
    }

    private fun keyguardLocked(): Boolean =
        appContext.getSystemService(KeyguardManager::class.java)?.isDeviceLocked ?: false

    private fun densityDpi(): Int = appContext.resources.displayMetrics.densityDpi

    @Suppress("DEPRECATION") // getRealMetrics：投屏要物理像素尺寸（API30+ 的 WindowMetrics 面另接）
    private fun screenSize(): Pair<Int, Int> {
        val wm = appContext.getSystemService(WindowManager::class.java)
        if (wm != null) {
            val metrics = android.util.DisplayMetrics()
            wm.defaultDisplay.getRealMetrics(metrics)
            if (metrics.widthPixels > 0 && metrics.heightPixels > 0) {
                return metrics.widthPixels to metrics.heightPixels
            }
        }
        val dm = appContext.resources.displayMetrics
        return dm.widthPixels to dm.heightPixels
    }

    companion object {
        private const val DISPLAY_NAME = "autoscript-projection"

        /** 对象池大小（§9.2 `maxImages=2~3`）：够双缓冲，又不会把整屏帧囤在内存里。 */
        const val MAX_IMAGES = 3

        /** 单帧等待上限：投屏帧率通常 60fps，等不到就是内容没更新/会话已死 —— 有界。 */
        const val DEFAULT_FRAME_WAIT_MILLIS = 2_000L
    }
}
