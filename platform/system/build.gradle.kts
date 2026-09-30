plugins {
    id("autoscript.android-library")
}

// 系统侧实现：overlay、通知、datastore(SQLite)、shell、设备信息、zip、系统设置。
// 实现 :domain SPI，不反向；禁服务逻辑。见 docs/framework-design.md §9.6。
android {
    namespace = "com.autoscript.platform.system"
}

dependencies {
    implementation(project(":domain"))
    implementation(libs.kotlinx.coroutines.core)
}
