plugins {
    id("autoscript.jvm")
}

// 运行时仲裁层：引擎进程池 + 看门狗策略（docs §8）。纯 JVM、只依赖 :domain SPI，可本地单测。
java {
    withSourcesJar()
}

dependencies {
    api(project(":domain"))
    implementation(libs.kotlinx.coroutines.core)
}
