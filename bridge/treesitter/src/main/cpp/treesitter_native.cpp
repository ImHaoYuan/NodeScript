#include "treesitter_native.h"
#include <tree_sitter/api.h>

#include <algorithm>
#include <cstring>
#include <limits>
#include <new>
#include <stdexcept>
#include <string>
#include <vector>

extern "C" const TSLanguage* tree_sitter_javascript();

struct TsHighlightSession {
    TSParser* parser = nullptr;
    TSTree* tree = nullptr;
    std::string source;
};

namespace {

enum SyntaxKind {
    KEYWORD = 0,
    STRING = 1,
    COMMENT = 2,
    NUMBER = 3,
    FUNCTION_NAME = 4,
    OPERATOR = 5,
    TYPE = 6,
};

bool is(const char* value, const char* expected) {
    return value != nullptr && std::strcmp(value, expected) == 0;
}

template <size_t N>
bool is_one_of(const char* value, const char* const (&names)[N]) {
    for (const char* name : names) {
        if (is(value, name)) return true;
    }
    return false;
}

bool is_continuation(char byte) {
    return (static_cast<unsigned char>(byte) & 0xc0) == 0x80;
}

TSPoint advance_point(const std::string& source, size_t start, size_t end, TSPoint point) {
    for (size_t i = start; i < end; ++i) {
        if (source[i] == '\n') {
            ++point.row;
            point.column = 0;
        } else {
            ++point.column;  // tree-sitter 的列是字节数，不是 UTF-16 或码点数。
        }
    }
    return point;
}

// 任意一批修改都可表示成相同前缀/后缀之间的一次替换；端点扩到完整 UTF-8 字符。
TSInputEdit replacement_edit(const std::string& before, const std::string& after) {
    size_t start = 0;
    const size_t shared_length = std::min(before.size(), after.size());
    while (start < shared_length && before[start] == after[start]) ++start;
    while (start > 0 &&
           ((start < before.size() && is_continuation(before[start])) ||
            (start < after.size() && is_continuation(after[start])))) {
        --start;
    }

    size_t old_end = before.size();
    size_t new_end = after.size();
    while (old_end > start && new_end > start && before[old_end - 1] == after[new_end - 1]) {
        --old_end;
        --new_end;
    }
    while (old_end < before.size() && new_end < after.size() &&
           (is_continuation(before[old_end]) || is_continuation(after[new_end]))) {
        ++old_end;
        ++new_end;
    }

    const TSPoint start_point = advance_point(before, 0, start, {0, 0});
    return {
        static_cast<uint32_t>(start),
        static_cast<uint32_t>(old_end),
        static_cast<uint32_t>(new_end),
        start_point,
        advance_point(before, start, old_end, start_point),
        advance_point(after, start, new_end, start_point),
    };
}

struct Parent {
    TSNode node;
    const char* type;
    const char* field;
    uint32_t template_next_byte;
};

int value_kind(TSNode parent, const char* field, uint32_t field_length) {
    const TSNode value = ts_node_child_by_field_name(parent, field, field_length);
    if (ts_node_is_null(value)) return -1;
    const char* type = ts_node_type(value);
    static const char* const functions[] = {"function", "generator_function", "arrow_function"};
    if (is_one_of(type, functions)) return FUNCTION_NAME;
    return is(type, "class") ? TYPE : -1;
}

int identifier_kind(const char* field, const std::vector<Parent>& parents) {
    size_t depth = parents.size();
    // obj.method() 只染 property；(fn)() 与 (obj.method)() 同样可识别。
    if (depth > 0 && is(parents[depth - 1].type, "member_expression") && is(field, "property")) {
        field = parents[--depth].field;
    }
    while (depth > 0 && is(parents[depth - 1].type, "parenthesized_expression")) {
        field = parents[--depth].field;
    }
    if (depth == 0) return -1;
    const Parent& parent = parents[depth - 1];
    static const char* const definitions[] = {
        "function", "function_declaration", "generator_function",
        "generator_function_declaration", "method_definition",
    };
    if (is(field, "name") && is_one_of(parent.type, definitions)) return FUNCTION_NAME;
    if (is(field, "name") && (is(parent.type, "class") || is(parent.type, "class_declaration"))) {
        return TYPE;
    }
    if (is(parent.type, "call_expression") && is(field, "function")) return FUNCTION_NAME;
    if ((is(parent.type, "new_expression") && is(field, "constructor")) ||
        is(parent.type, "class_heritage")) return TYPE;
    if (is(parent.type, "variable_declarator") && is(field, "name")) {
        return value_kind(parent.node, "value", 5);
    }
    if (is(parent.type, "assignment_expression") && is(field, "left")) {
        return value_kind(parent.node, "right", 5);
    }
    if (is(parent.type, "pair") && is(field, "key")) {
        return value_kind(parent.node, "value", 5);
    }
    return -1;
}

int node_kind(TSNode node, const char* type, const char* field, const std::vector<Parent>& parents) {
    // function/class 既是具名容器又是匿名 token；只把 token 当关键字。
    if (!ts_node_is_named(node)) {
        static const char* const keywords[] = {
            "as", "async", "await", "break", "case", "catch", "class", "const", "continue",
            "debugger", "default", "delete", "do", "else", "export", "extends", "finally",
            "for", "from", "function", "get", "if", "import", "in", "instanceof", "let",
            "new", "of", "return", "set", "static", "static get", "switch", "target", "throw",
            "try", "typeof", "var", "void", "while", "with", "yield",
        };
        static const char* const operators[] = {
            "-", "--", "-=", "+", "++", "+=", "*", "*=", "**", "**=", "/", "/=", "%", "%=",
            "<", "<=", "<<", "<<=", "=", "==", "===", "!", "!=", "!==", "=>", ">", ">=",
            ">>", ">>=", ">>>", ">>>=", "~", "^", "&", "|", "^=", "&=", "|=", "&&", "||",
            "??", "&&=", "||=", "?" "?=", "...", "?", "${",
        };
        if (is_one_of(type, keywords)) return KEYWORD;
        if (is_one_of(type, operators)) return OPERATOR;
        if (!parents.empty() &&
            ((is(type, ":") && is(parents.back().type, "ternary_expression")) ||
             (is(type, "}") && is(parents.back().type, "template_substitution")))) return OPERATOR;
        return -1;
    }

    if (is(type, "string") || is(type, "regex")) return STRING;
    if (is(type, "comment") || is(type, "hash_bang_line")) return COMMENT;
    if (is(type, "number")) return NUMBER;
    if (is(type, "optional_chain")) return OPERATOR;
    static const char* const literal_keywords[] = {"this", "super", "true", "false", "null", "undefined", "import"};
    if (is_one_of(type, literal_keywords)) return KEYWORD;
    if (is(type, "identifier") || is(type, "property_identifier") || is(type, "private_property_identifier")) {
        return identifier_kind(field, parents);
    }
    return -1;
}

bool append_span(uint32_t start, uint32_t end, int kind, int32_t* output, int32_t capacity, int32_t* count) {
    if (start >= end) return true;  // 错误恢复插入的 missing token 不产生空区间。
    if (*count == capacity) return false;  // 恰好填满不算截断；确实丢弃 span 才报错。
    const size_t index = static_cast<size_t>(*count) * 3;
    output[index] = static_cast<int32_t>(start);
    output[index + 1] = static_cast<int32_t>(end);
    output[index + 2] = kind;
    ++*count;
    return true;
}

struct Cursor {
    TSTreeCursor value;
    ~Cursor() { ts_tree_cursor_delete(&value); }
};

int32_t collect_spans(TSTree* tree, int32_t* output, int32_t capacity, int32_t* count) {
    Cursor cursor{ts_tree_cursor_new(ts_tree_root_node(tree))};
    std::vector<Parent> parents;
    // 游标 + 堆上祖先栈：深层表达式不消耗 C++ 调用栈，也不反复从根找 parent。
    for (;;) {
        const TSNode node = ts_tree_cursor_current_node(&cursor.value);
        const char* type = ts_node_type(node);
        const char* field = ts_tree_cursor_current_field_name(&cursor.value);
        const bool in_template = !parents.empty() && is(parents.back().type, "template_string");
        const bool substitution = ts_node_is_named(node) && is(type, "template_substitution");
        // 模板文本由相邻插值之间的源码间隙一次输出，含反引号、转义和隐藏文本节点。
        if (!ts_node_is_missing(node) && (!in_template || substitution)) {
            if (in_template) {
                Parent& parent = parents.back();
                if (!append_span(parent.template_next_byte, ts_node_start_byte(node), STRING, output, capacity, count)) {
                    return -3;
                }
                parent.template_next_byte = ts_node_end_byte(node);
            }
            const int kind = node_kind(node, type, field, parents);
            if (kind >= 0) {
                if (!append_span(ts_node_start_byte(node), ts_node_end_byte(node), kind, output, capacity, count)) return -3;
                // 已覆盖的 string/comment/regex 等不再遍历子节点，避免重叠区间。
            } else if (ts_tree_cursor_goto_first_child(&cursor.value)) {
                parents.push_back({node, type, field, ts_node_start_byte(node)});
                continue;
            } else if (is(type, "template_string")) {
                if (!append_span(ts_node_start_byte(node), ts_node_end_byte(node), STRING, output, capacity, count)) return -3;
            }
        }

        while (!ts_tree_cursor_goto_next_sibling(&cursor.value)) {
            if (!ts_tree_cursor_goto_parent(&cursor.value)) return 0;
            const Parent parent = parents.back();
            parents.pop_back();
            if (is(parent.type, "template_string") &&
                !append_span(parent.template_next_byte, ts_node_end_byte(parent.node), STRING, output, capacity, count)) {
                return -3;
            }
        }
    }
}

}  // namespace

TsHighlightSession* ts_create_session() {
    auto* session = new (std::nothrow) TsHighlightSession;
    if (!session) return nullptr;
    session->parser = ts_parser_new();
    if (!session->parser || !ts_parser_set_language(session->parser, tree_sitter_javascript())) {
        ts_destroy_session(session);
        return nullptr;
    }
    return session;
}

void ts_destroy_session(TsHighlightSession* session) {
    if (!session) return;
    ts_tree_delete(session->tree);
    ts_parser_delete(session->parser);
    delete session;
}

int32_t ts_highlight(
    TsHighlightSession* session,
    const char* source_utf8,
    int32_t source_len,
    int32_t* out_spans,
    int32_t max_spans,
    int32_t* out_count
) {
    if (!out_count) return -4;
    *out_count = 0;
    if (!session) return -1;
    if (source_len < 0 || max_spans < 0 || (!source_utf8 && source_len != 0) ||
        (!out_spans && max_spans != 0) ||
        static_cast<uint64_t>(max_spans) > std::numeric_limits<size_t>::max() / sizeof(int32_t) / 3) return -4;
    if (!session->parser) return -2;
    const char* source = source_utf8 ? source_utf8 : "";
    const size_t length = static_cast<size_t>(source_len);
    try {
        if (!session->tree || session->source.size() != length ||
            std::memcmp(session->source.data(), source, length) != 0) {
            // 先分配快照再编辑旧树，避免分配失败留下与快照不一致的树。
            std::string next_source(source, length);
            if (session->tree) {
                const TSInputEdit edit = replacement_edit(session->source, next_source);
                ts_tree_edit(session->tree, &edit);
            }
            TSTree* next_tree = ts_parser_parse_string(session->parser, session->tree, source, static_cast<uint32_t>(length));
            ts_tree_delete(session->tree);
            session->tree = next_tree;
            if (!next_tree) {
                // 旧树已编辑，失败后清空缓存并重置 parser，下一次从头解析。
                session->source.clear();
                ts_parser_reset(session->parser);
                return -2;
            }
            session->source.swap(next_source);
        }
        return collect_spans(session->tree, out_spans, max_spans, out_count);
    } catch (const std::bad_alloc&) {
        return -2;
    } catch (const std::length_error&) {
        return -2;
    }
}
