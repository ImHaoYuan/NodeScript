package com.autoscript.platform.editor

/** JNI 缝：每个高亮器独占一个非零 session；输出为 UTF-8 byte offset 三元组。 */
internal interface TreeSitterNativeOps {
    fun createSession(): Long
    fun highlight(handle: Long, source: ByteArray, outSpans: IntArray): Int
    fun destroySession(handle: Long)
}

/** 实例方法名及签名必须与 treesitter_jni.cc 的 JNI 符号同步。 */
internal class TreeSitterNative private constructor() : TreeSitterNativeOps {
    external override fun createSession(): Long
    external override fun highlight(handle: Long, source: ByteArray, outSpans: IntArray): Int
    external override fun destroySession(handle: Long)

    companion object {
        // 不在类初始化器里加载：单测可只替换 loader，且失败不会把类永久标为 erroneous。
        fun load(loadLibrary: (String) -> Unit = System::loadLibrary): TreeSitterNative {
            loadLibrary("tree-sitter")
            loadLibrary("tree-sitter-javascript")
            loadLibrary("treesitter")
            return TreeSitterNative()
        }
    }
}
