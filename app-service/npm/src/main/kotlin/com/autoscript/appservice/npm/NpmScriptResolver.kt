package com.autoscript.appservice.npm

import com.autoscript.domain.core.AutojsException
import com.autoscript.domain.core.ErrorCode
import com.autoscript.domain.json.DomainJson
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.Path

/**
 * lifecycle 脚本的**宿主侧解析**（§10.3 T1 放行门禁的取数口）。
 *
 * 存在的理由：审批账本的键是 `projectId + pkg + versionHash + action`（[ApprovalLedger]），
 * 而 [InstallCoordinator.runScript] 的入参只有 `projectId + name` —— **它拿不到调用方当初
 * 提交审批时用的哈希**。于是放行判据只能由宿主从盘上现状重算，且必须与 `requestApprove`
 * 重算出**同一个**值，否则审批永远匹配不上（功能等于没接）。
 * 本类就是那个「唯一的重算处」：requestApprove 与 runScript/exec 都调它，不各算各的。
 *
 * 两个哈希口径（都带 `sha256:` 前缀，便于人眼分辨，且都与 pkg@version 绑在一起 ——
 * §10.5-2「审批记录绑定 pkg+版本+脚本内容哈希，版本升级必须重新审批」）：
 * - [projectScripts]：**整个 scripts 映射**的规范化摘要（键排序后 `name=body` 逐行）。
 *   刻意不做「逐脚本一个哈希」：`npm run <name>` 要的是「这个项目此刻的脚本」这一整份授权，
 *   而审批时调用方未必知道脚本名与 body 的对应；改任何一个脚本 = 改整份 = 重新审批，
 *   与「版本升级必须重批」是同一条纪律的同一种粒度。
 * - [binTarget]：bin 声明（归属包 pkg@version + bin 名 + 目标相对路径）的摘要。
 *
 * 纯 JS bin 白名单（§10.3 T1「纯 JS bin 白名单」）：bin 目标必须是 `.js`/`.cjs`/`.mjs`。
 * 非 JS 后缀即 `ERR_NOT_SUPPORTED` 并指向「wasm 优先、纯 JS 兜底」（§10.3 明确不可行那节）
 * —— 从 app 数据区 exec 任意二进制本就不允许（W^X），设备端也没有编译器可用。
 *
 * 解析只读文件系统（`:main` Kotlin 直读，零 Node 进程，§10.6 轻操作同款纪律）。
 */
internal object NpmScriptResolver {

    /** 纯 JS bin 目标后缀（白名单的全体；不在表内即拒）。按**文件名的结尾**判，不是整名相等。 */
    private val JS_SUFFIXES = listOf(".js", ".cjs", ".mjs")

    /** 项目自身 scripts 的解析结果。 */
    data class ProjectScripts(
        /** 包名（manifest 的 `name`；缺失时退到 projectId —— 那是唯一稳定的项目身份）。 */
        val pkg: String,
        val version: String,
        /** 整份 scripts 的内容哈希（审批键的 versionHash 段）。 */
        val versionHash: String,
        val scripts: Map<String, String>,
    )

    /** 一个纯 JS bin 的解析结果。 */
    data class BinTarget(
        val pkg: String,
        val version: String,
        val versionHash: String,
        /** bin 目标文件（已 normalize 并确认在包目录内）。 */
        val entry: Path,
    )

    /**
     * 项目自身 `package.json` 的 `scripts` 段。
     *
     * manifest 不存在即 null（调用方转 `ERR_FILE_NOT_FOUND` 并说明「该项目还没有
     * package.json，跑不了 npm run」）——**不**返回空 scripts：空表与「没有这个项目」
     * 对调用方是两件事，后者要报错而不是安静地当成「没有脚本」。
     */
    fun projectScripts(projectRoot: Path, projectId: String): ProjectScripts? {
        val manifest = manifestOf(projectRoot) ?: return null
        val pkg = str(manifest, "name")?.takeIf { it.isNotBlank() } ?: projectId
        val version = str(manifest, "version")?.takeIf { it.isNotBlank() } ?: "0.0.0"
        val scripts = LinkedHashMap<String, String>()
        (manifest["scripts"] as? DomainJson.Value.Obj)?.fields
            ?.toSortedMap()
            ?.forEach { (k, v) -> (v as? DomainJson.Value.S)?.let { scripts[k] = it.v } }
        val canonical = scripts.entries.joinToString(NEWLINE) { it.key + "=" + it.value }
        return ProjectScripts(pkg, version, hashOf(pkg + "@" + version, canonical), scripts)
    }

    /**
     * `node_modules` 里声明了名为 [bin] 的 bin 的那个包（纯 JS 白名单已过）。
     *
     * 扫描深度 1（`node_modules/<pkg>`）+ 深度 2 的 scope（`node_modules/@scope/<pkg>`），
     * 目录名排序保证结果确定。**不解析 `node_modules/.bin/` 下的链接**：Android 侧 bin-links
     * 是 npm 生成的 shim 文件而不是符号链接（§10.3 T0「bin-links 走纯 fs」），两条形态不共用
     * 一条解析路径；从**声明侧**解析则两种布局同形，且「装出来是什么」以 manifest 为准。
     *
     * 多个包声明同名 bin → `ERR_NOT_SUPPORTED` 并点名候选：npm 自己的消歧依赖安装顺序与
     * hoist 布局，宿主重算不出一个稳定答案，猜一个就是让审批哈希对不上真正的执行目标。
     */
    fun binTarget(projectRoot: Path, bin: String): BinTarget? {
        val nodeModules = projectRoot.resolve("node_modules")
        if (!Files.isDirectory(nodeModules)) return null
        val hits = packageDirs(nodeModules).mapNotNull { dir ->
            val manifest = manifestOf(dir) ?: return@mapNotNull null
            val rel = binRelPath(manifest, bin) ?: return@mapNotNull null
            dir to rel
        }
        if (hits.isEmpty()) return null
        if (hits.size > 1) {
            throw AutojsException(
                ErrorCode.ERR_NOT_SUPPORTED,
                "多个包声明同名 bin「" + bin + "」（" +
                    hits.joinToString(", ") { it.first.fileName.toString() } +
                    "）：宿主无法确定执行目标（npm 依赖安装顺序消歧，审批哈希须绑定唯一目标）",
            )
        }
        val (pkgDir, rel) = hits.single()
        val fileName = rel.substringAfterLast('/')
        if (!JS_SUFFIXES.any { fileName.endsWith(it, ignoreCase = true) }) {
            throw AutojsException(
                ErrorCode.ERR_NOT_SUPPORTED,
                "bin「" + bin + "」不是纯 JS 目标（" + rel + "）：设备端不执行 app 数据区里的原生二进制" +
                    "（W^X，§10.3）。请改用 wasm 实现或纯 JS 替代（如 esbuild-wasm / node:sqlite / bcryptjs）",
            )
        }
        val entry = pkgDir.resolve(rel).normalize()
        if (!entry.startsWith(pkgDir)) {
            throw AutojsException(
                ErrorCode.ERR_NOT_SUPPORTED,
                "bin「" + bin + "」的声明路径逃出包目录（" + rel + "）：拒绝执行",
            )
        }
        if (!Files.isRegularFile(entry)) {
            throw AutojsException(
                ErrorCode.ERR_FILE_NOT_FOUND,
                "bin「" + bin + "」的目标文件不存在：" + entry + "（声明在 manifest 但未物化，包是否装完整？）",
            )
        }
        val manifest = manifestOf(pkgDir)!!
        val pkg = str(manifest, "name")?.takeIf { it.isNotBlank() } ?: pkgDir.fileName.toString()
        val version = str(manifest, "version")?.takeIf { it.isNotBlank() } ?: "0.0.0"
        return BinTarget(pkg, version, hashOf(pkg + "@" + version, "bin:" + bin + "=" + rel), entry)
    }

    // —— 内部 ——

    private const val NEWLINE = "\n"

    /** 读并解析一个 package.json；不存在/不是普通文件 → null；存在但坏了 → 抛（不装作没有）。 */
    private fun manifestOf(dir: Path): Map<String, DomainJson.Value>? {
        val file = dir.resolve("package.json")
        if (!Files.isRegularFile(file)) return null
        return try {
            DomainJson.decodeObject(String(Files.readAllBytes(file), StandardCharsets.UTF_8))
        } catch (e: Exception) {
            throw AutojsException(ErrorCode.ERR_IO, "package.json 解析失败（" + file + "）：" + e.message, e)
        }
    }

    private fun str(o: Map<String, DomainJson.Value>, key: String): String? =
        (o[key] as? DomainJson.Value.S)?.v

    /**
     * bin 声明的相对路径：两种形态都认 —— `"bin": "./cli.js"`（简写）与
     * `"bin": {"<name>": "./cli.js"}`（显式映射）。认不出来即 null（该包不提供此 bin）。
     */
    private fun binRelPath(manifest: Map<String, DomainJson.Value>, bin: String): String? =
        when (val b = manifest["bin"]) {
            // 简写形的隐含 bin 名是**包名**（scope 剥掉），与 npm `normalize-package-bin` 同款。
            // 注意不是文件名：`{"name":"my-server","bin":"./server.js"}` 装出来的命令叫
            // `my-server` 而不叫 `server.js`；按文件名匹配会让绝大多数单命令包都找不到。
            is DomainJson.Value.S ->
                if (b.v.isNotBlank() && bin == impliedBinName(manifest)) b.v else null
            is DomainJson.Value.Obj ->
                (b.fields[bin] as? DomainJson.Value.S)?.v?.takeIf { it.isNotBlank() }
            else -> null
        }

    /** 简写 `bin` 的隐含命令名 = manifest 的 `name` 去掉 `@scope/` 前缀。 */
    private fun impliedBinName(manifest: Map<String, DomainJson.Value>): String? =
        str(manifest, "name")?.takeIf { it.isNotBlank() }?.substringAfterLast('/')

    /** 深度 1 包目录 + 深度 2 scope 包目录，按路径名排序（结果确定）。 */
    private fun packageDirs(nodeModules: Path): List<Path> {
        val out = ArrayList<Path>()
        Files.list(nodeModules).use { s ->
            s.filter { Files.isDirectory(it) }.sorted().forEach { entry ->
                val name = entry.fileName.toString()
                if (name.startsWith("@")) {
                    Files.list(entry).use { inner ->
                        inner.filter { Files.isDirectory(it) }.sorted().forEach { out.add(it) }
                    }
                } else if (!name.startsWith(".")) {
                    out.add(entry)
                }
            }
        }
        return out
    }

    /**
     * 规范化内容哈希：身份与正文**分别带长度**再拼，避免拼接歧义
     * （identity="ab" body="c" 与 identity="a" body="bc" 裸拼都是 `abc` —— 那会让两个不同的
     * 审批主体落进同一张票）。
     */
    private fun hashOf(identity: String, body: String): String {
        val canonical = identity.length.toString() + ":" + identity + " " + body.length.toString() + ":" + body
        return "sha256:" + DirSizer.sha256(canonical.toByteArray(StandardCharsets.UTF_8))
    }
}
