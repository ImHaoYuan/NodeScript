plugins {
    id("autoscript.android-library")
}

// 项目/资源/脚本库、assets→filesDir 原子部署（docs §9.6）。
// 保留 android.library：AndroidAssetsSource 有 `import android.`（不入纯 JVM 转换名单）。
android {
    namespace = "com.autoscript.appservice.scriptrepo"
}

dependencies {
    implementation(project(":domain"))
    implementation(libs.kotlinx.coroutines.core)
}
