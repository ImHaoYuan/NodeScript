plugins {
    id("autoscript.jvm")
}

// 领域层：纯 Kotlin，零 Android / 零桥依赖。全部 SPI 契约见 docs §4.1/§7/§9.5。
java {
    withSourcesJar()
}

dependencies {
    // kotlinx.coroutines（Flow/suspend）是契约层签名的一部分；core 仅含 Flow/协程原语，零 Android。
    api(libs.kotlinx.coroutines.core)
}
