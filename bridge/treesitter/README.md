# `:bridge:treesitter`：tree-sitter 语法高亮 JNI

本模块提供 JavaScript 语法高亮的 native 计算核。每个编辑文档拥有一个独立的
Tree-sitter session，session 保留增量语法树；调用方必须串行调用同一 session，关闭文档时销毁。
解析器缺位时上层可使用 `SyntaxHighlighter.NONE`，不会阻塞编辑能力。

## 架构

```
TreeSitterNative（Kotlin/Java facade）
  └─ libtreesitter.so       JNI + session/增量树
       ├─ libtree-sitter.so
       └─ libtree-sitter-javascript.so
```

`libtree-sitter.so` 与 grammar 的公开 C 符号保持默认可见性；JNI 库只保留三个 Java
入口。三个库都显式写入 basename SONAME，JNI 的 `DT_NEEDED` 只引用本模块交付的两库及
Android 系统库。

## 构建

交叉编译需要 `ANDROID_NDK_HOME`（必须与 `node-runtime-build/VERSIONS.env` 的
`NDK_VERSION_CODE` 一致）和 `JAVA_HOME`。API level 也从同一份 `VERSIONS.env` 读取，
不在脚本中复制版本号：

```bash
export ANDROID_NDK_HOME=/path/to/android-ndk
export JAVA_HOME=/path/to/jdk-17
bash bridge/treesitter/scripts/build-treesitter.sh
```

默认工作目录是 `bridge/treesitter/build`，产物目录是
`build/out-treesitter/arm64-v8a/`。可用 `WORK_DIR` 或 `OUT_DIR` 覆盖。源码缓存固定为
core `0c49d6745b3fc4822ab02e0018770cd6383a779c`、JavaScript grammar
`f1e5a09b8d02f8209a68249c93f0ad647b228e6e`；已有缓存若 dirty 或 commit 不符会直接失败，
脚本不会 reset、clean、checkout 或删除用户缓存。

构建结束自动执行 `scripts/check-treesitter-alignment.sh`，检查 AArch64 ELF64/ET_DYN、
每个 LOAD 的 16KB 对齐与 `p_offset ≡ p_vaddr`、basename SONAME、NEEDED 依赖、core/grammar
本地符号可满足性，以及 JNI 恰好三个导出。若 JNI 源码尚未切换到新会话 API，门禁会明确
报告待最终 cross build；该门禁不以 host 构建替代 Android 产物门禁。

`-static-libstdc++` 只用于避免额外分发 `libc++_shared.so`；core、grammar 与 JNI 间没有
跨库 C++ 对象或异常边界，不能据此推断“两套 STL 必炸”。

## Native API

### 计算核（`src/main/cpp/treesitter_native.h`）

```cpp
struct TsHighlightSession;

TsHighlightSession* ts_create_session();
void ts_destroy_session(TsHighlightSession* session);

int32_t ts_highlight(
    TsHighlightSession* session,
    const char* source_utf8,  // UTF-8 字节串
    int32_t source_len,       // 字节长度
    int32_t* out_spans,       // [start, end, kind, ...]，字节偏移
    int32_t capacity,         // span 容量（不是 int 数量）
    int32_t* count             // 实际写入 span 数
);
```

返回非负值表示写入的 span 数；负值为 `-1` 无效/已销毁 session、`-2` native
解析/资源失败、`-3` 输出容量不足、`-4` 参数非法。高亮区间的 `start/end` 是 UTF-8
字节偏移；Kotlin facade 必须将其转换为 `String` 的 UTF-16 下标后再喂给文本呈现层。
`capacity` 是 span 数量，每个 span 占三个 `Int`。增量解析复用 session 的语法树，
但增量不等于只返回全部 AST 的变更节点：每次 `highlight` 返回当前源文本扫描得到的
完整 span 集合；调用方可按编辑范围自行过滤变更。

### JNI facade

类 `TreeSitterNative` 为实例类，native 库入口严格为：

```kotlin
class TreeSitterNative {
    fun createSession(): Long
    fun highlight(session: Long, sourceUtf8: ByteArray, outSpans: IntArray): Int
    fun destroySession(session: Long)
}
```

`sourceUtf8` 是调用方明确编码后的 UTF-8 bytes，不是 Kotlin UTF-16 字符数。返回值与
计算核一致：非负为写入 span 条数，`-1/-2/-3/-4` 依次表示无效 session、解析/资源失败、
容量不足、参数非法。`destroySession` 幂等；关闭后旧句柄再次 highlight 返回 `-1`。
Kotlin 侧通过 `ByteArray` 的 UTF-8 字节偏移转成 `String` UTF-16 偏移，不能把 native
区间直接当作 `String` 下标。

## 语法种类

| `kind` | 值 | 示例 |
|---|---:|---|
| `KEYWORD` | 0 | `function`, `const`, `if`, `return` |
| `STRING` | 1 | 字符串、模板字符串 |
| `COMMENT` | 2 | `// ...`, `/* ... */` |
| `NUMBER` | 3 | `42`, `3.14` |
| `FUNCTION_NAME` | 4 | 函数名、调用目标 |
| `OPERATOR` | 5 | `+`, `-`, `=>` |
| `TYPE` | 6 | 类名、构造目标 |

## 测试

host 语义测试位于 `test/cpp/`，不是 `src/test/`。它把纯计算核与相同 pinned
core/grammar 源码以 x86_64 host 编译，覆盖 AST 高亮、UTF-8 字节偏移、UTF-16 转换边界、
增量复用与容量错误：

```bash
TS_SRC=/path/to/tree-sitter \
TS_GRAMMAR_SRC=/path/to/tree-sitter-javascript \
bash bridge/treesitter/test/cpp/run-host-tests.sh
```

测试脚本会校验源码 commit（tarball 无 `.git` 时只提示并跳过 pin 对账）。JNI/Android
ELF 门禁由 native build 自动调用 alignment script。当前未填入 APK 体积实测值；上述产物
字节数是未 strip 的 native 文件大小，不代表最终 APK 增量。

## 版本与边界

- tree-sitter core：`v0.20.8`，commit `0c49d6745b3fc4822ab02e0018770cd6383a779c`
- JavaScript grammar：`v0.20.1`，commit `f1e5a09b8d02f8209a68249c93f0ad647b228e6e`
- NDK/API：由 `node-runtime-build/VERSIONS.env` 冻结，ABI 为 `arm64-v8a`
- 当前 grammar 仅覆盖 JavaScript；TypeScript/Python 等语言需另行 pin 与门禁
