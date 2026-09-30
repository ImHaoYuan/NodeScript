package com.autoscript.appservice.npm

import com.autoscript.build.ArchGate
import org.junit.jupiter.api.Test

/**
 * 依赖方向守护（docs §6）：npm 只依赖 :domain，零 Android / 零桥 / 零平台 / 零引擎 /
 * 零姐妹服务（含原属同一模块的 `:app-service:packager` —— 2026-09-30 拆分后是同级，
 * 编译期已无 Gradle 依赖边，这里再按字节码钉一道）。
 */
class ArchitectureTest {

    @Test
    fun `npm 包零跨层泄漏`() = ArchGate(
        "com.autoscript.appservice.npm",
        rulePackage = "..npm..",
    ).noLeakTo(
        "android..",
        "com.autoscript.bridge..",
        "com.autoscript.platform..",
        "com.autoscript.engine..",
        "com.autoscript.appservice.runtime..",
        "com.autoscript.appservice.scheduler..",
        "com.autoscript.appservice.scriptrepo..",
        "com.autoscript.appservice.permissioncenter..",
        "com.autoscript.appservice.packager..",
    )
}
