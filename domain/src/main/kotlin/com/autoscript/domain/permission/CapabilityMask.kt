package com.autoscript.domain.permission

import kotlin.jvm.JvmInline

/**
 * 桥面能力掩码（A5，§11）：宿主为**一次执行**发放的 [BridgeCapability] 集合。
 *
 * **deny-by-default**：掩码里没有的面一律拒 —— [BridgeCapabilityCatalog] 为每个命名空间
 * 申报必需能力，路由层要求掩码**覆盖**它（[covers]），否则 `ERR_PERMISSION_DENIED`。
 *
 * **与三态门禁（[CapabilityState]）正交、不可互相替代**：
 * - 三态问的是「**系统**给不给这个 App」（[PermissionFacade] 现问系统，§9.5）；
 * - 掩码问的是「**这次执行**被宿主授权用哪些桥面」—— 同一个 `GRANTED` 的能力，
 *   窄掩码的脚本照样拒。
 * 两者都过才放行：掩码是**先决条件**，不是替代品（掩码 `ALL` 也不会让未授权的 a11y 变可用）。
 * 注意两张目录是**两个枚举**（[Capability] vs [BridgeCapability]），别把它们当同义词。
 *
 * **掩码由宿主在 spawn 时定死、随执行身份绑定、运行期不可变**（[ScriptAuthorizationPolicy]
 * → `RunIdentityLease` → `AuthenticatedRunContext`）。因此「用户在系统里开了投屏/无障碍」
 * **不会**给任何脚本新增掩码位 —— 系统授权与脚本授权是两条独立的闸。
 *
 * **边界（不得读成沙箱）**：本掩码只管**桥面命名空间**。Node 内建 `fs`/`http`/`os`/
 * `process` 完整可用（§12.2），第三方脚本与自写脚本同 UID 同权（§18 第 1 项），
 * 掩码既不限制也不承诺限制它们；它也不是「恶意同 UID 代码隔离」（§11.3 第 1 条）。
 *
 * **持久化**：本批**没有**掩码持久化 —— 它只在内存里活（签发 → 连接 → 进程结束即消失）。
 * 位 = [BridgeCapability] 的 `ordinal`，故只许在枚举末尾追加；[fromBits] 的未知位守卫是
 * 防宿主策略写错位的纵深防御，**不是**跨版本兼容方案（真持久化要先有稳定编号 + 版本字段）。
 */
@JvmInline
value class CapabilityMask private constructor(val bits: Long) {

    init {
        // 未知位 = 宿主策略写错或跨版本位序漂移：响亮失败，绝不静默放行或静默截断。
        require(bits and KNOWN_BITS.inv() == 0L) { "掩码含未知能力位：0x${bits.toString(16)}" }
    }

    operator fun contains(capability: BridgeCapability): Boolean = bits and bitOf(capability) != 0L

    /** 本掩码是否**覆盖** [required] 的每一位（`NONE` 恒被覆盖；`ALL` 覆盖一切已知位）。 */
    fun covers(required: CapabilityMask): Boolean = bits and required.bits == required.bits

    fun plus(capability: BridgeCapability): CapabilityMask = CapabilityMask(bits or bitOf(capability))

    fun plus(other: CapabilityMask): CapabilityMask = CapabilityMask(bits or other.bits)

    fun minus(capability: BridgeCapability): CapabilityMask = CapabilityMask(bits and bitOf(capability).inv())

    fun isEmpty(): Boolean = bits == 0L

    fun isNotEmpty(): Boolean = bits != 0L

    /** 展开成可读集合（审计/日志/UI 用；位序无关）。 */
    fun toSet(): Set<BridgeCapability> = BridgeCapability.entries.filterTo(LinkedHashSet()) { it in this }

    /** 审计可读形：`ALL` / `NONE` / `{ACCESSIBILITY, SCREEN_CAPTURE}`。 */
    override fun toString(): String = when (bits) {
        0L -> "NONE"
        KNOWN_BITS -> "ALL"
        else -> toSet().joinToString(prefix = "{", postfix = "}") { it.name }
    }

    companion object {
        /** 已知能力位全集（由枚举派生：新增 [BridgeCapability] 项自动进集合，无需改掩码）。 */
        private val KNOWN_BITS: Long = BridgeCapability.entries.fold(0L) { acc, c -> acc or bitOf(c) }

        /** 一位都不许（`covers(NONE)` 为真，但任何非空 `required` 都拒）。 */
        val NONE: CapabilityMask = CapabilityMask(0L)

        /** 全部已知能力（生产缺省：§11 现行口径下各来源档均为全量，见 [TrustTierMasks]）。 */
        val ALL: CapabilityMask = CapabilityMask(KNOWN_BITS)

        fun of(vararg capabilities: BridgeCapability): CapabilityMask =
            CapabilityMask(capabilities.fold(0L) { acc, c -> acc or bitOf(c) })

        fun of(capabilities: Iterable<BridgeCapability>): CapabilityMask =
            CapabilityMask(capabilities.fold(0L) { acc, c -> acc or bitOf(c) })

        /**
         * 从裸位图还原（宿主策略/将来的持久化读回用）。未知位 → [IllegalArgumentException]
         * （见 [init]）：宁可响亮失败，不可把位序漂移读成「多了几项授权」。
         */
        fun fromBits(bits: Long): CapabilityMask = CapabilityMask(bits)

        private fun bitOf(capability: BridgeCapability): Long = 1L shl capability.ordinal

        init {
            require(BridgeCapability.entries.size <= Long.SIZE_BITS) {
                "桥面能力项数超过掩码位宽（${BridgeCapability.entries.size} > ${Long.SIZE_BITS}）：需换多位图实现"
            }
        }
    }
}
