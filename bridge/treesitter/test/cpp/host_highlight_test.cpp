// 宿主机语义测试：把 :bridge:treesitter 的**计算核**（treesitter_native.cpp —— 零 JNI）
// 与同 commit 的 tree-sitter 核心 + JS grammar 链成一个 x86_64 可执行文件，跑断言。
//
// 为什么需要它（与 :bridge:image 的 host 测试同一理由）：`-fsyntax-only` 只证明编得过，
// 不证明**判读对**。高亮面里有一批"编得出、门禁绿、真机才现形"的判读：
//   - 关键字表：tree-sitter-javascript 的匿名节点名是 `const`/`if`，而 `function` 是
//     **具名节点 `function`**、`class`/`new`/`this` 同理 —— 写错一个名字不会报错，
//     只会安静地少染一块；
//   - byte vs char：tree-sitter 给的是**字节**偏移，Kotlin 的 AnnotatedString 要的是
//     **UTF-16 字符**偏移。中文注释/字符串下两者不等，本机必须先把这条钉死；
//   - 截断语义：恰好装满 max_spans 的文档是**完整**收集，不该报 ERR_BUFFER_TOO_SMALL。
// 真机红测仍是最后一关，但"等上设备才发现"太贵。
//
// 用法：bash bridge/treesitter/test/cpp/run-host-tests.sh
//   $TS_SRC 必填（tree-sitter 源码目录，commit pin 见 build-treesitter.sh）；
//   $TS_GRAMMAR_SRC 必填（tree-sitter-javascript 源码目录）。均无缺省（机器路径不入脚本）。
#include "treesitter_native.h"
#include <tree_sitter/api.h>

#include <cstdio>
#include <cstring>
#include <string>
#include <vector>

extern "C" TSLanguage* tree_sitter_javascript();

static int g_failed = 0;
static int g_checks = 0;
static TsHighlightSession* g_session = nullptr;

#define CHECK(cond, ...)                                                    \
    do {                                                                    \
        ++g_checks;                                                         \
        if (!(cond)) {                                                      \
            ++g_failed;                                                     \
            std::printf("  [FAIL] %s:%d: ", __FILE__, __LINE__);            \
            std::printf(__VA_ARGS__);                                       \
            std::printf("\n");                                              \
        }                                                                   \
    } while (0)

static const int32_t MAX_SPANS = 4096;

struct Span { int32_t start, end, kind; };

// 跑一次高亮，返回状态码；spans 落进 out。
static int32_t run(TsHighlightSession* session, const std::string& src, std::vector<Span>* out) {
    std::vector<int32_t> buf(static_cast<size_t>(MAX_SPANS) * 3, -1);
    int32_t count = -1;
    int32_t status = ts_highlight(
        session, src.data(), static_cast<int32_t>(src.size()), buf.data(), MAX_SPANS, &count);
    out->clear();
    if (count > 0) {
        for (int32_t i = 0; i < count; ++i) {
            out->push_back({buf[i * 3], buf[i * 3 + 1], buf[i * 3 + 2]});
        }
    }
    return status;
}

// 找出覆盖 [start,end) 且 kind 相符的 span 是否存在。
static bool has_span(const std::vector<Span>& v, int32_t start, int32_t end, int32_t kind) {
    for (const auto& s : v) {
        if (s.start == start && s.end == end && s.kind == kind) return true;
    }
    return false;
}

// 期望区间一律由 find 算出，不手数字节：手数在这个文件里已错过六处（`if` 在 13 不是 15、
// "// " 是 3 字节不是 2、exact-fit 漏数 `=`）。find 的是源码里的**字面子串**，
// 所以"区间切出来恰好是这个词"由构造保证，kind 才是被断言的东西。
static bool has_word(const std::vector<Span>& v, const std::string& src,
                     const std::string& word, int32_t kind, size_t from = 0) {
    const size_t at = src.find(word, from);
    if (at == std::string::npos) return false;
    return has_span(v, static_cast<int32_t>(at), static_cast<int32_t>(at + word.size()), kind);
}

// ── 1) 关键字面：具名节点与匿名节点两类都要染上 ────────────────────────────
static void test_keywords() {
    std::printf("[run] test_keywords\n");
    const std::string src = "const x = 1;\nif (x) { return; }\n";
    std::vector<Span> spans;
    CHECK(run(g_session, src, &spans) == 0, "状态码非 0");
    CHECK(has_word(spans, src, "const", 0), "`const` 未染成 KEYWORD");
    CHECK(has_word(spans, src, "if", 0), "`if` 未染成 KEYWORD");
    CHECK(has_word(spans, src, "return", 0), "`return` 未染成 KEYWORD");
}

// 具名关键字节点：function / class / new / this / typeof 在 grammar 里是具名节点，
// 而 if/return/const 是匿名节点 —— 两张表都得进映射，漏一张就少染一类。
static void test_named_keyword_nodes() {
    std::printf("[run] test_named_keyword_nodes\n");
    const std::string src = "function f() { return new C(); }\n";
    std::vector<Span> spans;
    CHECK(run(g_session, src, &spans) == 0, "状态码非 0");
    CHECK(has_word(spans, src, "function", 0), "`function` 未染成 KEYWORD（具名节点 function）");
    CHECK(has_word(spans, src, "new", 0), "`new` 未染成 KEYWORD（具名节点 new_expression 的 new）");
}

// ── 2) 字面量面 ───────────────────────────────────────────────────────────
static void test_literals() {
    std::printf("[run] test_literals\n");
    const std::string src = "const s = \"hi\";\nconst n = 42;\n// c\n";
    std::vector<Span> spans;
    CHECK(run(g_session, src, &spans) == 0, "状态码非 0");
    CHECK(has_span(spans, 10, 14, 1), "字符串 \"hi\" 未染成 STRING（10..14）");
    CHECK(has_span(spans, 26, 28, 3), "数字 42 未染成 NUMBER（26..28）");
    CHECK(has_span(spans, 30, 34, 2), "注释 // c 未染成 COMMENT（30..34）");
}

// ── 3) 函数名：只有声明位的 identifier 才染 ───────────────────────────────
static void test_function_name() {
    std::printf("[run] test_function_name\n");
    const std::string src = "function foo() { bar(); }\n";
    std::vector<Span> spans;
    CHECK(run(g_session, src, &spans) == 0, "状态码非 0");
    CHECK(has_span(spans, 9, 12, 4), "声明位 `foo` 未染成 FUNCTION_NAME（9..12）");
    // 调用位的 `bar` 是普通 identifier，不该被染成函数名（它连 span 都不该有）。
    for (const auto& s : spans) {
        CHECK(!(s.start == 20 && s.end == 23),
              "调用位 `bar` 被误染（kind=%d）—— 函数名判定只认声明位", s.kind);
    }
}

// ── 4) 字节偏移 vs UTF-16 偏移（本机先钉死这条，Kotlin 侧才敢直接拿去做索引）──
// tree-sitter 报的是 UTF-8 **字节**偏移。非 ASCII 源码里字节数 ≠ Java String 的
// char 数（中文 3 字节 = 1 char，emoji 4 字节 = 2 char）。本门禁只断言"确实按字节
// 报"，把换算责任显式留在 Kotlin 门面（`:platform:editor`），不让它成为隐性假设。
static void test_byte_offsets_not_chars() {
    std::printf("[run] test_byte_offsets_not_chars\n");
    // "// 中文" = 3（斜杠斜杠空格）+ 3 + 3 = 9 字节，但只有 5 个 Java char。
    // 换行在字节 9，`const` 因此在字节 10..15；若实现误用字符偏移会报 6..11。
    const std::string src = "// \xE4\xB8\xAD\xE6\x96\x87\nconst a = 1;\n";
    std::vector<Span> spans;
    CHECK(run(g_session, src, &spans) == 0, "状态码非 0");
    CHECK(has_span(spans, 0, 9, 2), "含中文的注释区间不是字节偏移（期望 0..9 字节）");
    CHECK(has_span(spans, 10, 15, 0), "`const` 未按字节偏移报（期望 10..15）");
    CHECK(src.size() == 9 + 1 + 12 + 1, "夹具长度自检（9 注释 + 换行 + 12 + 换行）");
}

// ── 5) 截断语义 ───────────────────────────────────────────────────────────
static void test_truncation() {
    std::printf("[run] test_truncation\n");
    const std::string src = "const a = 1;\nconst b = 2;\nconst c = 3;\n";
    // 每行 const(KEYWORD) + =(OPERATOR) + 数字(NUMBER) = 3 条，三行共 9 条。
    // 容量恰好 9 → 完整收集，不该报错；这是"装满不等于截断"的边界。
    std::vector<int32_t> buf(9 * 3, -1);
    int32_t count = -1;
    int32_t st = ts_highlight(
        g_session, src.data(), static_cast<int32_t>(src.size()), buf.data(), 9, &count);
    CHECK(st == 0, "恰好装满时误报错（status=%d）—— 完整收集不该判截断", st);
    CHECK(count == 9, "count=%d，期望 9", count);
    // 少一格就必须报 -3：确认上面那个 0 不是"容量检查根本没生效"的假绿。
    std::vector<int32_t> short_buf(8 * 3, -1);
    count = -1;
    st = ts_highlight(
        g_session, src.data(), static_cast<int32_t>(src.size()), short_buf.data(), 8, &count);
    CHECK(st == -3, "少一格容量未报 -3（status=%d）", st);
    CHECK(count == 8, "少一格时 count=%d，期望 8", count);

    // 容量不够 → 必须报 -3，且 count 是**已写入**的条数（调用方可用部分结果）。
    std::vector<int32_t> small(2 * 3, -1);
    count = -1;
    st = ts_highlight(
        g_session, src.data(), static_cast<int32_t>(src.size()), small.data(), 2, &count);
    CHECK(st == -3, "容量不足未报 ERR_BUFFER_TOO_SMALL（status=%d）", st);
    CHECK(count == 2, "截断时 count=%d，期望 2（已写入条数）", count);
}

// ── 6) 未初始化与增量解析 ─────────────────────────────────────────────────
static void test_parse_error_and_incremental() {
    std::printf("[run] test_parse_error_and_incremental\n");
    // 语法错误不是失败：tree-sitter 带 ERROR 节点照样出树，高亮应照常返回。
    std::vector<Span> spans;
    const std::string bad = "function { { {";
    int32_t st = run(g_session, bad, &spans);
    CHECK(st == 0, "语法错误输入被当成解析失败（status=%d）—— tree-sitter 有错误恢复", st);

    // 增量解析：同一 parser 连续两次调用（第二次带 prev_tree）结果必须一致。
    const std::string a = "const a = 1;\n";
    const std::string b = "const a = 2;\n";
    std::vector<Span> s1, s2;
    CHECK(run(g_session, a, &s1) == 0, "第一次解析失败");
    CHECK(run(g_session, b, &s2) == 0, "第二次（增量）解析失败");
    CHECK(has_span(s2, 10, 11, 3), "增量解析后数字区间不对（期望 10..11）");
    CHECK(has_span(s2, 0, 5, 0), "增量解析后 `const` 丢了");
}

int main() {
    g_session = ts_create_session();
    if (g_session == nullptr) {
        std::printf("[FAIL] 无法创建 tree-sitter 会话（grammar 初始化失败）\n");
        return 1;
    }

    test_keywords();
    test_named_keyword_nodes();
    test_literals();
    test_function_name();
    test_byte_offsets_not_chars();
    test_truncation();
    test_parse_error_and_incremental();

    ts_destroy_session(g_session);
    g_session = nullptr;

    if (g_failed == 0) {
        std::printf("[OK] %d 条断言全过\n", g_checks);
        return 0;
    }
    std::printf("[FAIL] %d/%d 条断言未过\n", g_failed, g_checks);
    return 1;
}
