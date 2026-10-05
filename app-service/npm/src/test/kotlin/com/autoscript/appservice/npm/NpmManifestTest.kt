package com.autoscript.appservice.npm

import com.autoscript.domain.json.DomainJson
import com.autoscript.testkit.npmManifest
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Files
import java.nio.file.Path

/** 不依赖宿主 npm：打包前清单保持不动，单独破坏 APK 的可读资产。 */
class NpmManifestTest {
    @TempDir lateinit var dir: Path

    private class Source : NpmCliDeployer.CliSource {
        val tree = linkedMapOf(
            "bin/npm-cli.js" to "entry".toByteArray(),
            "bin/npx-cli.js" to "npx".toByteArray(),
            "node_modules/pkg/__generated__/index.js" to "dependency".toByteArray(),
        )
        var manifestText: String? = npmManifest(tree)
        var unreadable: String? = null
        override fun manifest() = manifestText
        override fun list() = tree.keys.toList()
        override fun read(relPath: String) = if (relPath == unreadable) null else tree[relPath]
    }

    @Test fun `清单齐全可部署并幂等`() {
        val src = Source()
        assertTrue((NpmCliDeployer.deploy(dir, src) as NpmCliDeployer.Outcome.Ready).deployedFresh)
        assertFalse((NpmCliDeployer.deploy(dir, src) as NpmCliDeployer.Outcome.Ready).deployedFresh)
    }

    @Test fun `APK 丢失生成目录时点名缺件且保留旧树`() {
        val src = Source()
        NpmCliDeployer.deploy(dir, src)
        val missing = src.tree.keys.last()
        src.tree.remove(missing)
        val ex = assertThrows(IllegalArgumentException::class.java) { NpmCliDeployer.deploy(dir, src) }
        assertTrue(ex.message!!.contains(missing))
        assertEquals("dependency", String(Files.readAllBytes(dir.resolve("npm/$missing"))))
    }

    @Test fun `缓存不能掩盖源文件哈希损坏`() {
        val src = Source()
        NpmCliDeployer.deploy(dir, src)
        src.tree["bin/npx-cli.js"] = "broken".toByteArray()
        val ex = assertThrows(IllegalArgumentException::class.java) { NpmCliDeployer.deploy(dir, src) }
        assertTrue(ex.message!!.contains("bin/npx-cli.js"))
        assertEquals("npx", String(Files.readAllBytes(dir.resolve("npm/bin/npx-cli.js"))))
    }

    @Test fun `入口未变但依赖改变仍重部署`() {
        val src = Source()
        NpmCliDeployer.deploy(dir, src)
        val dependency = src.tree.keys.last()
        src.tree[dependency] = "v2".toByteArray()
        src.manifestText = npmManifest(src.tree)
        assertTrue((NpmCliDeployer.deploy(dir, src) as NpmCliDeployer.Outcome.Ready).deployedFresh)
        assertEquals("v2", String(Files.readAllBytes(dir.resolve("npm/$dependency"))))
    }

    @Test fun `部署目录损坏时重新物化`() {
        val src = Source()
        NpmCliDeployer.deploy(dir, src)
        Files.delete(dir.resolve("npm/bin/npx-cli.js"))
        assertTrue((NpmCliDeployer.deploy(dir, src) as NpmCliDeployer.Outcome.Ready).deployedFresh)
        assertEquals("npx", String(Files.readAllBytes(dir.resolve("npm/bin/npx-cli.js"))))
    }

    @Test fun `缺清单与不可读文件均拒绝部署`() {
        val src = Source()
        src.manifestText = null
        assertThrows(IllegalStateException::class.java) { NpmCliDeployer.deploy(dir, src) }
        src.manifestText = npmManifest(src.tree)
        src.unreadable = "bin/npx-cli.js"
        val ex = assertThrows(IllegalStateException::class.java) { NpmCliDeployer.deploy(dir, src) }
        assertTrue(ex.message!!.contains("bin/npx-cli.js"))
        assertFalse(Files.exists(dir.resolve("npm")))
    }

    @Test fun `非法路径重复条目与错误计数不可落盘`() {
        for (path in listOf("../outside", "/absolute", "a/../escape", "a\\b", ".cli-manifest.sha256")) {
            val src = Source()
            src.tree[path] = byteArrayOf(1)
            src.manifestText = npmManifest(src.tree)
            assertThrows(IllegalArgumentException::class.java) { NpmCliDeployer.deploy(dir, src) }
        }
        val src = Source()
        val entry = mapOf("path" to "bin/npm-cli.js", "sha256" to DirSizer.sha256("entry".toByteArray()))
        src.manifestText = DomainJson.encode(mapOf("count" to 2, "bytes" to 10, "files" to listOf(entry, entry)))
        assertThrows(IllegalArgumentException::class.java) { NpmCliDeployer.deploy(dir, src) }
        src.manifestText = npmManifest(src.tree).replace("\"count\":3", "\"count\":4")
        assertThrows(IllegalArgumentException::class.java) { NpmCliDeployer.deploy(dir, src) }
        src.manifestText = npmManifest(src.tree).replace("\"bytes\":18", "\"bytes\":19")
        assertThrows(IllegalArgumentException::class.java) { NpmCliDeployer.deploy(dir, src) }
        assertFalse(Files.exists(dir.resolve("npm")))
    }
}
