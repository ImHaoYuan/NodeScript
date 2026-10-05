import org.gradle.testing.jacoco.tasks.JacocoReport

// Android 形态的覆盖率报告（B6，只出报告不设阈值）：:ui / :platform:* / :engine:* /
// :bridge:* 与 :app 同形。单测产物在 build/tmp/kotlin-classes/debug，
// 执行数据在 build/jacoco/testDebugUnitTest.exec。
// 配置体挂在 AGP 之后：本脚本被 autoscript.android-library 应用时 android{} 还没建。
plugins {
    id("autoscript.jacoco")
}

// library 与 application 两种形态共用同一段配置（:app 是 application）。
plugins.withId("com.android.base") {
    val report = tasks.findByName("jacocoTestReport") as? JacocoReport
        ?: tasks.register<JacocoReport>("jacocoTestReport").get()
    report.apply {
        group = "verification"
        description = "出覆盖率报告（不设阈值；B6）"
        dependsOn("testDebugUnitTest")
        val classes = layout.buildDirectory.dir("tmp/kotlin-classes/debug")
        classDirectories.setFrom(
            files(classes).asFileTree.matching {
                // 生成物不算覆盖率：R / BuildConfig / Manifest 合成类。
                exclude("**/R.class", "**/R\$*.class", "**/BuildConfig.*", "**/Manifest*.*")
            },
        )
        sourceDirectories.setFrom(files("src/main/kotlin", "src/main/java"))
        executionData.setFrom(layout.buildDirectory.file("jacoco/testDebugUnitTest.exec"))
        reports {
            xml.required.set(true)
            html.required.set(true)
            csv.required.set(false)
        }
    }
}
