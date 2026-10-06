package com.autoscript.platform.system.app

/**
 * app 命名空间的契约 DTO/SPI（§9.3/§9.6/§12.2；`extras.ts` 的 Kotlin 对偶）。
 *
 * 沿革（原 `SystemHostContracts.kt` 四面合一份 → 按面拆开，契约随「仅 handler+impl 消费」
 * 判据迁自 `:domain`）见 `SystemNamespaces` 的类注释，此处不重复。
 *
 * **非法即拒**：构造期 `require`（本面全是老实返回，无构造期守卫），handler 据此折叠
 * ERR_INVALID_PARAM，绝不把垃圾发往平台层。
 */

// ── app（§9.3/§12.2）────────────────────────────────────────────────

/**
 * 应用开关 SPI。实现住 `:platform:system`（PackageManager）。
 * - [launch] 回 false = 找不到/起不来（**不是异常**：JS facade 用 `=== true` 判成败，
 *   抛错会让 try/catch 策略退化成「只有崩了才算失败」）；
 * - [currentPackage] 回 null = 取不到前台包（无权限/无前台窗口），如实给 null 不给空串。
 */
interface AppLauncher {
    suspend fun launch(packageName: String): Boolean
    suspend fun currentPackage(): String?
}
