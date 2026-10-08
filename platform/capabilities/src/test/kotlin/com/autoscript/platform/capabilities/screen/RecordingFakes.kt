package com.autoscript.platform.capabilities.screen

import com.autoscript.domain.automation.LeasedScreenRecording
import com.autoscript.domain.automation.MediaProjectionOpenException
import com.autoscript.domain.automation.MediaProjectionSessionOwner
import com.autoscript.domain.automation.MediaProjectionSessionState
import com.autoscript.domain.automation.RecordingOutcome
import com.autoscript.domain.automation.ScreenConsentToken
import com.autoscript.domain.automation.ScreenRecordingSessions
import com.autoscript.domain.automation.ScreenRecordingSpec
import com.autoscript.domain.automation.SessionResourceLease
import java.util.concurrent.atomic.AtomicLong
import kotlinx.coroutines.CompletableDeferred

/**
 * 录屏腿的设备面替身（`MediaProjectionRecorderTest` / `ScreenNamespaceHandlerRecordingTest` 共用）。
 *
 * 与 [FakeSessions]（取帧腿）**并列而不是合并**：两条腿的 SPI 本来就是分开的两张方法表
 * （见 `ScreenRecordingSessions` 的 KDoc），替身合成一个会让"录屏腿只依赖自己那条缝"
 * 这条约束在测试里也失效 —— 而它正是分型的目的。
 *
 * **逐条复刻真设备层**（`AndroidMediaProjectionSessions` 的录屏分支）的三件事，否则语义层
 * 测试是在测一个比真设备层宽松的世界：
 * 1. **收口恰好一次**：`stop()` 第二次回**同一次** `RecordingOutcome`（真设备层由
 *    `RecordingLive.outcome` 缓存 + `Live.closed` 的 CAS 保证）；
 * 2. **归属按三元组比**：`revokeRecordingOwner` 只收自己那条（含"正在开的那条"）；
 * 3. **开启中被收口 → `CancellationException`**，且那条会话的文件已 finalize
 *    （真设备层在 `commit` 后才发现被收口时会 `detachAndTeardown`）。
 *
 * 不模拟的：真 mp4 的字节内容（要 `MediaRecorder`，JVM 上复现不了）。`sizeBytes` 由替身
 * 按 [FakeRecording.fileBytes] 给 —— 它验的是"语义层把设备层报的数原样带回"，不是编码。
 */
internal class FakeRecordingSessions : ScreenRecordingSessions {

    var failWith: MediaProjectionOpenException? = null

    /** 每次 `startRecording` 的 (归属, 规格) —— 规格是断言"落点由语义层算"的入口。 */
    val startCalls = mutableListOf<Pair<MediaProjectionSessionOwner, ScreenRecordingSpec>>()

    var closeCurrentCalls = 0
    var revokeCalls = 0

    /** 在册的那条（真设备层是 `current` 槽）。 */
    var current: FakeRecording? = null
        private set

    /** 正在开的那条（`startRecording` 挂在 [gateOpen] 上时非 null）。 */
    var pendingOpen: FakeRecording? = null
        private set

    /** 让下一次 `startRecording` 挂住（模拟"前台确认/建编码器"那段挂起）。 */
    var gateOpen: Boolean = false
    private var gate: CompletableDeferred<Unit>? = null

    /** 框架侧收口落在挂起点里（真设备层的 `openAbandoned`）：建完也不提交。 */
    private var abandonPending = false

    /** 系统单方面收回（`MediaProjection.Callback.onStop`）后的状态事实。 */
    private var systemStopped = false

    private val leaseIds = AtomicLong(1)
    private val revoked = HashSet<MediaProjectionSessionOwner>()

    override val recordingState: MediaProjectionSessionState
        get() = current?.state
            ?: if (systemStopped) MediaProjectionSessionState.STOPPED else MediaProjectionSessionState.IDLE

    override val recordingOwner: MediaProjectionSessionOwner? get() = current?.owner

    override suspend fun startRecording(
        consent: ScreenConsentToken?,
        owner: MediaProjectionSessionOwner,
        spec: ScreenRecordingSpec,
    ): LeasedScreenRecording {
        failWith?.let { throw it }
        startCalls += owner to spec
        val session = FakeRecording(SessionLease(leaseIds.getAndIncrement(), owner), owner, spec.path)
        pendingOpen = session
        abandonPending = false
        if (gateOpen) {
            val g = CompletableDeferred<Unit>()
            gate = g
            g.await()
        }
        pendingOpen = null
        // 开启期间被撤销 / 被框架收口：真设备层回 CancellationException，**文件已 finalize**。
        if (revoked.contains(owner) || abandonPending) {
            session.finalizeIt()
            throw kotlinx.coroutines.CancellationException("录屏会话开启过程中被收口（连接撤销/熄屏裁剪）")
        }
        current = session
        systemStopped = false
        return session
    }

    /** 放行被 [gateOpen] 挂住的那次 `startRecording`。 */
    fun finishOpen() {
        gate?.complete(Unit)
        gate = null
    }

    override fun closeCurrentRecording(): Boolean {
        closeCurrentCalls++
        // 正在开的那条也要放弃（真设备层置 `openAbandoned`）。
        if (pendingOpen != null) abandonPending = true
        val c = current ?: return pendingOpen != null
        c.finalizeIt()
        current = null
        return true
    }

    override fun revokeRecordingOwner(owner: MediaProjectionSessionOwner): Boolean {
        revokeCalls++
        revoked += owner
        var revokedSomething = false
        pendingOpen?.let { if (it.owner == owner) revokedSomething = true }
        val c = current
        if (c != null && c.owner == owner) {
            c.finalizeIt()
            current = null
            revokedSomething = true
        }
        return revokedSomething
    }

    /** 模拟系统收回：真设备层会**连 current 一起清掉**并当场 finalize。 */
    fun systemStop() {
        val c = current ?: return
        c.finalizeIt()
        c.markStopped()
        current = null
        systemStopped = true
    }
}

/** 一条在册的录屏会话（真设备层是 `RecordingLive`）。 */
internal class FakeRecording(
    override val lease: SessionResourceLease,
    override val owner: MediaProjectionSessionOwner,
    override val path: String,
) : LeasedScreenRecording {

    /** 假的"落盘字节数"；`0` 模拟"一帧都没录到"。 */
    var fileBytes: Long = 4_096

    /** 模拟 `MediaRecorder.stop()` 失败（一帧都没录到）：结果如实 `completed = false`。 */
    var failOnStop: String? = null

    /**
     * **真的**收口次数（真设备层由 `Live.closed` 的 CAS 保证恰好一次）。
     *
     * 幂等路径（`stop()` 第二次 / 撤销之后再问一次）**不涨**：它读的是缓存结果，
     * 没有第二次 `MediaRecorder.stop()` —— 涨了就说明有人真的重复 finalize 了。
     */
    var finalizeCalls = 0
        private set

    private var live: MediaProjectionSessionState = MediaProjectionSessionState.ACTIVE
    private var outcome: RecordingOutcome? = null

    override val state: MediaProjectionSessionState get() = live

    override fun stop(): RecordingOutcome {
        finalizeIt()
        // 幂等：第二次回**同一次**结果（真设备层读 `RecordingLive.outcome` 缓存）。
        return outcome ?: error("替身状态不一致：finalize 之后没有结果")
    }

    /** 收口的共同落点（非挂起，供 `FakeRecordingSessions` 的框架收口路径用）。 */
    fun finalizeIt() {
        if (outcome != null) return
        finalizeCalls++
        val detail = failOnStop
        outcome = RecordingOutcome(
            path = path,
            sizeBytes = if (detail != null) 0L else fileBytes,
            completed = detail == null,
            detail = detail,
        )
        live = MediaProjectionSessionState.STOPPED
    }

    /** 系统单方面收回：**不是**我们的 stop（[finalizeCalls] 不涨，但文件仍被 finalize）。 */
    fun markStopped() {
        live = MediaProjectionSessionState.STOPPED
    }
}
