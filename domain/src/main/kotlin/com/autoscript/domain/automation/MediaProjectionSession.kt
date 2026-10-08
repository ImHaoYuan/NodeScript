package com.autoscript.domain.automation

import com.autoscript.domain.bridge.AuthenticatedRunContext
import com.autoscript.domain.core.AutojsException
import com.autoscript.domain.core.ErrorCode
import com.autoscript.domain.engine.EngineId

/**
 * 系统投屏同意凭据（§9.2 MediaProjection **会话语义**）。
 *
 * **不透明标记**：内容由设备层定义（`(resultCode, Intent)` 那一对），`:domain` 与桥面
 * handler 只做搬运与归属校验，**绝不解析**。之所以要一个标记类型而不是裸 `Any?`：
 * 让"这里要的是一次系统同意"在签名上看得见，同时不把 Android 类型拖进 `:domain`。
 *
 * **一次同意只能换一次会话**：API 34 起系统不再复用同意凭据（`getMediaProjection` 每次
 * 都要一次新的用户确认），所以本标记是**一次性**的 —— 设备层取用后即失效，重复使用
 * 如实报 `ERR_CAPTURE_DENIED`，不假装还能开第二条会话。
 */
interface ScreenConsentToken

/** 一次系统投屏同意的结果（`payload` = 平台的结果 `Intent`，`:domain` 不解析）。 */
data class ScreenConsentOutcome(
    /** 对话框有没有被拉起（false = 系统里没有这个界面/没有存活的 Activity）。 */
    val launched: Boolean,
    /** 平台的结果码（`Activity.RESULT_OK` 等；未拉起时无意义）。 */
    val resultCode: Int,
    /** 结果载荷（`Intent`）。 */
    val payload: Any?,
    /**
     * 没拉起时的原因（诊断用，会进日志与异常文案）。
     *
     * 为什么要它：`launched = false` 有三种来路（界面已销毁 / 系统没这个界面 /
     * 已有一轮征询在跑），现场只看一个布尔分不出来 —— 而这三者该做的事完全不同。
     * `launched = true` 时无意义。
     */
    val detail: String? = null,
) {
    /** 用户是否**真的同意**了（拉起了对话框 **且** 结果是 RESULT_OK）。 */
    val granted: Boolean get() = launched && resultCode == RESULT_OK

    companion object {
        /**
         * `Activity.RESULT_OK`（-1）。
         *
         * 写成常量而不是 import `android.app.Activity`：`:domain` 零 Android 依赖，
         * 而这个值本身是平台契约的一部分（宿主传进来的就是 Activity 的结果码）。
         */
        const val RESULT_OK = -1

        /** 没能把用户送到授权页（拉不起 / 上一次对话框还开着 / 界面已销毁）。 */
        fun notLaunched(detail: String? = null): ScreenConsentOutcome =
            ScreenConsentOutcome(launched = false, resultCode = 0, payload = null, detail = detail)
    }
}

/**
 * 系统投屏同意对话框的宿主缝（§9.2）。
 *
 * **为什么必须是 Activity**：`MediaProjectionManager.createScreenCaptureIntent()` 的结果
 * 只能经 `onActivityResult` 回来，而 `:app` 装配层（Application）没有 Activity —— 于是
 * 呈现层（`:ui` 的 `MainActivity`）实现本缝并注册进 [ScreenConsentHolder]，`:app` 在脚本
 * 需要一次会话时取用。与 `A11yServiceHolder`/`ForegroundHost` 同形：**谁能提供就谁写，
 * 装配层只读**，两边不互相 import。
 *
 * 两个方法是**同一件事的两种等待方式**，不是两条路径：
 * - [requestScreenConsent]（挂起）：脚本那次 `startCapturer` 走这条 —— 它要结果；
 * - [requestScreenConsentDetached]（非挂起）：能力中心「去授权」那一行走这条 ——
 *   它是 UI 线程上的 `fun open(page): Boolean`，只能问"拉起来了没有"。**结果不丢**：
 *   落进 [ScreenConsentInbox] 待领，由**下一次** `startCapturer` 一次性领走
 *   （投屏没有持久 grant，丢了就等于让用户白同意一次）。
 */
interface ScreenConsentHost {
    /** 拉起系统投屏同意对话框并**等结果**；**不抛**（拉不起如实回 `launched = false`）。 */
    suspend fun requestScreenConsent(): ScreenConsentOutcome

    /**
     * 只把对话框拉起来，不等结果。
     * @return true = 已经交给系统（用户看到对话框了）；false = 拉不起（没有界面/已在弹）。
     */
    fun requestScreenConsentDetached(): Boolean
}

/**
 * 进程内注册信箱（§9.2）：呈现层写（Activity `onCreate` 注册、`onDestroy` 注销），
 * 装配层读。**没有存活的界面 = 没人能问用户** —— 此时 `:app` 如实拒绝会话开启
 * （`ERR_CAPTURE_DENIED`），绝不假装问过了。
 */
object ScreenConsentHolder {
    @Volatile
    var host: ScreenConsentHost? = null
}

/**
 * 投屏凭据的征得口（`:app` 装配层实现，handler 持有）。
 *
 * 为什么是"回一个凭据"而不是"handler 自己去拉 Activity"：handler 住
 * `:platform:capabilities`，碰不到 Activity（也不该碰）。装配层把
 * 「[ScreenConsentHolder.host] → 系统对话框 → 结果 → 设备层凭据」这一串折成一个挂起调用，
 * handler 只管「需要一次同意就调它」。
 *
 * **失败一律抛 `ERR_CAPTURE_DENIED`**（用户取消 / 拉不起 / 宿主未接线）——
 * 那是脚本可判别的分类错误，**绝不静默改走别的截图通道**（a11y 截图是另一条独立能力，
 * 见 `ScreenNamespaceHandler` 的两个帧源各自独立接线）。
 */
fun interface ScreenConsentBroker {
    suspend fun requestConsent(): ScreenConsentToken
}

/** 投影会话的生存态（§9.2）。`STOPPED` = 系统/用户已收回投屏，会话**不可再用**。 */
enum class MediaProjectionSessionState {
    /** 没有会话（从未开过，或上一次已彻底收口）。 */
    IDLE,

    /** 会话存活，可 `nextFrame`。 */
    ACTIVE,

    /** 系统已停止投屏（`MediaProjection.Callback.onStop`）—— 句柄还在但**不可再用**。 */
    STOPPED,
}

/**
 * 会话归属（§9.2 安全面）：一次 MediaProjection 会话属于**一条已认证的桥连接**
 * （[AuthenticatedRunContext] 的三元组：引擎槽 + 执行号 + 连接号）。
 *
 * **为什么必须有归属**：投屏会话是**进程级单资源**（一台设备同时只有一个
 * `VirtualDisplay`），而 handler 是所有脚本共用的单例。没有归属校验时，任何脚本猜到
 * 一个会话 id 就能读别人的屏幕、关别人的会话 —— 那是"低信任脚本越权读屏"。
 * 归属 + 代际两道校验之后：**只有开它的那条连接**（且必须是同一代）能操作它。
 *
 * **判据是这三个字段本身，不是"以后加字段会自动变严"**：`==` 逐字段比，
 * 将来身份结构变了（加字段、改语义）就必须**同时**改本类型与比较 —— 这条是明写的
 * 契约，不是靠数据类自动继承的性质（原 KDoc 曾声称"整个对象比就自动含新维度"，
 * 那只在本类型跟着加字段时成立，写在这里免得被当成免费的保证）。
 *
 * **为什么含 [connectionId]**：执行号（`engineRunId`）标识一次脚本运行，但同一次运行
 * 可以有多条连接（重连、宿主直连）；连接号是"这次撤销/这条 socket"的粒度 ——
 * 连接一断，它名下开出来的会话就该被收掉（见
 * [com.autoscript.domain.bridge.ConnectionResourceRegistry]），而同一 run 的另一条
 * 连接不该被牵连。只按 `engineRunId` 比会把两条连接混成一个主人。
 */
data class MediaProjectionSessionOwner(
    val engineId: EngineId,
    val engineRunId: Long,
    val connectionId: Long,
)

/**
 * 从协程上下文里的认证身份取会话归属（§9.2 安全面）。
 *
 * **没有身份 = 不是一条可信的脚本请求** → 如实 `ERR_PERMISSION_DENIED`，
 * **绝不放行**（放行等于"任何没身份的东西都能读屏"）。宿主直调（可信本地调用）
 * 若确实要开会话，也必须在协程上下文里带上 [AuthenticatedRunContext]。
 */
suspend fun mediaProjectionOwnerOrThrow(): MediaProjectionSessionOwner {
    val ctx = kotlin.coroutines.coroutineContext[AuthenticatedRunContext]
        ?: throw AutojsException(
            ErrorCode.ERR_PERMISSION_DENIED,
            "本连接没有已认证的执行身份，不能开启投屏会话",
        )
    return MediaProjectionSessionOwner(ctx.engineId, ctx.engineRunId, ctx.connectionId)
}

/**
 * 把一条会话登记进**本连接**的资源收口口（§9.2 会话资源）：连接一断，
 * 设备层就把这条会话收掉（`VirtualDisplay`/`ImageReader`/`mediaProjection` 前台一并还回去）。
 *
 * **为什么要登记而不是等 `SCREEN_OFF`**：熄屏裁剪只覆盖"用户关了屏"那一种结束，
 * 而脚本崩溃、被看门狗掐、socket 断开、宿主 `close()` 都不经过它 —— 那些路径上
 * 会话会一直挂着（通知栏一条"正在投屏"、`VirtualDisplay` 占着编码器）。收口点放在
 * **连接层**，覆盖全部结束方式，包括"会话开到一半就被撤销"（迟到的登记会立刻兑现）。
 *
 * @param onRevoke 收口动作（必须幂等且不抛；可能在任意线程上被调）。
 */
fun AuthenticatedRunContext.registerConnectionResource(onRevoke: () -> Unit) {
    resources.register(onRevoke)
}

/** 会话开不成的分类原因（设备层 → handler → 桥面错误码的映射输入）。 */
enum class MediaProjectionOpenFailure {
    /** 用户拒绝 / 凭据无效 / 凭据已被用过 —— 桥面 `ERR_CAPTURE_DENIED`。 */
    DENIED,

    /** 本机没有可用的投屏通道（无 Activity 宿主、系统服务缺失）—— 桥面 `ERR_SERVICE_DISABLED`。 */
    UNAVAILABLE,
}

/** 会话开启失败的分类异常（handler 折成桥面错误码；`DENIED` → `ERR_CAPTURE_DENIED`）。 */
class MediaProjectionOpenException(
    val failure: MediaProjectionOpenFailure,
    detail: String,
    /** 底层原因（`SecurityException` 等）；只作诊断用，不进桥面文案。 */
    cause: Throwable? = null,
) : RuntimeException(detail, cause)

/**
 * 一帧**尚未发号**的像素（紧密打包 RGBA，`width*height*4`，R,G,B,A 序）。
 *
 * 设备层（投屏）只交像素、不交句柄：帧句柄必须经 `ImageAnalyzer.ingest` 发号，
 * 才能与 `images.decode` 的帧同表互认（§18-8(b)）。设备层凭空造一个 `refId`
 * 就是造一个别人认不出的野句柄 —— 所以这一层刻意不带 [com.autoscript.domain.bridge.HandleRef]。
 */
data class RawFrame(val rgba: ByteArray, val width: Int, val height: Int) {
    override fun equals(other: Any?): Boolean =
        other is RawFrame && width == other.width && height == other.height && rgba.contentEquals(other.rgba)

    override fun hashCode(): Int = (width * 31 + height) * 31 + rgba.contentHashCode()
}

/**
 * 一条存活的投影会话（设备面实现；`:domain` 只认这几个动作）。
 *
 * [state] 在系统收回投屏（用户从通知栏停止 / 系统回收）后转 [MediaProjectionSessionState.STOPPED]
 * —— **回调是唯一事实来源**，不是"我们请求过 stop"。
 */
interface MediaProjectionSession {
    val width: Int
    val height: Int
    val state: MediaProjectionSessionState

    /** 归属（开它的那个执行；由 [MediaProjectionSessions.open] 记下，非空）。 */
    val owner: MediaProjectionSessionOwner

    /**
     * 取下一帧像素（有界等待，见实现；超时如实抛 `ERR_TIMEOUT`）。
     * 会话已 [MediaProjectionSessionState.STOPPED] / 已 close → 抛 `ERR_CAPTURE_DENIED`。
     */
    suspend fun nextFrame(): RawFrame

    /** 收口（幂等）：释放 VirtualDisplay/ImageReader/投影，并撤下 mediaProjection 前台类型。 */
    suspend fun close()
}

/**
 * 会话生命周期 SPI（§9.2）：handler 经它开/查/收会话，**进程级单会话**由实现保证。
 *
 * 实现住设备层（`com.autoscript.platform.capabilities.device`，唯一许碰 `android..` 的子包），
 * 语义面（归属/代际/错误分类）在 `MediaProjectionSource` + handler，纯 JVM 可测。
 *
 * **归属校验做两层**：语义层（`MediaProjectionSource`）先用 [owner] 挡住越权调用，
 * 设备层再按同一判据自查一遍 —— 两层都在，任一层出漏子都不至于让另一个脚本读到屏。
 */
interface MediaProjectionSessions {
    val state: MediaProjectionSessionState

    /** 当前会话归属（无会话 = null）。 */
    val owner: MediaProjectionSessionOwner?

    /**
     * 开一条会话：**用掉**一次系统同意（[consent] 一次性），起 mediaProjection 前台类型，
     * 建 VirtualDisplay + ImageReader。
     *
     * 返回的是**带租约**的句柄：收口（[close]）凭租约证明"我收的还是我那条"，
     * 于是旧会话的迟到收口动不了新会话（见 [SessionResourceLease]）。
     *
     * @throws MediaProjectionOpenException 凭据无效/已用过/系统拒（`DENIED`），
     *   或本机无可用投屏通道（`UNAVAILABLE`）。**取消**（`CancellationException`）
     *   原样传播 —— 那不是"开失败"，调用方要能区分。
     */
    suspend fun open(consent: ScreenConsentToken?, owner: MediaProjectionSessionOwner): LeasedMediaProjectionSession

    /**
     * 凭**租约**收口（幂等）：只有租约号仍对应当前会话时才动手。
     *
     * **非挂起**是刻意的：收口点之一是**连接撤销**（[com.autoscript.domain.bridge.ConnectionResourceRegistry]
     * 的回调是普通函数，可能在连接线程/撤销线程上被调），那里没有协程可挂。
     * 设备层的收口本来就是同步的（`VirtualDisplay.release` / `ImageReader.close` /
     * `MediaProjection.stop` 都不挂起）。
     *
     * @return true = 确实收掉了这一条；false = 它已经不是当前会话（已被别处收掉/已被
     *   新会话顶掉）—— 如实不动手，**绝不**顺手把当前会话拆了。
     */
    fun close(lease: SessionResourceLease): Boolean

    /**
     * **系统级收口**（熄屏裁剪 / 进程收口 / 连接撤销）：不问归属，直接停当前会话。
     *
     * 与 [close] 的分工：那是**脚本面**的收口（必须校验归属，否则一个脚本能断别人的流）；
     * 本方法是**框架自己**的收口路径（`SCREEN_OFF` 裁剪画面类能力、连接撤销、进程终止），
     * 调用方是装配层而不是某个执行 —— 此时"是谁的会话"不重要，重要的是**投屏必须停**
     * （§8.8：keyguard 下投屏给的是黑帧，留着它只会让脚本拿到黑图还以为成功）。
     *
     * **幂等**（关两次与关一次同效）、**不抛**（收口路径上的异常只会让资源留在半开态，
     * 调用方无从处理）：重复调用与"本来就没有会话"都回 false。
     *
     * @return true = 这一次调用确实停掉了一条；false = 本来就没有会话（幂等路径）。
     */
    fun closeCurrent(): Boolean

    /**
     * **按归属收口**（连接/执行撤销）：收掉这个归属名下的会话 —— **包括正在开的那一条**
     * （`open` 还没返回时连接就断了也算）。别的归属一概不动。
     *
     * 为什么必须覆盖"正在开的那条"：`open` 里有一段挂起（等前台确认/拿投影/建显示），
     * 撤销完全可能落在这一段里；只管已提交的会话，那条就会在建好之后**照样提交上去**
     * （用户看到"脚本已经没了但通知栏还在投屏"）。
     *
     * **非挂起**（撤销回调是普通函数，可能在任意线程被调）、**幂等**、**不抛**。
     *
     * @return true = 这一次确实收掉了什么。
     */
    fun revokeOwner(owner: MediaProjectionSessionOwner): Boolean
}

/**
 * 一次投屏会话的**资源租约**（§9.2 生命周期所有权）：收口必须证明"我收的还是我那条"。
 *
 * **为什么需要它**：会话 id 会被复用（关掉再开后 id 空间照旧），而收口有三条来路 ——
 * 脚本 `close`、系统 `onStop` 回调、框架 `closeCurrent`/连接撤销。没有租约时，
 * "旧会话的迟到收口"与"新会话的收口"在资源层长得一模一样：旧的那次会把**新会话的
 * 前台服务/虚拟显示**一起拆掉（表现是"重开投屏后立刻被停"）。
 *
 * 租约是**单调递增的收口代际**：设备层每次真正建起一条会话就发一个新号，收口时带上
 * 自己那份 —— 设备层只对**号还对得上**的那条动手，否则如实回 false（不动手）。
 *
 * 租约同时是**幂等凭据**：同一份租约被收两次，第二次回 false（资源已经还过了）。
 */
interface SessionResourceLease {
    /** 收口代际（设备层发放，单调递增）。 */
    val leaseId: Long

    /** 归属（收口时按它判"是不是我这条"）。 */
    val owner: MediaProjectionSessionOwner
}

/**
 * 投屏会话的**设备资源面**（§9.2）：设备层交出来的会话句柄除了取帧，还能回答
 * "我这份资源还归不归我"。
 *
 * 与 [MediaProjectionSession] 分开是为了让语义层（[MediaProjectionCapturer] 的实现）
 * 不必知道设备资源的形状：它只搬运租约、按租约收口。JVM 替身实现本接口即可测
 * 全套代际/幂等判据，不需要 Android。
 */
interface LeasedMediaProjectionSession : MediaProjectionSession {
    /** 本条会话的设备资源租约。 */
    val lease: SessionResourceLease
}

/**
 * 投影会话的桥面错误分类（handler 与设备层共用一处，免得两处各写一张表漂移）。
 */
fun MediaProjectionOpenException.toAutojsException(): AutojsException = when (failure) {
    MediaProjectionOpenFailure.DENIED ->
        AutojsException(ErrorCode.ERR_CAPTURE_DENIED, message ?: "投屏授权被拒")
    MediaProjectionOpenFailure.UNAVAILABLE ->
        AutojsException(ErrorCode.ERR_SERVICE_DISABLED, message ?: "本机没有可用的投屏通道")
}
