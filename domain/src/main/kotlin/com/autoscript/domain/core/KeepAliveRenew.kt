package com.autoscript.domain.core

/**
 * 保活续期窄缝（docs §8.7）—— 电源 handler 迁 `:platform:system` 时引入：
 * handler 构造期要挂一次 FGS 补拉，但 `ForegroundKeeper`（真身）住 `:app`，
 * 平台模块不得反向见 :app（§6 依赖方向）。于是只抽它**已有的**那一个动作成缝：
 * [renew] 的签名与 `ForegroundKeeper.renew` 逐字吻合，`:app` 侧直接实现本接口，
 * 装配层把同一实例喂进 handler（脚本锁与框架锁仍是**同一本账**，见
 * `PowerManagerNamespaceHandler` 的 FGS 补拉段）。
 *
 * 为什么不把整个 keeper 塞进 :domain：keeper 持有 ticker/服务编排/ForegroundOps
 * （Android 触点），抽全量等于把 §8.7 的实现搬进领域层 —— 缝只需要"acquire 后
 * 拉一次服务"这一个动作。
 */
fun interface KeepAliveRenew {

    /**
     * 续期/清理一轮，返回本轮动作的如实描述（无动作 = 空列表）。
     * 幂等：没有可做的时什么都不做、不记新账（调用方只作诊断，不看返回值判成败）。
     */
    fun renew(): List<String>
}
