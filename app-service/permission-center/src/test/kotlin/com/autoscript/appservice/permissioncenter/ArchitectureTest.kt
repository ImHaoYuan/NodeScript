package com.autoscript.appservice.permissioncenter

import com.autoscript.build.ArchGate
import org.junit.jupiter.api.Test

/**
 * 依赖方向守护（docs §6 模块表）：permission-center 实现 :domain 权限 SPI；
 * 禁 Android 直连（系统查询/拉起由 :app 经 SystemStateReader/GrantLauncher 注入）、
 * 禁桥/引擎/平台/同级服务。
 */
class ArchitectureTest {

    @Test
    fun `permission-center 包零跨层泄漏`() = ArchGate("com.autoscript.appservice.permissioncenter").noLeakTo(
        "android..",
        "androidx..",
        "com.autoscript.bridge..",
        "com.autoscript.platform..",
        "com.autoscript.engine..",
        "com.autoscript.domain.engine..",
        "com.autoscript.domain.bridge..",
        "com.autoscript.domain.automation..",
        "com.autoscript.appservice.runtime..",
        "com.autoscript.appservice.scheduler..",
        "com.autoscript.appservice.scriptrepo..",
        "com.autoscript.appservice.packager..",
    )
}
