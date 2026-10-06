// C++ 单测：纯计算核，零 JNI 依赖，x86_64 本机可跑
//
// 编译命令（需要先克隆 tree-sitter 源码）:
//   g++ -std=c++17 -I../../build/tree-sitter-src/lib/include \
//       treesitter_native_test.cpp \
//       ../../build/tree-sitter-src/lib/src/lib.c \
//       ../../build/tree-sitter-javascript-src/src/parser.c \
//       ../../build/tree-sitter-javascript-src/src/scanner.c \
//       ../main/cpp/treesitter_native.cpp \
//       -o test && ./test

#include "../main/cpp/treesitter_native.h"
#include <cassert>
#include <cstring>
#include <cstdio>

void test_parse_simple_function() {
    printf("test_parse_simple_function ... ");
    
    ts_init_parser();
    
    const char* source = "function foo() { return 42; }";
    int32_t spans[1024 * 3];
    int32_t count = 0;
    
    int32_t status = ts_parse_and_highlight(
        source, strlen(source), spans, 1024, &count
    );
    
    assert(status == 0);  // OK
    assert(count > 0);    // 至少有几个 span
    
    // 打印结果（调试用）
    printf("OK (%d spans)\n", count);
    for (int i = 0; i < count && i < 5; i++) {
        printf("  [%d..%d] kind=%d\n", 
               spans[i*3], spans[i*3+1], spans[i*3+2]);
    }
    
    ts_cleanup_parser();
}

void test_parse_with_comment() {
    printf("test_parse_with_comment ... ");
    
    ts_init_parser();
    
    const char* source = 
        "// 这是注释\n"
        "const x = 123;";
    int32_t spans[1024 * 3];
    int32_t count = 0;
    
    int32_t status = ts_parse_and_highlight(
        source, strlen(source), spans, 1024, &count
    );
    
    assert(status == 0);
    assert(count >= 2);  // 至少有 comment + const
    
    printf("OK (%d spans)\n", count);
    
    ts_cleanup_parser();
}

void test_parse_error_returns_negative() {
    printf("test_parse_error (uninitialized) ... ");
    
    // 不调 ts_init_parser()
    const char* source = "function test() {}";
    int32_t spans[1024 * 3];
    int32_t count = 0;
    
    int32_t status = ts_parse_and_highlight(
        source, strlen(source), spans, 1024, &count
    );
    
    assert(status == -1);  // ERR_NOT_INITIALIZED
    
    printf("OK (status=%d)\n", status);
}

void test_incremental_parse() {
    printf("test_incremental_parse ... ");
    
    ts_init_parser();
    
    // 第一次解析
    const char* source1 = "const a = 1;";
    int32_t spans[1024 * 3];
    int32_t count1 = 0;
    ts_parse_and_highlight(source1, strlen(source1), spans, 1024, &count1);
    
    // 第二次解析（应该复用 prev_tree）
    const char* source2 = "const a = 2;";  // 只改了一个字符
    int32_t count2 = 0;
    int32_t status = ts_parse_and_highlight(source2, strlen(source2), spans, 1024, &count2);
    
    assert(status == 0);
    assert(count2 > 0);
    
    printf("OK (parse1=%d spans, parse2=%d spans)\n", count1, count2);
    
    ts_cleanup_parser();
}

int main() {
    printf("==> 运行 treesitter_native 单测\n\n");
    
    test_parse_simple_function();
    test_parse_with_comment();
    test_parse_error_returns_negative();
    test_incremental_parse();
    
    printf("\n✓ 全部通过\n");
    return 0;
}
