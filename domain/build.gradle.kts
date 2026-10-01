plugins {
    id("autoscript.jvm")
}

// 领域层：纯 Kotlin，零 Android / 零桥依赖。全部 SPI 契约见 docs §4.1/§7/§9.5。
java {
    withSourcesJar()
}

dependencies {
    // kotlinx.coroutines（Flow/suspend）是契约层签名的一部分；core 仅含 Flow/协程原语，零 Android。
    api(libs.kotlinx.coroutines.core)
}

// ModuleGraphTest 读仓库根的文件（settings/CI/文档计数），这些不是本模块的源码 ——
// 不声明成输入的话，改了文档 Gradle 仍判 :domain:test UP-TO-DATE，门在本机就哑了。
tasks.named<Test>("test") {
    inputs.files(
        rootProject.file("settings.gradle.kts"),
        rootProject.file("build-logic/settings.gradle.kts"),
        rootProject.file(".github/workflows/ci.yml"),
        rootProject.file("CLAUDE.md"),
        rootProject.file("docs/design/06-modules.md"),
    ).withPropertyName("moduleGraphDocs").withPathSensitivity(PathSensitivity.RELATIVE)
}
