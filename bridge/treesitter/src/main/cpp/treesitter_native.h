#ifndef AUTOSCRIPT_TREESITTER_NATIVE_H
#define AUTOSCRIPT_TREESITTER_NATIVE_H

#include <cstdint>

// 零 JNI 依赖的计算核；每个文档独占会话，由调用方串行调用。
struct TsHighlightSession;

// 加载 JavaScript grammar；分配或语言初始化失败时返回 nullptr。
TsHighlightSession* ts_create_session();

// 释放 parser、语法树和源码快照；nullptr 合法。
void ts_destroy_session(TsHighlightSession* session);

/**
 * 增量解析 JavaScript，输出按源码顺序排列、互不重叠的 [start, end, kind] 三元组。
 * start/end 是 UTF-8 字节偏移，end 不含；UTF-16 换算由桥面负责。
 * kind 与 domain SyntaxKind.wireCode 对齐：0 KEYWORD、1 STRING、2 COMMENT、
 * 3 NUMBER、4 FUNCTION_NAME、5 OPERATOR、6 TYPE。
 *
 * out_spans 至少容纳 max_spans * 3 个 int32_t；out_count 必填，入口先置 0，
 * 返回时为实际写入条数（包括容量不足时的部分结果），始终不超过 max_spans。
 * source_len/max_spans 不可为负；source_len 为 0 时 source_utf8 可空，
 * max_spans 为 0 时 out_spans 可空。空源码与零容量组合合法。
 *
 * 返回 0 成功、-1 空会话、-2 解析器失败、-3 容量不足、-4 参数非法。
 * 语法错误由 tree-sitter 恢复，不等于解析器失败；容量不足后可扩大缓冲重试。
 */
int32_t ts_highlight(
    TsHighlightSession* session,
    const char* source_utf8,
    int32_t source_len,
    int32_t* out_spans,
    int32_t max_spans,
    int32_t* out_count
);

#endif  // AUTOSCRIPT_TREESITTER_NATIVE_H
