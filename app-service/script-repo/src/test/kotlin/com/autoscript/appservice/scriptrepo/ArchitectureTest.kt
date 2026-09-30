package com.autoscript.appservice.scriptrepo

import com.autoscript.build.ArchGate
import org.junit.jupiter.api.Test

/** 依赖方向守护（docs §3）：core 层保持纯 JVM，零 Android / 零桥 / 零引擎泄漏。 */
class ArchitectureTest {

    @Test
    fun `core 包保持平台无关`() = ArchGate(
        "com.autoscript.appservice.scriptrepo.core",
        rulePackage = "..core..",
    ).noLeakTo(
        "android..",
        "com.autoscript.bridge..",
        "com.autoscript.platform..",
        "com.autoscript.engine..",
        "org.jetbrains.kotlinx.coroutines..",
    )
}
