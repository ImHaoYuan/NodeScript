plugins {
    id("autoscript.android-library")
}

// C++ N-API addon 控制面 + libnode.so 装载（:nodeN 进程宿主引用）。
// 本模块**不经 AGP 的 externalNativeBuild**：addon 的 `.so` 由 :engine:node-process 的
// scripts/build-native.sh 用 NDK 交叉编译（本机可跑；真机红测走 CI）；见 docs §7.8。
android {
    // namespace 不能含 Java 关键字 `native`（AGP 校验拒绝 com.autoscript.bridge.native）。
    // 模块路径 :bridge:native 由 §6 模块表冻结，此处只取合法包名变体；本模块零 JVM 源码（纯 C++），
    // namespace 仅用于 AGP 生成 R/manifest 包，无跨模块引用，改名零影响。
    namespace = "com.autoscript.bridge.nativelib"
}
