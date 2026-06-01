package com.github.cc11001100.weavergirl.api.context;

import java.util.Collections;
import java.util.HashMap;
import java.util.Map;

/**
 * Thread-local context that can be captured and propagated across threads.
 * Interceptors can store/retrieve values in this context to carry state
 * across before/after callbacks or across thread boundaries.
 *
 * <p>Usage:</p>
 * <pre>
 *   // In before():
 *   ThreadContext.put("traceId", "abc123");
 *
 *   // In after():
 *   String traceId = ThreadContext.get("traceId");
 *
 *   // Cross-thread:
 *   Map&lt;String, Object&gt; captured = ThreadContext.capture();
 *   executor.submit(() -&gt; {
 *       ThreadContext.restore(captured);
 *       // Now traceId is available in the new thread
 *   });
 * </pre>
 */
public class ThreadContext {

    private static final ThreadLocal<Map<String, Object>> CONTEXT =
            ThreadLocal.withInitial(HashMap::new);

    /**
     * Put a value into the current thread's context.
     */
    public static void put(String key, Object value) {
        CONTEXT.get().put(key, value);
    }

    /**
     * Get a value from the current thread's context.
     */
    @SuppressWarnings("unchecked")
    public static <T> T get(String key) {
        return (T) CONTEXT.get().get(key);
    }

    /**
     * Get a value from the current thread's context with a default.
     */
    @SuppressWarnings("unchecked")
    public static <T> T get(String key, T defaultValue) {
        Map<String, Object> ctx = CONTEXT.get();
        return ctx.containsKey(key) ? (T) ctx.get(key) : defaultValue;
    }

    /**
     * Remove a value from the current thread's context.
     */
    public static void remove(String key) {
        CONTEXT.get().remove(key);
    }

    /**
     * Clear all values from the current thread's context.
     */
    public static void clear() {
        CONTEXT.get().clear();
    }

    /**
     * Capture the current thread's context as an immutable snapshot.
     * The snapshot can be restored in another thread via {@link #restore(Map)}.
     */
    public static Map<String, Object> capture() {
        return Collections.unmodifiableMap(new HashMap<>(CONTEXT.get()));
    }

    /**
     * Restore a previously captured context into the current thread.
     * This replaces all values in the current thread's context.
     */
    public static void restore(Map<String, Object> captured) {
        CONTEXT.get().clear();
        CONTEXT.get().putAll(captured);
    }
}
