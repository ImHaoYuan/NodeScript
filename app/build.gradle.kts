plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    // skipped/aborted 守卫（convention 之外唯一单独应用它的模块：application 不走
    // autoscript.android-library 约定，android{} 里塞满装配特例）。
    id("autoscript.test-guard")
    // 引擎二进制 / facade dist 随包任务（§19/§12.4）+ preBuild wiring（审查步骤 1 迁入）。
    id("autoscript.engine-natives")
}

android {
    namespace = "com.autoscript"
    compileSdk = libs.versions.compileSdk.get().toInt()
    defaultConfig {
        applicationId = "com.autoscript"
        minSdk = libs.versions.minSdk.get().toInt()
        targetSdk = libs.versions.targetSdk.get().toInt()
        versionCode = 1
        versionName = "0.1.0"
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions { jvmTarget = "17" }
    sourceSets {
        getByName("main") {
            // 生成位在 build/ 下（不提交二进制副本）；prepareBridgeDistAssets 在资产合并前跑。
            // srcDir 必须是 **父目录**：拷贝目标是 .../bridgeDistAssets/bridge-dist/*，
            // 资产键 = 相对 srcDir 的路径 = `bridge-dist/<file>`（Application 侧
            // `assets.list("bridge-dist")` 认的就是这个键）。指成子目录会把文件拍平到
            // assets 根，list("bridge-dist") 恒空 —— 一条静默的"没货"故障。
            assets.srcDir(layout.buildDirectory.dir("generated/bridgeDistAssets"))
            // bridge addon 随包（§19 交付轨）：srcDir 取**父目录**，资产键 =
            // `bridge-addon/<file>`（Application 侧 `assets.open("bridge-addon/…")` 认的
            // 就是这个键）—— 与 bridge-dist 同一条"指成子目录会拍平"的教训。
            assets.srcDir(layout.buildDirectory.dir("generated/engineAddonAssets"))
            // 引擎二进制随包（§19）：srcDir 根下按 ABI 分目录（`arm64-v8a/libnoden.so`），
            // prepareEngineNativeLibs 拷进 generated/engineNativeLibs/arm64-v8a/。
            jniLibs.srcDir(layout.buildDirectory.dir("generated/engineNativeLibs"))
        }
    }
    packaging {
        jniLibs {
            // exec 需要**真文件**：默认 extractNativeLibs=false（lib 留在 APK 里给
            // linker 直读）时 nativeLibraryDir 是空的，`hostBinary` 预检/ exec 都落空。
            // legacy 打包 = 安装期解压 lib/<abi>/* 到 nativeLibraryDir（§19 交付轨前提）。
            useLegacyPackaging = true
        }
    }
    testOptions {
        unitTests {
            all {
                it.useJUnitPlatform()
                // P0 回环（P0LoopbackTest）拉真 npm 进程：CI 走 -PskipNpmE2E 排除
                // （与 :app-service:packager 的 HostNodeNpmE2ETest 同一条纪律；
                // 本机闭环不带该 flag 即跑）。
                if (project.hasProperty("skipNpmE2E")) {
                    it.exclude("**/P0LoopbackTest*")
                }
            }
        }
    }
}

dependencies {
    implementation(project(":app-service:runtime"))
    implementation(project(":app-service:scheduler"))
    implementation(project(":app-service:script-repo"))
    implementation(project(":app-service:permission-center"))
    implementation(project(":app-service:packager"))
    implementation(project(":domain"))
    // 仅 Composition Root（com.autoscript.shell.AppShell）可碰 :bridge:java：
    // 把各 handler 薄转接挂到 BridgeRouter。不做业务逻辑，见 AppShell 注释 + ArchitectureTest。
    implementation(project(":bridge:java"))
    // 仅 Composition Root（com.autoscript.shell 装配包）可碰 :platform:*（§6 包级例外二，
    // 与 :bridge:java 同形）：SystemSpis + CapabilityNamespaces 生产装配（PlatformWiring）。
    // 见 ArchitectureTest「平台实现只许装配包碰」+ ModuleGraphTest 允许集 + §6「例外不是开后门」。
    implementation(project(":platform:capabilities"))
    implementation(project(":platform:system"))
    implementation(project(":engine:node-process"))   // §19 Kotlin spawn：根包 Application 构造 engineFactory（shell 装配包仍禁碰 —— ArchitectureTest）
    // 呈现层（2026-09-23 拆出）：只为 APK 组装 + launcher manifest 合并 ——
    // :app **源码零 import** com.autoscript.ui（装配知识不流向呈现层；
    // Application 实现的是 :domain 的 HostSummary）。compose 依赖随 UI 同批迁去 :ui。
    implementation(project(":ui"))

    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.lifecycle.runtime.ktx)

    testImplementation(libs.junit.jupiter)
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")
    testImplementation(libs.archunit.junit5)
}
