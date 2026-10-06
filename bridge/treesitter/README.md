# `:bridge:treesitter` — tree-sitter 语法解析器（JNI 绑定）

编辑器语法高亮的 C++ 层：tree-sitter 增量解析 + JNI 导出。

## 架构

```
:platform:editor (Kotlin)
  ↓ JNI 调用
libtreesitter.so (本模块)
  ↓ 链接
libtree-sitter.so (核心解析器)
libtree-sitter-javascript.so (JS grammar)
```

## 构建

**本机交叉编译**（需要 NDK r28c）：

```bash
export ANDROID_NDK_HOME=/path/to/ndk/28.0.12674087
bash scripts/build-treesitter.sh
```

产物在 `build/native-local/arm64-v8a/`：
- `libtree-sitter.so` (~200 KB)
- `libtree-sitter-javascript.so` (~350 KB)
- `libtreesitter.so` (~50 KB，JNI 绑定)

**CI 构建**：见 `.github/workflows/treesitter-native.yml`（推送 `bridge/treesitter/**` 时触发）。

## API

### 计算核（`treesitter_native.cpp`，零 JNI 依赖）

```cpp
void ts_init_parser();  // 初始化（加载 JS grammar）

int32_t ts_parse_and_highlight(
    const char* source_utf8,
    int32_t source_len,
    int32_t* out_spans,     // [start, end, kind, ...] 输出数组
    int32_t max_spans,
    int32_t* out_count
);
// 返回 0=OK, -1=未初始化, -2=解析失败, -3=buffer 太小

void ts_cleanup_parser();  // 释放资源
```

### JNI 层（`treesitter_jni.cc`）

```kotlin
// Kotlin 调用侧（`:platform:editor`）
object TreeSitterNative {
    external fun initParser()
    external fun parseAndHighlight(source: String, outSpans: IntArray): Int
    external fun cleanupParser()
}
```

## SyntaxKind 枚举

| kind | 值 | 示例 |
|---|---|---|
| KEYWORD | 0 | `function`, `const`, `if`, `return` |
| STRING | 1 | `"hello"`, `'world'`, `` `template` `` |
| COMMENT | 2 | `// ...`, `/* ... */` |
| NUMBER | 3 | `42`, `3.14` |
| FUNCTION_NAME | 4 | `function foo()` 的 `foo` |
| OPERATOR | 5 | `+`, `-`, `*`, `/` |
| TYPE | 6 | `interface`, `type` |

## 依赖版本

- tree-sitter 核心：`v0.20.8`（2023-06 release）
- JS grammar：`v0.20.1`（支持 ES2020）
- NDK：r28c（API 26 minSdk）

## 单测

**C++ 单测**（`src/test/cpp/treesitter_native_test.cpp`）：纯计算核，x86_64 本机可跑。

**JNI 层单测**：在 `:platform:editor` 的 Robolectric 测试里覆盖。

## 文件清单

```
bridge/treesitter/
├── build.gradle.kts                    # Android Library 配置
├── scripts/
│   └── build-treesitter.sh             # 独立构建脚本
├── src/main/cpp/
│   ├── treesitter_native.h             # 计算核头文件
│   ├── treesitter_native.cpp           # 计算核实现（遍历 AST）
│   └── treesitter_jni.cc               # JNI 装载面（类型转换）
├── src/test/cpp/
│   └── treesitter_native_test.cpp      # C++ 单测
└── .gitignore                          # 忽略 build/ 和 jniLibs/
```

## 后续扩展

- 多语言支持：增加 TypeScript / Python grammar（各 +300 KB）
- 错误恢复：解析失败时标红错误位置
- 增量更新优化：只重扫改动行 ±10 行
