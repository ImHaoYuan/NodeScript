plugins {
    id("autoscript.jvm")
}

// 权限三态门禁、引导页、降级路径（docs §9.5）。
// 纯 JVM（零 `import android.`，2026-09-30 起走 kotlin.jvm —— 系统查询/拉起由 :app 注入，本模块只做判定）。
dependencies {
    implementation(project(":domain"))
    implementation(libs.kotlinx.coroutines.core)
}
