// B6：覆盖率只出报告、不设阈值（存量覆盖率未知，设门必红）——本批只让盲区「看得见」。
// 本插件只负责「挂 agent」；报告任务按模块形态分两处注册（JVM 走 sourceSets，
// Android 走 kotlin-classes/debug），见 autoscript.jvm / autoscript.android-jacoco。
plugins {
    jacoco
}

// 版本钉死：与 CI 的 JDK 17 / Kotlin 2.0.21 无关，只决定报告格式与 agent 字节码面。
configure<JacocoPluginExtension> {
    toolVersion = "0.8.15"
}

// 每个 Test 任务都挂 agent（含 Android 的 testDebugUnitTest）。
tasks.withType<Test>().configureEach {
    extensions.configure(JacocoTaskExtension::class.java) {
        // 覆盖 data class / 内联函数生成的合成类，否则报告里成片假未覆盖。
        isIncludeNoLocationClasses = true
        // 必配：JDK 16+ 隐藏 jdk.internal.* ，instrument 了它们会让测试 worker 起不来
        //（实测 NoClassDefFoundError: jdk/internal/reflect/GeneratedSerializationConstructorAccessor1，
        // 三个测试任务同时红）。这是 jacoco 官方给出的配套排除项。
        excludes = listOf("jdk.internal.*")
    }
}
