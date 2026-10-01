package com.autoscript.appservice.scriptrepo.core

import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardOpenOption
import java.security.MessageDigest

/**
 * 部署目录/路径安全（docs §9.6：assets→filesDir 原子部署 + 防逃逸）。
 * 全部相对路径写入前必须过 [isSafeRelPath] 与 [resolveIn]。
 */
object DeployPath {

    /** relPath 合法：非空、相对、段内无 `..`/`.`/空段/反斜杠。 */
    fun isSafeRelPath(relPath: String): Boolean {
        if (relPath.isBlank()) return false
        if (relPath.startsWith("/") || relPath.startsWith("\\")) return false
        for (seg in relPath.split('/')) {
            if (seg == ".." || seg == "." || seg.isEmpty() || seg.contains('\\')) return false
        }
        return true
    }

    /** 解析到 root 之下并做二次校验（防符号链接/边界绕行）。 */
    fun resolveIn(root: Path, relPath: String): Path {
        require(isSafeRelPath(relPath)) { "非法相对路径: $relPath" }
        val base = root.toAbsolutePath().normalize()
        val target = base.resolve(relPath).normalize()
        require(target.startsWith(base)) { "路径逃逸拒绝: $relPath" }
        return target
    }

    fun sha256(bytes: ByteArray): String =
        MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }

    fun sha256(file: Path): String = sha256(Files.readAllBytes(file))

    /** 目录 fsync：先创建目录（若缺），再对目录句柄 force。 */
    fun fsyncDirectory(dir: Path) {
        Files.createDirectories(dir)
        FileChannelForce.force(dir)
    }

    /** 单文件 fsync。 */
    fun fsyncFile(file: Path) {
        Files.newByteChannel(file, StandardOpenOption.WRITE).use { ch ->
            (ch as? java.nio.channels.FileChannel)?.force(true) ?: Unit
        }
    }

    private object FileChannelForce {
        fun force(dir: Path) {
            Files.newByteChannel(dir).use { ch ->
                (ch as? java.nio.channels.FileChannel)?.force(true) ?: Unit
            }
        }
    }
}

/** URL 编码关键字段，避免行式 journal 被分隔符污染。
 *
 * 用 `(String, String)` 重载而不是 `(String, Charset)`：后者在 Android 上 **API 33** 才出现
 * （minSdk 26 走到即 NoSuchMethodError）；字符集名写 `StandardCharsets.UTF_8.name()` ——
 * 与 Charset 重载语义逐字相同（都是 UTF-8 百分号编码，空格编成 `+`）。
 * JDK 侧这个重载自 Java 10 起 deprecated，Android 侧不是；为 minSdk 26 只能选它。 */
internal object FieldCodec {
    fun enc(s: String): String = java.net.URLEncoder.encode(s, StandardCharsets.UTF_8.name())
    fun dec(s: String): String = java.net.URLDecoder.decode(s, StandardCharsets.UTF_8.name())
}