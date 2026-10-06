package com.autoscript.ui.screens

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Test

/**
 * 编辑器的读写两条窄腰（[loadScriptText] / [saveScriptText]）。
 *
 * 钉住的是编辑器 KDoc 里那条纪律：**读取失败不冒充空文件**。空文件（`""`）是可以直接
 * 写内容的，而"没读到"写下去就是把原内容抹掉 —— 两者在界面上长得不一样，靠的就是
 * 这里 `text` 是 `""` 还是 `null`。取消则必须原样抛（吞了会让"换文件时的重读"
 * 变成上一位的结果盖到新文件上）。
 */
class ScriptEditorIoTest {

    @Test
    fun `读到空文件_是空串不是读不到`() = runBlocking {
        val loaded = loadScriptText("demo", "demo/empty.txt") { _, _ -> "" }
        assertEquals("", loaded.text)
        assertNull(loaded.error)
    }

    @Test
    fun `读失败_正文是null且原文带上`() = runBlocking {
        val loaded = loadScriptText("demo", "demo/main.js") { _, _ -> error("不是文件：demo/main.js") }
        assertNull(loaded.text)
        assertEquals("不是文件：demo/main.js", loaded.error)
    }

    @Test
    fun `读的时候被取消_照原样抛`() {
        assertThrows(CancellationException::class.java) {
            runBlocking {
                loadScriptText("demo", "demo/main.js") { _, _ -> throw CancellationException("走了") }
            }
        }
    }

    @Test
    fun `保存成功_结果是null`() = runBlocking {
        var written: String? = null
        val failure = saveScriptText("demo", "demo/main.js", "console.log(1)") { _, _, content ->
            written = content
        }
        assertNull(failure)
        assertEquals("console.log(1)", written)
    }

    @Test
    fun `保存失败_原文带回来而不是抛`() = runBlocking {
        val failure = saveScriptText("demo", "demo/main.js", "x") { _, _, _ -> error("磁盘满了") }
        assertEquals("磁盘满了", failure)
    }

    @Test
    fun `保存时被取消_照原样抛`() {
        assertThrows(CancellationException::class.java) {
            runBlocking {
                saveScriptText("demo", "demo/main.js", "x") { _, _, _ -> throw CancellationException("走了") }
            }
        }
    }
}
