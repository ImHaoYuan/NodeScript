pluginManagement {
    repositories {
        google()
        mavenCentral()
        gradlePluginPortal()
    }
}

dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        google()
        mavenCentral()
    }
}

rootProject.name = "AutoScript"

// 约定插件独立构建（不入模块图、不计 15 模块）：autoscript.jvm / autoscript.android-library /
// autoscript.test-guard。插件 id 见 build-logic/src/main/kotlin/。
includeBuild("build-logic")

// 模块表见 docs/framework-design.md §6。
// Gradle 模块：15 个。:bridge:js 是独立 npm workspace（不参与 Gradle）；:node-runtime-build 是 CI 构建管线（不参与 Gradle）。
// ⚠️ 此文件由协调者冻结：新增/删除模块需先与协调者对齐，禁止子 agent 私自修改。
include(":app")
include(":app-service:runtime")
include(":app-service:scheduler")
include(":app-service:script-repo")
include(":app-service:permission-center")
include(":app-service:packager")
include(":domain")
include(":bridge:java")
include(":bridge:native")
include(":bridge:image")
include(":engine:node-process")
include(":engine:sandbox")
include(":platform:capabilities")
include(":platform:system")
include(":ui")