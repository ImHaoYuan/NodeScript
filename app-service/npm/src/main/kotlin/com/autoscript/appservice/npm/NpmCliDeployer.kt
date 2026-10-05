package com.autoscript.appservice.npm

import com.autoscript.domain.json.DomainJson
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.nio.file.StandardOpenOption

/** 将打包前的 npm 文件清单与 APK 实际可读的资产、落盘目录逐项对账。 */
object NpmCliDeployer {
    private val ANCHORS = listOf("bin/npm-cli.js", "bin/npx-cli.js")
    private const val MARKER = ".cli-manifest.sha256"
    private val SHA256 = Regex("[0-9a-f]{64}")

    interface CliSource {
        fun read(relPath: String): ByteArray?
        fun list(): List<String>
        /** 构建期生成、位于 assets 根的 npm-manifest.json；生产缺失必须报错。 */
        fun manifest(): String?
    }

    sealed interface Outcome {
        data class Ready(val cliJs: Path, val deployedFresh: Boolean) : Outcome
    }

    fun npmRoot(filesDir: Path): Path = filesDir.resolve("npm")
    fun cliJsPath(filesDir: Path): Path = npmRoot(filesDir).resolve("bin/npm-cli.js")

    private data class Entry(val path: String, val sha256: String)
    private data class Manifest(val files: List<Entry>, val bytes: Long, val identity: String)

    private fun parseManifest(text: String): Manifest {
        val fields = DomainJson.decodeObject(text)
        val count = DomainJson.optLong(fields, "count") ?: error("npm 清单缺 count")
        val bytes = DomainJson.optLong(fields, "bytes") ?: error("npm 清单缺 bytes")
        val files = (fields["files"] as? DomainJson.Value.Arr)?.items
            ?: error("npm 清单缺 files 数组")
        require(count > 0 && bytes >= 0 && files.size.toLong() == count) {
            "npm 清单件数不一致：count=$count files=${files.size}"
        }
        val entries = files.map { value ->
            val item = (value as? DomainJson.Value.Obj)?.fields ?: error("npm 清单文件条目不是对象")
            val path = DomainJson.reqStr(item, "path")
            val hash = DomainJson.reqStr(item, "sha256")
            require(safePath(path) && path != MARKER && SHA256.matches(hash)) {
                "npm 清单路径或 SHA-256 非法：$path"
            }
            Entry(path, hash)
        }
        require(entries.map { it.path }.toSet().size == entries.size) { "npm 清单有重复路径" }
        require(ANCHORS.all { anchor -> entries.any { it.path == anchor } }) {
            "vendored npm CLI 素材缺锚文件：${ANCHORS.filter { anchor -> entries.none { it.path == anchor } }}"
        }
        // 身份覆盖整棵树，而不只是 bin/npm-cli.js；清单顺序不改变身份。
        val identity = DirSizer.sha256(
            DomainJson.encode(
                mapOf(
                    "count" to count, "bytes" to bytes,
                    "files" to entries.sortedBy { it.path }.map { mapOf("path" to it.path, "sha256" to it.sha256) },
                ),
            )
                .toByteArray(StandardCharsets.UTF_8),
        )
        return Manifest(entries, bytes, identity)
    }

    private fun safePath(path: String): Boolean =
        path.isNotBlank() && !path.startsWith('/') && !path.contains('\\') &&
            !path.contains('\u0000') && path.split('/').all { it.isNotEmpty() && it != "." && it != ".." }

    private fun treeMatches(target: Path, expected: Manifest): Boolean {
        if (!Files.isDirectory(target, LinkOption.NOFOLLOW_LINKS)) return false
        val paths = Files.walk(target).use { walk ->
            walk.filter { Files.isRegularFile(it, LinkOption.NOFOLLOW_LINKS) }
                .map { target.relativize(it).toString().replace('\\', '/') }
                .filter { it != MARKER }.collect(java.util.stream.Collectors.toList())
        }
        if (paths.toSet() != expected.files.map { it.path }.toSet() || paths.size != expected.files.size) return false
        return expected.files.all { entry ->
            val file = target.resolve(entry.path)
            Files.isRegularFile(file, LinkOption.NOFOLLOW_LINKS) &&
                DirSizer.sha256(Files.readAllBytes(file)) == entry.sha256
        }
    }

    /** 源树检疫在缓存判断之前完成；失败不触碰旧目录。 */
    fun deploy(filesDir: Path, source: CliSource): Outcome {
        val manifestText = source.manifest() ?: error("vendored npm CLI 缺 npm-manifest.json（APK 素材未完整交付）")
        val expected = parseManifest(manifestText)
        val rels = source.list()
        val actualPaths = rels.toSet()
        val expectedPaths = expected.files.map { it.path }.toSet()
        require(rels.size == actualPaths.size && rels.all(::safePath) && actualPaths == expectedPaths) {
            "vendored npm CLI 清单与 APK 文件集合不一致：缺 ${expectedPaths - actualPaths}；多 ${actualPaths - expectedPaths}"
        }
        var sourceBytes = 0L
        for (entry in expected.files) {
            val bytes = source.read(entry.path) ?: error("vendored npm CLI 素材不可读：${entry.path}")
            sourceBytes += bytes.size
            require(DirSizer.sha256(bytes) == entry.sha256) { "vendored npm CLI 素材哈希不匹配：${entry.path}" }
        }
        require(sourceBytes == expected.bytes) { "vendored npm CLI 素材字节数不符：$sourceBytes != ${expected.bytes}" }

        Files.createDirectories(filesDir)
        val target = npmRoot(filesDir)
        val cli = cliJsPath(filesDir)
        val marker = target.resolve(MARKER)
        if (Files.isRegularFile(marker, LinkOption.NOFOLLOW_LINKS) &&
            String(Files.readAllBytes(marker), StandardCharsets.UTF_8).trim() == expected.identity &&
            treeMatches(target, expected)
        ) return Outcome.Ready(cli, deployedFresh = false)

        Files.list(filesDir).use { s ->
            s.filter {
                val n = it.fileName.toString()
                n.startsWith(".npm-deploy-") || n.startsWith(".npm-tombstone-")
            }.forEach { it.toFile().deleteRecursively() }
        }
        val tmp = Files.createDirectories(filesDir.resolve(".npm-deploy-" + System.nanoTime().toString(36)))
        try {
            for (entry in expected.files) {
                val bytes = source.read(entry.path) ?: error("vendored npm CLI 素材不可读：${entry.path}")
                require(DirSizer.sha256(bytes) == entry.sha256) { "vendored npm CLI 素材哈希不匹配：${entry.path}" }
                val dest = tmp.resolve(entry.path)
                Files.createDirectories(dest.parent)
                Files.write(dest, bytes, StandardOpenOption.CREATE_NEW)
            }
            require(treeMatches(tmp, expected)) { "vendored npm CLI 部署校验失败：磁盘文件集合或哈希不匹配" }
            Files.write(tmp.resolve(MARKER), expected.identity.toByteArray(StandardCharsets.UTF_8), StandardOpenOption.CREATE_NEW)
            if (Files.isDirectory(target)) {
                val tomb = filesDir.resolve(".npm-tombstone-" + System.nanoTime().toString(36))
                Files.move(target, tomb, StandardCopyOption.ATOMIC_MOVE)
                try {
                    Files.move(tmp, target, StandardCopyOption.ATOMIC_MOVE)
                } catch (e: Exception) {
                    Files.move(tomb, target, StandardCopyOption.ATOMIC_MOVE)
                    throw e
                }
                tomb.toFile().deleteRecursively()
            } else {
                Files.move(tmp, target, StandardCopyOption.ATOMIC_MOVE)
            }
            return Outcome.Ready(cli, deployedFresh = true)
        } finally {
            tmp.toFile().deleteRecursively()
        }
    }
}
