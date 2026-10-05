import com.autoscript.build.Catalogs
import com.autoscript.build.TestGuard

// AutoScript 纯 JVM 模块约定：kotlin.jvm + JDK17 工具链 + JUnit5 + 共享架构门源 + skipped 守卫。
// 收编各模块 build.gradle.kts 逐字重复的 kotlin{}/tasks.test{}/test 依赖三段。
plugins {
    id("org.jetbrains.kotlin.jvm")
}

// B6：覆盖率报告（只出报告不设门）。
plugins.apply("autoscript.jvm-jacoco")

// B8：静态分析（baseline + 新增即红）。
plugins.apply("autoscript.detekt")

kotlin {
    jvmToolchain(17)
    // 共享架构门（ArchGate）：各模块 ArchitectureTest 只留"本模块黑名单"。
    sourceSets.named("test") {
        kotlin.srcDir(rootProject.file("build-logic/arch-shared/src/main/kotlin"))
    }
}

dependencies {
    "testImplementation"(Catalogs.libs(project).findLibrary("junit-jupiter").get())
    "testRuntimeOnly"("org.junit.platform:junit-platform-launcher")
    "testImplementation"(Catalogs.libs(project).findLibrary("archunit-junit5").get())
}

// 守卫须在脚本顶层注册：嵌在 configureEach 回调里再 register configureEach 会炸
// （"cannot be executed in the current context"）。
TestGuard.apply(project)

tasks.withType<Test>().configureEach {
    useJUnitPlatform()
}
