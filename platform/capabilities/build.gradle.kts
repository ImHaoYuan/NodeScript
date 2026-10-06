plugins {
    id("autoscript.android-library")
}

// 系统能力实现：a11y 服务 / UiNodeTreeReader、MediaProjection、截图 FrameSource、输入通道（无障碍/root/adb/Shizuku）。
// 实现 :domain SPI，不反向；禁服务逻辑。见 docs §9.1–9.3。
android {
    namespace = "com.autoscript.platform.capabilities"
}

dependencies {
    implementation(project(":domain"))
    implementation(libs.kotlinx.coroutines.core)
    // Shizuku（§9.3 的 adb 输入通道，2026-10-06 引入；判据见 docs/design-decisions.md 第 35 项）。
    // **api**：`ShizukuInput` 只用**类名**（`Class.forName("rikka.shizuku.Shizuku")`）与
    //   AIDL 接口名，代码里没有一处 import rikka.* —— 所以它其实是个**类路径声明**：
    //   声明在，编得过；真机上类不在 = `isAvailable()` 回 false、通道不接线（不是崩溃）。
    //   用 implementation 而不是 compileOnly：这两条是**运行时**要用到的（provider 更是
    //   授权握手的唯一通道），compileOnly 会把它们从 APK 里剔掉。
    // **provider**：AAR 带 `ShizukuProvider` 的**类**与 meta-data（`V3_SUPPORT`），但
    //   **不带 `<provider>` 节点** —— 节点由本模块的 AndroidManifest.xml 显式声明
    //   （见那里的注释：不声明 = 永远授权不上）。
    implementation(libs.shizuku.api)
    implementation(libs.shizuku.provider)
}
