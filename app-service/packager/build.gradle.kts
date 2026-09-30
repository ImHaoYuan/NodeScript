plugins {
    id("autoscript.jvm")
}

// 模板 APK 改写 + 签名（docs §14；npm 面 2026-09-30 审查步骤 5 已拆去 :app-service:npm）。
// 纯 JVM（零 `import android.`，2026-09-30 起走 kotlin.jvm —— 原 android.library 是插件错配）。
dependencies {
    implementation(project(":domain"))
    implementation(libs.kotlinx.coroutines.core)
}

