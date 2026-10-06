#include "treesitter_native.h"
#include <jni.h>
#include <cstring>

// JNI 装载面：唯一包含 jni.h 的文件，只做类型转换

extern "C" {

JNIEXPORT void JNICALL
Java_com_autoscript_platform_editor_TreeSitterNative_initParser(JNIEnv*, jclass) {
    ts_init_parser();
}

JNIEXPORT jint JNICALL
Java_com_autoscript_platform_editor_TreeSitterNative_parseAndHighlight(
    JNIEnv* env,
    jclass,
    jstring source,
    jintArray outSpans
) {
    // 取 UTF-8 字符串
    const char* utf8 = env->GetStringUTFChars(source, nullptr);
    if (!utf8) return -2;  // OOM
    
    jsize source_len = env->GetStringUTFLength(source);
    
    // 取输出数组
    jint* spans = env->GetIntArrayElements(outSpans, nullptr);
    if (!spans) {
        env->ReleaseStringUTFChars(source, utf8);
        return -2;
    }
    
    jsize array_len = env->GetArrayLength(outSpans);
    int32_t max_spans = array_len / 3;  // 每个 span 占 3 个 int
    
    int32_t count = 0;
    int32_t status = ts_parse_and_highlight(
        utf8,
        static_cast<int32_t>(source_len),
        spans,
        max_spans,
        &count
    );
    
    // 释放资源
    env->ReleaseIntArrayElements(outSpans, spans, 0);  // 0 = 写回 Java 数组
    env->ReleaseStringUTFChars(source, utf8);
    
    // 返回值：成功时返回 span 数（>= 0），失败时返回负错误码
    return (status == 0) ? count : status;
}

JNIEXPORT void JNICALL
Java_com_autoscript_platform_editor_TreeSitterNative_cleanupParser(JNIEnv*, jclass) {
    ts_cleanup_parser();
}

}  // extern "C"
