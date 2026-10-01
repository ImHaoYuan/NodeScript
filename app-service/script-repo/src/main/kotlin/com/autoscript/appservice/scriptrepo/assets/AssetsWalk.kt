package com.autoscript.appservice.scriptrepo.assets

import java.io.InputStream

/**
 * assets 递归枚举的纯逻辑（§9.6 assets → filesDir 原子部署的读侧）。
 *
 * 为什么独立成文件而不是 [AndroidAssetsSource] 的 companion：[walk] 是**纯逻辑**
 * ——吃的是 `(String) -> Array<String>?` 与 `(String) -> InputStream` 两个函数，单测
 * 直接喂假实现即可；混进 companion 就得先造出一个
 * [android.content.res.AssetManager] 实例才走得到（JVM 单测里 android.jar 是可 mock 的
 * 桩，方法一调就抛），于是「可单测的纯逻辑」实际不可单测。
 * （2026-09-30 前还有一条更硬的约束：本机旁路按源文件裸 kotlinc 编译，companion 会连坐
 * `AssetManager` 一起编不过；旁路已删，这条不再成立。）
 *
 * [android.content.res.AssetManager.list] 只返回当前层且把子目录与文件混排，
 * Assets 也没有 Path API，故这里自维护队列做广度优先；目录判定用「子目录名带尾 '/'」
 * 优先、再以「list 出内容」兜底（两种 ROM 行为都覆盖）。
 */
object AssetsWalk {

    /**
     * @param root assets 内的起始目录（如 `scripts/demo`）。
     * @param list 列目录：返当前层条目（目录名可能带尾 '/'）；目录不存在返 null。
     * @param open 开文件流：由本函数负责关闭。
     * @return 相对 [root] 的路径（'/' 分隔，含子目录）→ 文件字节。
     */
    fun walk(
        root: String,
        list: (String) -> Array<String>?,
        open: (String) -> InputStream,
    ): Map<String, ByteArray> {
        val out = LinkedHashMap<String, ByteArray>()
        val dirs = ArrayDeque<String>()
        dirs.addLast(root)
        while (dirs.isNotEmpty()) {
            val dir = dirs.removeFirst()
            val names = list(dir) ?: continue
            for (raw in names) {
                if (raw.isBlank()) continue
                // 目录名可能带尾 '/'（真机行为）：入队前剥掉，保证 list/open 收到的路径形态一致
                val isDirBySuffix = raw.endsWith("/")
                val name = raw.trimEnd('/')
                if (name.isEmpty()) continue
                val path = "$dir/$name"
                val rel = if (dir == root) name else "${dir.removePrefix("$root/")}/$name"
                if (isDirBySuffix || !list(path).isNullOrEmpty()) {
                    dirs.addLast(path)
                    continue
                }
                out[rel] = open(path).use { it.readBytes() }
            }
        }
        return out
    }
}
