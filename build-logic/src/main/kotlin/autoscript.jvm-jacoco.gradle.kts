import org.gradle.testing.jacoco.tasks.JacocoReport

// 纯 JVM 形态的覆盖率报告（B6，只出报告不设阈值）。
// 类目录直接取 kotlin/java 编译产物位（不经 SourceSetContainer —— 预编译脚本里
// 那个扩展在插件应用次序上取不稳，实测 "Extension of type 'SourceSetContainer' does not exist"）。
plugins {
    id("autoscript.jacoco")
}

plugins.withId("java") {
    val report = tasks.findByName("jacocoTestReport") as? JacocoReport
        ?: tasks.register<JacocoReport>("jacocoTestReport").get()
    report.apply {
        group = "verification"
        description = "出覆盖率报告（不设阈值；B6）"
        dependsOn("test")
        classDirectories.setFrom(
            files(
                layout.buildDirectory.dir("classes/kotlin/main"),
                layout.buildDirectory.dir("classes/java/main"),
            ),
        )
        sourceDirectories.setFrom(files("src/main/kotlin", "src/main/java"))
        executionData.setFrom(layout.buildDirectory.file("jacoco/test.exec"))
        reports {
            xml.required.set(true)
            html.required.set(true)
            csv.required.set(false)
        }
    }
}
