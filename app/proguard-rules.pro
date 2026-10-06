# R8 规则（:app 的 release「性能包」；debug 不过 R8，见 app/build.gradle.kts 的 buildTypes）。
#
# 这份文件**故意很短**，但短的理由只覆盖一部分 —— 下面第 3 条是**必须**写死的，
# 不是"相信 R8"：
#
#   1. 资源面确实干净：`getIdentifier` / `getDeclaredField` / `newInstance` 在**全部模块**
#      的 main 源集里 0 命中（`grep -rn --include=*.kt` 一遍就知道），资源都是 `R.*` 直引；
#   2. androidx / compose / kotlinx 各自带 consumer 规则（随 AAR 合并进来），manifest 里的
#      Activity/Service/Receiver/Provider 由 AGP 从**合并后的 manifest** 生成 keep 规则
#      （`rikka.shizuku.ShizukuProvider` 那条就是这么来的，见 `configuration.txt`）——
#      重复写一遍只会变成"改了 manifest 忘了改这里"的第二处事实来源；
#   3. **按字符串找类/找方法的反射面不止一处**（初版注释写"只有一处、其余为零"，是错的）：
#      `Class.forName` 在 main 源集里有 **3 处**（`:app` 1 —— launcher Activity；
#      `:platform:capabilities` 2 —— `IShizukuService$Stub` 与 `IShizukuService`）、
#      `getMethod` 有 **9 处**（`:platform:capabilities` 8 —— 全在 `ShizukuInput`；
#      `:engine:node-process` 1 —— `Process::class.java.getMethod("pid")`），横跨三个模块。
#      R8 看不见这些引用，**每一条都要在这里显式留名**，否则 release 包上表现为
#      "能力静默失效"（不崩、不报错，只是永远走不通那条分支）。
#      （唯一**不用**在这里列的是 `ProcessLauncher` 那条：`pid` 声明在 `java.lang.Process`
#      —— 平台类不参与收缩，名字不会变。）
#
# 用 `-keep` 而不是 `-keepnames`：`-keepnames` 带 `allowshrinking`，**只保名不保代码** ——
# 对"静态调用者已经把它保活了、只是名字被改短"的成员够用，对**只被反射调到**的成员
# 等于没写（名字留着，方法体被 R8 全量模式删掉，`getMethod` 找得到、`invoke` 才发现没实现）。
# 本文件列的全是"只被反射调到"或"类名被按字符串查"的成员，一律 `-keep`。

# ── 1. 按字符串找**类**的四处 ────────────────────────────────────────────────

# 1a. launcher Activity：AppShellApplication.launcherActivityOrNull() 用
# `Class.forName(component.className)` 按名字取（`:app` 源码不认识 `:ui`，类名只能从
# PackageManager 查）。去掉它 = 通知点不开（保活本身不受影响）。
-keep class com.autoscript.ui.MainActivity { <init>(); }

# 1b. Shizuku（adb 输入通道，§9.3）：ShizukuInput 全程反射调用，**依赖是 `implementation`
# 不是 compileOnly**（`ShizukuProvider` 是本应用 manifest 里声明的 ContentProvider，
# 类不在 = 授权握手不成立），所以两个坐标都真在 APK 里。
#
# 这四条覆盖反射调到的**每一个类名 + 方法名**：
#   · `rikka.shizuku.Shizuku`：`getBinder()` / `pingBinder()`；
#   · `moe.shizuku.server.IShizukuService$Stub`：`asInterface(IBinder)`；
#   · `moe.shizuku.server.IShizukuService`：`newProcess(String[], String[], String)`
#     （**从公开接口取**，见 ShizukuInput.run 里那段注释）；
#   · `rikka.shizuku.ShizukuRemoteProcess`（`newProcess` 返回的实现类）：
#     `getErrorStream()` / `waitForTimeout(long, TimeUnit)` / `destroy()` / `exitValue()`。
#
# 前三条里的方法**有静态调用者**（`ShizukuProvider` 真调 `Shizuku.getBinder()`/
# `pingBinder()`；`Shizuku` 内部真调 `IShizukuService$Stub.asInterface`），`-keep` 在这里
# 主要是**保名**（实测 `mapping.txt`：`rikka.shizuku.Shizuku -> O2.c`、`getBinder() -> call`）。
# 第四条不一样：`ShizukuRemoteProcess` 在本应用侧**没有静态调用者**（`Shizuku.newProcess`
# 是私有的、只有 `Shizuku` 内部调，而那个入口本应用没用），当前 release 包里它的四个方法
# **全被 R8 删光**（`usage.txt`）—— 这条 `-keep` 才是真在保代码。
-keep class rikka.shizuku.Shizuku {
    public static android.os.IBinder getBinder();
    public static boolean pingBinder();
}
-keep class moe.shizuku.server.IShizukuService$Stub {
    public static moe.shizuku.server.IShizukuService asInterface(android.os.IBinder);
}
-keep class moe.shizuku.server.IShizukuService {
    public abstract moe.shizuku.server.IRemoteProcess newProcess(java.lang.String[], java.lang.String[], java.lang.String);
}
-keep class rikka.shizuku.ShizukuRemoteProcess {
    public java.io.InputStream getErrorStream();
    public boolean waitForTimeout(long, java.util.concurrent.TimeUnit);
    public void destroy();
    public int exitValue();
}

# ── 2. 原生方法按名字解析（libopencv.so 的 JNI 入口）────────────────────────
# 缺省规则里已经有，这里显式再留一条 —— 免得将来有人换 proguard 文件时把它丢了，
# 而丢了的表现是"图像能力在设备上 UnsatisfiedLinkError"，本机单测（替身 Ops）却照样全绿。
-keepclasseswithmembernames,includedescriptorclasses class * {
    native <methods>;
}
