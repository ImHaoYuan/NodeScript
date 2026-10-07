package com.autoscript.domain.engine

/**
 * 执行身份签发缝（§7.5/§8.2）：先登记，后 spawn，避免第一帧早于宿主建账。
 * 实现由装配根给；引擎只见领域契约，不依赖桥、Android 或 socket 类型。
 */
fun interface RunIdentityIssuer {
    fun issue(engineId: EngineId, engineRunId: Long): RunIdentityLease
}

/** 一次执行独占的凭据及生命周期；不是 scheduler 的幂等 runNonce。 */
interface RunIdentityLease {
    /** 仅供启动环境传给执行体；不打印、不持久化、不写进诊断/toString。 */
    val token: String

    /** spawn 已返回；PID 不可得仍可认证，活性只用于握手，不能拿来拒绝已绑定连接的尾帧。 */
    fun confirmSpawn(pid: Int?, isAlive: () -> Boolean)

    /** 自然退出：禁止新握手，已绑定连接保留归属并有界排空。重复调用不延长期限。 */
    fun naturalExit()

    /** 启动失败/取消/主动停止：立即撤销，取消在途请求并关闭连接。幂等。 */
    fun revoke()
}
