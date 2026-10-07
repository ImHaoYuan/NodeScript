package com.autoscript.domain.permission

/**
 * 一次执行的授权决策（A5）：掩码 + 判定依据（审计/日志用；[tier] 为 null = 无来源元数据）。
 *
 * 依据随决策一起交出来，是因为「为什么这个脚本拿的是窄掩码」在事后必须答得上来 ——
 * 只回一个掩码位图，现场只能靠猜。
 *
 * **[snapshot] 是「本次执行被授权到什么」的那份不可变事实**（A5 目标侧授权读它）：
 * 它在**一次 spawn 决策里只算一次**，随后贯穿 预检 → 池请求 → 引擎请求 → 身份签发 → 连接
 * 上下文。跨脚本目标授权（`CrossScriptAuthorizer`）拿它比较调用方与**目标**的授权，
 * 因此它必须与目标实际拿到的掩码是**同一份对象**，不能事后重算。
 */
data class ScriptAuthorization(
    val projectId: String,
    val tier: TrustTier?,
    val mask: CapabilityMask,
) {
    /** 审计可读形；**不含**凭据/token（那不属于授权决策）。 */
    override fun toString(): String =
        "ScriptAuthorization(projectId=$projectId, tier=${tier ?: "UNKNOWN"}, mask=$mask)"

    /**
     * 跨层携带的紧凑快照（[CapabilityMask] 是值类，`Int` 下标即掩码位图本身）。
     *
     * 为什么需要它而不是直接把 [ScriptAuthorization] 传下去：目标侧授权只需要
     * 「这个 run 的来源档 + 它的掩码」两件事，且它要活到**引擎进程宿主**那一层
     * （`:engine:node-process` 只依赖 `:domain`，拿不到 `:app-service` 的策略对象）。
     * 快照把这两件事压成一个可跨层复制的值；[tier] 用 `Int` 下标而不是枚举，是因为
     * 该层只需要「是不是无证据档」这一个判定，不需要枚举语义。
     */
    fun snapshot(): ScriptAuthorizationSnapshot =
        ScriptAuthorizationSnapshot(mask = mask, tierOrdinal = tier?.ordinal ?: NO_TIER_ORDINAL)

    companion object {
        /** [ScriptAuthorizationSnapshot.tierOrdinal] 的哨兵：无来源元数据（[TrustTier.UNKNOWN] 之外的"查不到"）。 */
        const val NO_TIER_ORDINAL: Int = -1
    }
}

/**
 * 一次执行的授权快照（见 [ScriptAuthorization.snapshot]）：掩码 + 来源档下标。
 *
 * **它是「目标这次执行实际被授权到什么」的事实载体**，由 [RuntimeController.start] 一次算出、
 * 原样透传到身份签发（`RunIdentityIssuer.issue`）—— 授权判据读到的就是签发出去的那一份，
 * 不存在「校验一套、签发另一套」的窗口（A5 整改第 4 条）。
 *
 * 刻意**不带 projectId**：目标侧授权只需要掩码与来源档；把项目号一起搬过桥/进程边界
 * 会让它看起来像"可上报的字段"，而它从来不是（来源判定只在宿主侧算一次）。
 */
data class ScriptAuthorizationSnapshot(
    val mask: CapabilityMask,
    /**
     * 来源档下标；[ScriptAuthorization.NO_TIER_ORDINAL] = 无来源元数据。
     *
     * 存下标而非枚举，是因为本快照要住进 `:domain` 的**引擎 SPI**（`EngineRunRequest`），
     * 而 SPI 只依赖 `:domain` 的类型 —— [TrustTier] 也在 `:domain`，所以本可以直接存枚举；
     * 选下标是为了让 SPI 的字段面**不随枚举增删而改形状**（加一档不改签名）。
     * 读方用 [tierOf] 还原，越界即"无证据"，绝不静默映射到某一档。
     */
    val tierOrdinal: Int = ScriptAuthorization.NO_TIER_ORDINAL,
) {
    /** 还原来源档；下标越界（跨版本漂移）一律回 null = 无证据，**不**折成某一档。 */
    fun tierOf(): TrustTier? = TrustTier.entries.getOrNull(tierOrdinal)
}

/**
 * 来源授权策略（A5，§11）：把「这是谁的脚本」翻译成「这次执行能用哪些桥面」。
 *
 * 三条纪律：
 * 1. **缺元数据不升级**：没有 [TrustTierResolver] 或解析器回 null → 按
 *    [TrustTier.UNKNOWN] 走（[TrustTierMasks.UNKNOWN_DEFAULT]），绝不当成内置脚本。
 *    ⚠️ **来源只能来自宿主持有的元数据**：不许从桥 payload、projectId 或项目名推断
 *    （那是脚本自报，不是证据）。
 * 2. **决策在 spawn 前算一次，之后随快照贯穿**：本策略在**一次 spawn 决策**里被问一次
 *    （`RuntimeController.start` → [decide]），得到的 [ScriptAuthorization] 经
 *    [ScriptAuthorization.snapshot] 沿 池请求 → 引擎请求 → 身份签发 → 连接上下文
 *    一路传下去。运行期没有任何入口能改它 —— 「运行中提权」在这条路径上不存在；
 *    也不存在「预检算一套、签发又算一套」的窗口（后者会让预检放行的组合与真正签发的
 *    掩码不一致）。
 * 3. **不承诺隔离**：窄掩码只是桥面收窄，不改变「同 UID 同权、Node 内建 fs/network 可用」
 *    这一事实（§11.3 第 1 条）。本类的 KDoc 是这条边界的一部分，别在别处写成沙箱。
 *
 * **可注入**：本类是装配层的开参（`AppShell.assemble` / `AppShellKit.assemble` 的
 * `authorization`），生产可传一个接了真元数据的 [TrustTierResolver] 的策略 —— 这样
 * 「按项目来源分级」是**装配决定**，而不是被三个全局布尔开关钉死。缺省 = 无来源元数据。
 *
 * @param resolver 来源元数据接缝；null = 本批生产缺省（没有来源落盘），一律 [TrustTier.UNKNOWN]。
 * @param override 装配层的显式覆盖（测试注入 / 受信直投路径）；非 null 时**优先于**解析器。
 */
class ScriptAuthorizationPolicy(
    private val resolver: TrustTierResolver? = null,
    private val override: CapabilityMask? = null,
) {

    /**
     * 该项目的授权决策（[override] 优先；否则问解析器；问不到按 [TrustTier.UNKNOWN] 档）。
     *
     * 一次 spawn 决策只调一次，结果经 [ScriptAuthorization.snapshot] 贯穿下游
     * （见类 KDoc 第 2 条）：调用方**不得**在下游重算 —— 那就又开了 TOCTOU 的口子。
     */
    fun decide(projectId: String): ScriptAuthorization {
        override?.let { return ScriptAuthorization(projectId, tier = null, mask = it) }
        val tier = resolver?.tierOf(projectId)
        return ScriptAuthorization(projectId, tier = tier, mask = TrustTierMasks.maskFor(tier ?: TrustTier.UNKNOWN))
    }

    /**
     * 便捷读口：只要掩码。
     *
     * ⚠️ 只给「不需要来源档」的调用方（诊断/UI 预览）。**spawn 路径不要用它** ——
     * 它丢掉 [ScriptAuthorization.tier]，目标侧授权就拿不到来源档了；spawn 路径走 [decide]。
     */
    fun maskFor(projectId: String): CapabilityMask = decide(projectId).mask
}
