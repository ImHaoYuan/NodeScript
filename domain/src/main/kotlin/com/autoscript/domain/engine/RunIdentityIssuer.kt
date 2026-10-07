package com.autoscript.domain.engine

import com.autoscript.domain.permission.ScriptAuthorizationSnapshot

/**
 * 执行身份签发缝（§7.5/§8.2）：先登记，后 spawn，避免第一帧早于宿主建账。
 * 实现由装配根给；引擎只见领域契约，不依赖桥、Android 或 socket 类型。
 *
 * **A5 起它同时是授权快照的落点**：[authorization] 是宿主对**这一次执行**已经算好的
 * 不可变授权（掩码 + 来源档），由 `RuntimeController.start` 一次算出、随请求一路传到这里
 * —— 签发方**不再自己算**（那会开一个「预检算一套、签发算另一套」的 TOCTOU 窗口，
 * 见 `ScriptAuthorizationPolicy` 类 KDoc 第 2 条）。实现方把它原样存进 lease，
 * 认证时随 [com.autoscript.domain.bridge.AuthenticatedRunContext.capabilityMask] 下发。
 * 引擎只搬运这份快照，**不做授权判断** —— 策略住在宿主一侧，引擎无权决定自己拿到什么票。
 *
 * @param projectId 项目号，**仅供审计/诊断**（来源判定已在 [authorization] 里做完，
 *   本参数不得被实现方拿去重新推断来源 —— 那是脚本可影响的字段）。
 */
fun interface RunIdentityIssuer {
    fun issue(
        engineId: EngineId,
        engineRunId: Long,
        projectId: String,
        authorization: ScriptAuthorizationSnapshot,
    ): RunIdentityLease
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
