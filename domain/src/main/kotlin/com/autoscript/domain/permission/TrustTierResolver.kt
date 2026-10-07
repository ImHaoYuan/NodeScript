package com.autoscript.domain.permission

/**
 * 来源/项目元数据 → [TrustTier] 的接缝（A5）。
 *
 * **本批没有实现**：`ScriptProject` 没有来源字段，仓库里也没有任何来源落盘位置
 * （打包器不写、市场导入不存在）。本批刻意**不猜**来源 —— 缺实现时
 * [ScriptAuthorizationPolicy] 落 [TrustTier.UNKNOWN]（最保守）。
 *
 * 最小 SPI 的形状就是这一个函数：装配层（`:app`）将来把它接到真元数据
 * （打包产物清单 / 市场导入记录 / 用户「这是我写的」标记）上，
 * 不需要改桥、不需要改授权路径。
 *
 * **实现方纪律**：
 * - 读不到、读失败、字段缺失一律回 `null`（= 无证据 = [TrustTier.UNKNOWN]）；
 * - **绝不**回 [TrustTier.BUILT_IN] —— 「不知道」不能升级成「可信」；
 * - **绝不**从桥 payload / projectId 字面 / 项目名推断来源（那是脚本自报，不是证据）；
 * - [TrustTier.THIRD_PARTY] 只能由**真证据**（市场导入记录、签名缺失判定）产生，
 *   不能拿"查不到"来冒充 —— [TrustTier.UNKNOWN] 与 [TrustTier.THIRD_PARTY] 是两回事。
 */
fun interface TrustTierResolver {
    fun tierOf(projectId: String): TrustTier?
}
