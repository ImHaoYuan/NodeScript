plugins {
    id("autoscript.android-library")
}

// C++ 图像分析管线 addon：独立 so libopencv.so（OpenCV 4.x，不依赖 node）。
// 本模块**不经 AGP 的 externalNativeBuild**：libopencv.so 由 node-runtime-build/scripts/build-opencv.sh
// 交叉编译（CI 走 .github/workflows/image-native.yml），宿主机语义门禁在 test/cpp/；见 docs §9.2。
android {
    namespace = "com.autoscript.bridge.image"
}
