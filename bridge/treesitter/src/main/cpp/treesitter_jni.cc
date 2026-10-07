#include "treesitter_native.h"
#include <jni.h>

#include <cstdint>
#include <limits>
#include <memory>
#include <mutex>
#include <new>
#include <unordered_map>
#include <vector>

// JNI owns opaque, never-reused IDs, not addresses. The same lock covers lookup,
// every core call, and removal, so highlight cannot race a close into a UAF.
namespace {
constexpr jint kNotInitialized = -1;
constexpr jint kParseFailed = -2;
constexpr jint kInvalidArguments = -4;

static_assert(std::numeric_limits<jint>::min() == std::numeric_limits<int32_t>::min()
                  && std::numeric_limits<jint>::max() == std::numeric_limits<int32_t>::max(),
              "JNI jint must represent exactly the int32_t range");

std::mutex session_mutex;
std::unordered_map<jlong, TsHighlightSession*> sessions;
jlong next_handle = 1;  // 0 means exhausted; IDs are never recycled, even after close.

// Never overwrite/clear an exception already raised by the VM.
void throw_java(JNIEnv* env, const char* type, const char* message) noexcept {
    if (env->ExceptionCheck()) return;
    jclass exception_class = env->FindClass(type);
    if (exception_class != nullptr) {
        env->ThrowNew(exception_class, message);
        env->DeleteLocalRef(exception_class);
    }
}

struct SessionDeleter {
    void operator()(TsHighlightSession* session) const noexcept {
        // Roll back a session if registry allocation fails. Even a faulty core
        // destructor must not terminate stack unwinding at the JNI boundary.
        try {
            ts_destroy_session(session);
        } catch (...) {
        }
    }
};
}  // namespace

extern "C" {

JNIEXPORT jlong JNICALL
Java_com_autoscript_platform_editor_TreeSitterNative_createSession(JNIEnv* env, jobject) {
    if (env->ExceptionCheck()) return 0;
    try {
        std::lock_guard<std::mutex> lock(session_mutex);
        if (next_handle == 0) return 0;
        std::unique_ptr<TsHighlightSession, SessionDeleter> session(ts_create_session());
        if (!session) return 0;
        const jlong handle = next_handle;
        sessions.emplace(handle, session.get());
        session.release();
        // Do not overflow a signed jlong or reuse a stale ID after wraparound.
        next_handle = handle == std::numeric_limits<jlong>::max() ? 0 : handle + 1;
        return handle;
    } catch (const std::bad_alloc&) {
        throw_java(env, "java/lang/OutOfMemoryError", "Unable to create tree-sitter session");
    } catch (...) {
        throw_java(env, "java/lang/IllegalStateException", "Unable to create tree-sitter session");
    }
    return 0;
}

JNIEXPORT jint JNICALL
Java_com_autoscript_platform_editor_TreeSitterNative_highlight(
    JNIEnv* env, jobject, jlong handle, jbyteArray source, jintArray out_spans) {
    if (env->ExceptionCheck()) return kParseFailed;
    if (source == nullptr || out_spans == nullptr) return kInvalidArguments;
    try {
        const jsize source_length = env->GetArrayLength(source);
        if (env->ExceptionCheck()) return kParseFailed;
        const jsize output_length = env->GetArrayLength(out_spans);
        if (env->ExceptionCheck()) return kParseFailed;
        if (source_length < 0 || output_length < 0
                || static_cast<int64_t>(source_length) > std::numeric_limits<int32_t>::max()
                || static_cast<int64_t>(output_length) > std::numeric_limits<int32_t>::max()) {
            return kInvalidArguments;
        }

        std::lock_guard<std::mutex> lock(session_mutex);
        const auto found = sessions.find(handle);
        if (found == sessions.end()) return kNotInitialized;

        // ByteArray is standard UTF-8, including embedded NUL and four-byte
        // emoji. GetStringUTFChars would silently replace it with Modified UTF-8.
        // Region copies leave no pinned JNI storage for the core to retain.
        std::vector<jbyte> source_bytes(static_cast<size_t>(source_length) + 1, 0);
        if (source_length > 0) {
            env->GetByteArrayRegion(source, 0, source_length, source_bytes.data());
            if (env->ExceptionCheck()) return kParseFailed;
        }

        // A trailing 1-2 ints cannot store a span and remain untouched. Capacity
        // zero is valid: the core decides whether the document needs any spans.
        const int32_t capacity = static_cast<int32_t>(output_length / 3);
        std::vector<int32_t> native_spans(static_cast<size_t>(capacity) * 3);
        int32_t empty_output = 0;
        int32_t count = 0;
        const int32_t status = ts_highlight(
            found->second,
            reinterpret_cast<const char*>(source_bytes.data()),
            static_cast<int32_t>(source_length),
            native_spans.empty() ? &empty_output : native_spans.data(),
            capacity, &count);
        if (status != 0) return static_cast<jint>(status);
        if (count < 0 || count > capacity) return kParseFailed;

        if (count > 0) {
            const size_t used = static_cast<size_t>(count) * 3;
            // Convert values rather than alias int32_t* as jint*: equal width
            // alone does not establish compatible C++ types on every JNI ABI.
            const std::vector<jint> java_spans(native_spans.begin(), native_spans.begin() + used);
            env->SetIntArrayRegion(out_spans, 0, static_cast<jsize>(used), java_spans.data());
            if (env->ExceptionCheck()) return kParseFailed;
        }
        // Output is committed only on success; partial buffer errors never leak
        // half a result into Java. All storage above expires before JNI returns.
        return static_cast<jint>(count);
    } catch (const std::bad_alloc&) {
        throw_java(env, "java/lang/OutOfMemoryError", "Unable to allocate tree-sitter buffers");
    } catch (...) {
        throw_java(env, "java/lang/IllegalStateException", "Tree-sitter highlight failed");
    }
    return kParseFailed;
}

JNIEXPORT void JNICALL
Java_com_autoscript_platform_editor_TreeSitterNative_destroySession(
    JNIEnv* env, jobject, jlong handle) {
    if (env->ExceptionCheck()) return;
    try {
        std::lock_guard<std::mutex> lock(session_mutex);
        const auto found = sessions.find(handle);
        if (found == sessions.end()) return;  // Invalid and repeated closes are no-ops.
        TsHighlightSession* session = found->second;
        sessions.erase(found);  // Invalidate before destruction, including error paths.
        ts_destroy_session(session);
    } catch (const std::bad_alloc&) {
        throw_java(env, "java/lang/OutOfMemoryError", "Unable to close tree-sitter session");
    } catch (...) {
        throw_java(env, "java/lang/IllegalStateException", "Unable to close tree-sitter session");
    }
}

}  // extern "C"
