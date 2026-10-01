package com.autoscript.appservice.npm

import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.TimeUnit

/**
 * 宿主 npm CLI 发现（**测试源集专用**，E2E 的环境前置）。
 *
 * 为什么不是写死 `/usr/lib/node_modules/npm`：那是 Debian 系 nodejs 包的位置，只在本机
 * 成立。GitHub runner 上 node 由 setup-node / 预装镜像放进
 * `/opt/hostedtoolcache/node/<ver>/x64/`，`/usr/lib/node_modules/npm` 不一定在 —— 写死
 * 的后果不是"跑挂了"（那还好），而是 `assumeTrue` **静默跳过**：nightly 跑完一片绿，
 * 真 npm 路径一次都没走（B1 的原始病灶）。所以改成按三种来源现查：
 *
 *  ① `npm root -g` —— npm 自己报的全局根，最权威（node/npm 不同源安装也认）；
 *  ② 从 node 可执行文件推 `<prefix>/bin/node → <prefix>/lib/node_modules/npm` ——
 *     发行版（`/usr`）/ hostedtoolcache / nvm 是同一个形状；
 *  ③ 老静态位兜底（本机 Debian 系）。
 *
 * 探不到就是 null，调用侧 `assumeTrue`（不假扮通过）；本对象**绝不抛异常**。
 * 与 `:app` 测试源集的 `com.autoscript.shell.HostNpm` 同源 —— 模块间测试源集不可见，
 * 改一处必须改另一处（两处都用同一套三来源算法，键名一致）。
 */
internal object HostNpm {

    /** 宿主 npm-cli.js 绝对路径；探不到 = null。 */
    val cliJs: Path? = candidates().firstOrNull { Files.isRegularFile(it) }

    /** npm 安装根（`<root>/bin/npm-cli.js` 的上两级）；`cliJs` 为 null 时同样为 null。 */
    val root: Path? = cliJs?.parent?.parent

    /** 宿主有 node 可执行文件吗（PATH 口径，与 [cliJs] 独立：两者可分别缺）。 */
    val hasNode: Boolean by lazy {
        try {
            val p = ProcessBuilder("node", "--version").redirectErrorStream(true).start()
            p.inputStream.readBytes()
            p.waitFor(30, TimeUnit.SECONDS) && p.exitValue() == 0
        } catch (_: Exception) {
            false
        }
    }

    private fun candidates(): List<Path> = buildList {
        capture("npm", "root", "-g")?.let { add(Path.of(it, "npm", "bin", "npm-cli.js")) }
        capture("node", "-p", "process.execPath")?.let { ex ->
            Path.of(ex).parent?.parent?.let { add(it.resolve("lib/node_modules/npm/bin/npm-cli.js")) }
        }
        add(Path.of("/usr/lib/node_modules/npm/bin/npm-cli.js"))
        add(Path.of("/usr/local/lib/node_modules/npm/bin/npm-cli.js"))
    }

    /** 跑一条只读命令取单行 stdout；任何异常/超时/多行 → null。 */
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
