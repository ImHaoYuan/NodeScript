# R8 规则（:app 的 release「性能包」；debug 不过 R8，见 app/build.gradle.kts 的 buildTypes）。
#
# 这份文件**故意很短**，理由是可核的而不是"相信 R8"：
#   · 生产代码里按**字符串**找类的只有一处（`grep -n "Class.forName" app/src/main` 只有一条），
#     就是下面那条 keep；
#   · 其余反射面为零 —— `getDeclaredMethod` / `newInstance` / `getIdentifier` 在 main 源集里
#     0 命中（只有测试在用），资源也都是 `R.*` 直引；
#   · androidx / compose / kotlinx 各自带 consumer 规则（随 AAR 合并进来），manifest 里的
#     Activity/Service/Receiver/Provider 由 AGP 从合并后的 manifest 生成 keep 规则 ——
#     重复写一遍只会变成"改了 manifest 忘了改这里"的第二处事实来源。

# launcher Activity：AppShellApplication.launcherActivityOrNull() 用
# `Class.forName(component.className)` 按名字取（`:app` 源码不认识 `:ui`，类名只能从
# PackageManager 查），R8 看不见这条引用。去掉它 = 通知点不开（保活本身不受影响）。
-keep class com.autoscript.ui.MainActivity { <init>(); }

# 原生方法按名字解析（libopencv.so 的 JNI 入口）：缺省规则里已经有，这里显式再留一条 ——
# 免得将来有人换 proguard 文件时把它丢了，而丢了的表现是"图像能力在设备上
# UnsatisfiedLinkError"，本机单测（替身 Ops）却照样全绿。
-keepclasseswithmembernames,includedescriptorclasses class * {
    native <methods>;
}
