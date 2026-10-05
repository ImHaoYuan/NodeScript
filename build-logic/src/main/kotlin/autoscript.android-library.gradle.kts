import com.autoscript.build.Catalogs
import com.autoscript.build.TestGuard

// AutoScript android-library 模块约定：android.library + kotlin.android + JDK17 面 +
// JUnit5 + 共享架构门源 + skipped 守卫。收编 12 个 build 文件逐字重复的 android{} 公共段
// （compileSdk/minSdk/compileOptions/jvmTarget/testOptions）—— namespace 留各模块自己声明。
plugins {
    id("com.android.library")
    id("org.jetbrains.kotlin.android")
}

// B6：覆盖率报告（只出报告不设门）。
plugins.apply("autoscript.android-jacoco")

// B8：静态分析（baseline + 新增即红）。
plugins.apply("autoscript.detekt")

android {
    compileSdk = Catalogs.int(project, "compileSdk")
    defaultConfig { minSdk = Catalogs.int(project, "minSdk") }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions { jvmTarget = "17" }
    testOptions {
        unitTests {
            all { it.useJUnitPlatform() }
        }
    }
    // 共享架构门（ArchGate）进 test 源集。
    sourceSets.getByName("test") {
        kotlin.srcDir(rootProject.file("build-logic/arch-shared/src/main/kotlin"))
    }
}

dependencies {
    "testImplementation"(Catalogs.libs(project).findLibrary("junit-jupiter").get())
    "testRuntimeOnly"("org.junit.platform:junit-platform-launcher")
    "testImplementation"(Catalogs.libs(project).findLibrary("archunit-junit5").get())
}

// 守卫须在脚本顶层注册（同 autoscript.jvm：不可嵌在 configureEach 回调里）。
TestGuard.apply(project)
