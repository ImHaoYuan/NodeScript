plugins {
    id("autoscript.jvm")
}

// 桥基础层（JVM 侧）：Router / RequestRegistry(TTL) / HandleRegistry(generation) / EventBus / transports。
// 契约见 docs §7。只依赖 :domain；禁 UI。
java {
    withSourcesJar()
}

dependencies {
    api(project(":domain"))
    implementation(libs.kotlinx.coroutines.core)
}
