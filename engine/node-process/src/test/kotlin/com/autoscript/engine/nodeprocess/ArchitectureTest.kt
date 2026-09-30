package com.autoscript.engine.nodeprocess

import com.autoscript.build.ArchGate
import org.junit.jupiter.api.Test

/**
 * 依赖方向守护（docs §6 `:engine:node-process` 行）：Kotlin 侧实现 `:domain` 的 `ScriptEngine`
 * SPI，不反向、不碰编排/平台/shell/UI —— 本模块保持**纯 JVM**（约定插件给它 kotlin.jvm 面的
 * 依赖视图，任何 `android.*` import 会落进下方 `android.app/android.widget` 黑名单被本门拦下；
 * 更早一道闸是编译期：模块零 android 依赖，误引直接红）。
 *
 * `:bridge:native` 的依赖是 C++/运行期 `.so` 装载（main.cpp dlopen），没有 Kotlin 类可依，
 * 故黑名单里的 `com.autoscript.bridge..`（`:bridge:java` 的 Kotlin 面）对本模块是全禁。
 */
class ArchitectureTest {

    @Test
    fun `engine 模块零跨层泄漏`() = ArchGate("com.autoscript.engine.nodeprocess").noLeakTo(
        "com.autoscript.bridge..",      // 桥 Kotlin 面归 :main 装配（§6：引擎只见 domain SPI）
        "com.autoscript.appservice..",  // 编排层（池/调度）反向依赖引擎 = 环
        "com.autoscript.platform..",    // 平台实现 SPI 不反向
        "com.autoscript.shell..",       // 装配包（shell 反过来禁碰 engine，双向不交）
        "androidx..",
        "android.app..",                // 禁 UI/系统服务面；纯 java.* 进程面
        "android.widget..",
    )
}
