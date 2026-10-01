package com.autoscript.shell

import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.TimeUnit

/**
 * 宿主 npm CLI 发现（**测试源集专用**，P0 回环的环境前置）。
 *
 * 与 `:app-service:npm` 测试源集的 `com.autoscript.appservice.npm.HostNpm` **同源同算法**
 * （三来源：`npm root -g` → node 可执行文件推 prefix → 老静态位兜底）。
 * 为什么写两份：Gradle 模块间测试源集互不可见，为这 40 行引 testFixtures 是更重的机器；
 * 代价是**改一处必须改另一处** —— 两处 KDoc 互相点名，键名/行为保持一致。
 *
 * 动机同 B1：写死 `/usr/lib/node_modules/npm` 在 GitHub runner 上会静默跳过，
 * nightly 一片绿但真 npm 路径一次没走。
 */
internal object HostNpm {

    /** 宿主 npm-cli.js 绝对路径；探不到 = null（调用侧 assumeTrue）。 */
    val cliJs: Path? = candidates().firstOrNull { Files.isRegularFile(it) }

    private fun candidates(): List<Path> = buildList {
        capture("npm", "root", "-g")?.let { add(Path.of(it, "npm", "bin", "npm-cli.js")) }
        capture("node", "-p", "process.execPath")?.let { ex ->
            Path.of(ex).parent?.parent?.let { add(it.resolve("lib/node_modules/npm/bin/npm-cli.js")) }
        }
        add(Path.of("/usr/lib/node_modules/npm/bin/npm-cli.js"))
        add(Path.of("/usr/local/lib/node_modules/npm/bin/npm-cli.js"))
    }

    private fun capture(vararg cmd: String): String? = try {
        val p = ProcessBuilder(*cmd).redirectErrorStream(true).start()
        val out = p.inputStream.readBytes().toString(Charsets.UTF_8).trim()
        val done = p.waitFor(30, TimeUnit.SECONDS)
        if (!done) p.destroyForcibly()
        if (done && p.exitValue() == 0 && out.isNotEmpty() && !out.contains('\n')) out else null
    } catch (_: Exception) {
        null
    }
}
