package com.autoscript.platform.editor;

import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Self-contained host JNI smoke test (not an Android/runtime implementation).
 * Compile with javac -encoding UTF-8; pass the absolute host JNI .so path to main.
 * Run with -Xcheck:jni to check JNI contracts as well as the returned spans.
 */
public final class TreeSitterNative {
    public native long createSession();
    public native int highlight(long handle, byte[] source, int[] outSpans);
    public native void destroySession(long handle);

    private static final int SENTINEL = 0x5a5a5a5a;
    private static final AtomicInteger CHECKS = new AtomicInteger();

    private static void check(boolean condition, String message) {
        CHECKS.incrementAndGet();
        if (!condition) throw new AssertionError(message);
    }

    private static byte[] utf8(String source) {
        return source.getBytes(StandardCharsets.UTF_8);
    }

    private static int[] filled(int length) {
        int[] output = new int[length];
        Arrays.fill(output, SENTINEL);
        return output;
    }

    private static void unchanged(int[] output, String message) {
        for (int value : output) check(value == SENTINEL, message);
    }

    private int highlightChecked(long handle, byte[] source, int[] output) {
        byte[] original = source.clone();
        int count = highlight(handle, source, output);
        check(count >= 0 && count <= output.length / 3, "Invalid span count: " + count);
        check(Arrays.equals(source, original), "JNI mutated input bytes");
        for (int i = 0; i < count; i++) {
            int start = output[i * 3];
            int end = output[i * 3 + 1];
            int kind = output[i * 3 + 2];
            check(start >= 0 && end > start && end <= source.length, "Invalid UTF-8 byte range");
            check(kind >= 0 && kind <= 6, "Invalid syntax kind");
        }
        for (int i = count * 3; i < output.length; i++) {
            check(output[i] == SENTINEL, "JNI overwrote unused output capacity");
        }
        return count;
    }

    private static void hasSpan(int[] output, int count, int start, int end, int kind) {
        for (int i = 0; i < count; i++) {
            if (output[i * 3] == start && output[i * 3 + 1] == end && output[i * 3 + 2] == kind) {
                check(true, "span found");
                return;
            }
        }
        check(false, "Missing span [" + start + ", " + end + ", " + kind + "]");
    }

    private void unicodeAndNul(long handle) {
        String comment = "// 中文😀";
        String literal = "\"中文😀\"";
        String prefix = comment + "\nconst message = ";
        byte[] source = utf8(prefix + literal + ";\nconst n = 42;\n");
        int[] output = filled(257);
        int count = highlightChecked(handle, source, output);
        hasSpan(output, count, 0, utf8(comment).length, 2);
        int start = utf8(prefix).length;
        hasSpan(output, count, start, start + utf8(literal).length, 1);
        start = utf8(prefix + literal + ";\nconst n = ").length;
        hasSpan(output, count, start, start + 2, 3);
        check(utf8(comment).length != comment.length(), "Fixture must distinguish UTF-8 and UTF-16");

        // Raw NUL is one byte, not MUTF-8 C0 80. Require the token *after* it,
        // catching both strlen truncation and Modified UTF-8 byte-offset shifts.
        String nulPrefix = "const before = \"中😀\u0000文\";\nconst after = ";
        source = utf8(nulPrefix + "23;\n");
        int nulOffset = utf8("const before = \"中😀").length;
        check(source[nulOffset] == 0, "Fixture must contain a raw UTF-8 NUL byte");
        output = filled(257);
        count = highlightChecked(handle, source, output);
        start = utf8(nulPrefix).length;
        hasSpan(output, count, start, start + 2, 3);
    }

    private void capacityAndInvalidArguments(long handle) {
        byte[] source = utf8("const x = 7;");
        int[] full = filled(96);
        int count = highlightChecked(handle, source, full);
        check(count > 0, "Fixture must produce spans");

        int[] exact = filled(count * 3);
        check(highlightChecked(handle, source, exact) == count, "Exact capacity must succeed");
        check(Arrays.equals(exact, Arrays.copyOf(full, count * 3)), "Exact capacity changed spans");
        int[] odd = filled(count * 3 + 2);
        check(highlightChecked(handle, source, odd) == count, "Trailing capacity must be allowed");

        int[] shortOutput = filled((count - 1) * 3 + 2);
        check(highlight(handle, source, shortOutput) == -3, "One missing span must report buffer error");
        unchanged(shortOutput, "Buffer error committed a partial result");
        for (int length = 0; length < 3; length++) {
            int[] output = filled(length);
            check(highlight(handle, source, output) == -3, "Zero span capacity must report buffer error");
            unchanged(output, "Zero span capacity wrote output");
            check(highlight(handle, new byte[0], output) == 0, "Empty source needs no spans");
            unchanged(output, "Empty source wrote output");
        }

        int[] output = filled(12);
        check(highlight(handle, null, output) == -4, "Null source must report arguments error");
        unchanged(output, "Null input wrote output");
        check(highlight(handle, source, null) == -4, "Null output must report arguments error");
        check(highlight(handle, null, null) == -4, "Null arrays must report arguments error");
        for (long invalid : new long[] {0, -1, Long.MIN_VALUE, Long.MAX_VALUE}) {
            check(highlight(invalid, source, output) == -1, "Invalid handle must report uninitialized");
            unchanged(output, "Invalid handle wrote output");
            destroySession(invalid);
            destroySession(invalid);
        }
    }

    private void independentSessions(long first, long second) {
        for (int i = 0; i < 16; i++) {
            String firstPrefix = i % 2 == 0 ? "const first = " : "/* 中文😀 */ const changed = ";
            byte[] firstSource = utf8(firstPrefix + "123;\n");
            int[] firstOutput = filled(96);
            int firstCount = highlightChecked(first, firstSource, firstOutput);
            int start = utf8(firstPrefix).length;
            hasSpan(firstOutput, firstCount, start, start + 3, 3);

            String secondPrefix = i % 2 == 0 ? "const independent = " : "let second = ";
            byte[] secondSource = utf8(secondPrefix + "9876;\n");
            int[] secondOutput = filled(96);
            int secondCount = highlightChecked(second, secondSource, secondOutput);
            start = utf8(secondPrefix).length;
            hasSpan(secondOutput, secondCount, start, start + 4, 3);
        }
        destroySession(first);
        destroySession(first);
        int[] output = filled(96);
        check(highlight(first, utf8("const x = 1;"), output) == -1, "Closed handle remains usable");
        unchanged(output, "Closed handle wrote output");
        check(highlightChecked(second, utf8("const live = 2;"), output) > 0,
                "Closing one document invalidated another");
        long replacement = createSession();
        check(replacement > 0 && replacement != first && replacement != second, "Session ID was reused");
        try {
            output = filled(96);
            check(highlight(first, utf8("const stale = 3;"), output) == -1,
                    "Stale handle aliased the replacement");
            check(highlightChecked(replacement, utf8("const fresh = 4;"), output) > 0,
                    "Replacement session unusable");
        } finally {
            destroySession(replacement);
        }
    }

    private void concurrentClose() throws InterruptedException {
        for (int round = 0; round < 24; round++) {
            long handle = createSession();
            check(handle > 0, "Could not create concurrent session");
            CountDownLatch ready = new CountDownLatch(2);
            CountDownLatch start = new CountDownLatch(1);
            AtomicReference<Throwable> failure = new AtomicReference<>();
            Thread parser = new Thread(() -> {
                ready.countDown();
                try {
                    start.await();
                    byte[] source = utf8("// 中文😀\nconst value = 99;\n");
                    for (int i = 0; i < 24; i++) {
                        int result = highlight(handle, source, new int[96]);
                        check(result > 0 || result == -1, "Highlight/close race returned " + result);
                    }
                } catch (Throwable error) {
                    failure.compareAndSet(null, error);
                }
            }, "treesitter-highlight");
            Thread closer = new Thread(() -> {
                ready.countDown();
                try {
                    start.await();
                    destroySession(handle);
                    destroySession(handle);
                } catch (Throwable error) {
                    failure.compareAndSet(null, error);
                }
            }, "treesitter-close");
            parser.start();
            closer.start();
            try {
                ready.await();
                start.countDown();
                parser.join();
                closer.join();
                if (failure.get() != null) throw new AssertionError("Concurrent JNI call failed", failure.get());
                check(highlight(handle, new byte[0], new int[0]) == -1, "Close race left session live");
            } finally {
                start.countDown();
                destroySession(handle);
            }
        }
    }

    public static void main(String[] args) throws InterruptedException {
        if (args.length != 1) throw new IllegalArgumentException("Pass the host JNI shared-library path");
        System.load(Path.of(args[0]).toAbsolutePath().toString());
        TreeSitterNative nativeBridge = new TreeSitterNative();
        long first = nativeBridge.createSession();
        long second = nativeBridge.createSession();
        check(first > 0 && second > 0 && first != second, "Expected distinct positive session IDs");
        try {
            nativeBridge.unicodeAndNul(first);
            nativeBridge.capacityAndInvalidArguments(first);
            nativeBridge.independentSessions(first, second);
            nativeBridge.concurrentClose();
        } finally {
            nativeBridge.destroySession(first);
            nativeBridge.destroySession(second);
        }
        System.out.println("[PASS] TreeSitterNative JNI smoke: " + CHECKS.get() + " checks");
    }
}
