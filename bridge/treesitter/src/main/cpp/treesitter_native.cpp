#include "treesitter_native.h"
#include <tree_sitter/api.h>
#include <cstring>
#include <vector>

// JS grammar 外部符号（由 libtree-sitter-javascript.so 导出）
extern "C" TSLanguage* tree_sitter_javascript();

// 全局状态（单例）
static TSParser* parser = nullptr;
static TSTree* prev_tree = nullptr;

// SyntaxKind 枚举（与 Kotlin 侧对齐）
enum SyntaxKind {
    KEYWORD = 0,
    STRING = 1,
    COMMENT = 2,
    NUMBER = 3,
    FUNCTION_NAME = 4,
    OPERATOR = 5,
    TYPE = 6,
};

void ts_init_parser() {
    if (parser) return;  // 已初始化
    
    parser = ts_parser_new();
    ts_parser_set_language(parser, tree_sitter_javascript());
}

void ts_cleanup_parser() {
    if (prev_tree) {
        ts_tree_delete(prev_tree);
        prev_tree = nullptr;
    }
    if (parser) {
        ts_parser_delete(parser);
        parser = nullptr;
    }
}

// 遍历 AST 并收集需要高亮的节点
static void traverse_and_collect(
    TSNode node,
    int32_t* out_spans,
    int32_t max_spans,
    int32_t* span_idx
) {
    if (*span_idx >= max_spans) return;  // buffer 满了
    
    const char* type = ts_node_type(node);
    int32_t kind = -1;
    
    // 根据节点类型映射到 SyntaxKind
    // 参考 tree-sitter-javascript 的 node-types.json
    if (strcmp(type, "function") == 0 || 
        strcmp(type, "const") == 0 || 
        strcmp(type, "let") == 0 || 
        strcmp(type, "var") == 0 ||
        strcmp(type, "if") == 0 || 
        strcmp(type, "else") == 0 ||
        strcmp(type, "return") == 0 ||
        strcmp(type, "for") == 0 ||
        strcmp(type, "while") == 0 ||
        strcmp(type, "break") == 0 ||
        strcmp(type, "continue") == 0 ||
        strcmp(type, "class") == 0 ||
        strcmp(type, "extends") == 0 ||
        strcmp(type, "new") == 0 ||
        strcmp(type, "this") == 0 ||
        strcmp(type, "async") == 0 ||
        strcmp(type, "await") == 0 ||
        strcmp(type, "try") == 0 ||
        strcmp(type, "catch") == 0 ||
        strcmp(type, "throw") == 0 ||
        strcmp(type, "import") == 0 ||
        strcmp(type, "export") == 0 ||
        strcmp(type, "default") == 0 ||
        strcmp(type, "case") == 0 ||
        strcmp(type, "switch") == 0) {
        kind = KEYWORD;
    } else if (strcmp(type, "string") == 0 || 
               strcmp(type, "template_string") == 0) {
        kind = STRING;
    } else if (strcmp(type, "comment") == 0) {
        kind = COMMENT;
    } else if (strcmp(type, "number") == 0) {
        kind = NUMBER;
    } else if (strcmp(type, "identifier") == 0) {
        // 函数名：父节点是 function_declaration，且当前节点是 name 字段
        TSNode parent = ts_node_parent(node);
        if (!ts_node_is_null(parent)) {
            const char* parent_type = ts_node_type(parent);
            if (strcmp(parent_type, "function_declaration") == 0 ||
                strcmp(parent_type, "function") == 0 ||
                strcmp(parent_type, "arrow_function") == 0) {
                // 简化判断：函数声明里的第一个 identifier
                if (ts_node_child_count(parent) > 0) {
                    TSNode first_id = ts_node_child_by_field_name(parent, "name", 4);
                    if (!ts_node_is_null(first_id) && 
                        ts_node_start_byte(first_id) == ts_node_start_byte(node)) {
                        kind = FUNCTION_NAME;
                    }
                }
            }
        }
    }
    
    // 如果当前节点需要高亮，记录区间
    if (kind >= 0) {
        uint32_t start = ts_node_start_byte(node);
        uint32_t end = ts_node_end_byte(node);
        
        out_spans[*span_idx * 3] = static_cast<int32_t>(start);
        out_spans[*span_idx * 3 + 1] = static_cast<int32_t>(end);
        out_spans[*span_idx * 3 + 2] = kind;
        (*span_idx)++;
    }
    
    // 递归遍历子节点
    uint32_t child_count = ts_node_child_count(node);
    for (uint32_t i = 0; i < child_count && *span_idx < max_spans; i++) {
        TSNode child = ts_node_child(node, i);
        traverse_and_collect(child, out_spans, max_spans, span_idx);
    }
}

int32_t ts_parse_and_highlight(
    const char* source_utf8,
    int32_t source_len,
    int32_t* out_spans,
    int32_t max_spans,
    int32_t* out_count
) {
    if (!parser) return -1;  // ERR_NOT_INITIALIZED
    
    // 增量解析：复用 prev_tree
    TSTree* tree = ts_parser_parse_string(
        parser,
        prev_tree,
        source_utf8,
        static_cast<uint32_t>(source_len)
    );
    
    if (!tree) return -2;  // ERR_PARSE_FAILED
    
    // 删除旧树
    if (prev_tree) {
        ts_tree_delete(prev_tree);
    }
    prev_tree = tree;
    
    // 遍历 AST
    TSNode root = ts_tree_root_node(tree);
    int32_t span_idx = 0;
    traverse_and_collect(root, out_spans, max_spans, &span_idx);
    
    *out_count = span_idx;
    
    if (span_idx >= max_spans) {
        return -3;  // ERR_BUFFER_TOO_SMALL（可能还有更多 span 没记录）
    }
    
    return 0;  // OK
}
