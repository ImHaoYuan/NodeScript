// 约定插件的 classpath：三个插件的 **marker 坐标**（= 各模块 plugins 块里那三个 id，版本取目录同源）。
// 注：`libs.plugins.*` 别名直接进 dependencies 会被当成普通 Dependency 记法解析失败（实测
// "Cannot convert … org.jetbrains.kotlin.jvm:2.0.21"），故手拼 marker 串。
plugins {
    `kotlin-dsl`
}

group = "com.autoscript.build"

dependencies {
    implementation("org.jetbrains.kotlin.jvm:org.jetbrains.kotlin.jvm.gradle.plugin:${libs.versions.kotlin.get()}")
    implementation("org.jetbrains.kotlin.android:org.jetbrains.kotlin.android.gradle.plugin:${libs.versions.kotlin.get()}")
    implementation("com.android.library:com.android.library.gradle.plugin:${libs.versions.agp.get()}")
}
