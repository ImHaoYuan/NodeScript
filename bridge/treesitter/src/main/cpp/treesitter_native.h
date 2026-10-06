#ifndef AUTOSCRIPT_TREESITTER_NATIVE_H
#define AUTOSCRIPT_TREESITTER_NATIVE_H

#include <cstdint>

// 计算核 API（零 JNI 依赖，纯 C）

/**
 * 初始化 parser（加载 JS grammar）。
 * 必须在第一次调用 ts_parse_and_highlight 之前调用。
 */
void ts_init_parser();

/**
 * 解析 JavaScript 源码并提取高亮区间。
 * 
 * @param source_utf8  UTF-8 编码的源码
 * @param source_len   源码字节数
 * @param out_spans    输出数组 [start, end, kind, start, end, kind, ...]
 * @param max_spans    out_spans 数组最多容纳几个 span（每个 span 占 3 个 int32）
 * @param out_count    实际提取的 span 数（写入 out_spans 的 span 数）
 * 
 * @return 0=OK, 负数=错误码（-1=未初始化, -2=解析失败, -3=buffer 太小）
 */
int32_t ts_parse_and_highlight(
    const char* source_utf8,
    int32_t source_len,
    int32_t* out_spans,
    int32_t max_spans,
    int32_t* out_count
);

/**
 * 释放 parser 资源。
 */
void ts_cleanup_parser();

#endif  // AUTOSCRIPT_TREESITTER_NATIVE_H
