import io.gitlab.arturbosch.detekt.Detekt
import io.gitlab.arturbosch.detekt.DetektCreateBaselineTask
import io.gitlab.arturbosch.detekt.extensions.DetektExtension

// B8：静态分析（baseline + 新增即红）。口径见 docs/design-decisions.md。
// 重点规则：SwallowedException / TooGenericExceptionCaught —— A7 那一类吞异常由规则机械守住。
// baseline 承载存量豁免：首次为每个模块生成 detekt-baseline.xml，此后**新增违规即红**。
plugins {
    id("io.gitlab.arturbosch.detekt")
}

detekt {
    // 存量豁免的单一落点：各模块自己那份（无则空跑，不报错）。
    baseline = file("detekt-baseline.xml")
    // 仓级公共配置住 build-logic，模块侧零配置 —— 口径只有一处。
    config.setFrom(files(project.rootProject.file("build-logic/detekt.yml")))
    buildUponDefaultConfig = true
    // 只扫主源码与测试源码；build/ 生成物不扫。
    source.setFrom(files("src/main/kotlin", "src/main/java", "src/test/kotlin", "src/test/java"))
}

tasks.withType<Detekt>().configureEach {
    jvmTarget = "17"
    // baseline 生成任务与检查任务共用同一份源面。
    reports {
        html.required.set(true)
        xml.required.set(true)
        sarif.required.set(false)
        md.required.set(false)
    }
}

tasks.withType<DetektCreateBaselineTask>().configureEach {
    jvmTarget = "17"
}
