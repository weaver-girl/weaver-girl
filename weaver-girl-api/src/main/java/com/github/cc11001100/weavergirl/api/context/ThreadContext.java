package com.github.cc11001100.weavergirl.api.context;

import java.util.Collections;
import java.util.HashMap;
import java.util.Map;

/**
 * Thread-local context that can be captured and propagated across threads.
 *
 * <p>Interceptors can store and retrieve values in this context to carry state
 * across {@link com.github.cc11001100.weavergirl.api.interceptor.Interceptor#before}
 * and {@link com.github.cc11001100.weavergirl.api.interceptor.Interceptor#after}
 * callbacks, or across thread boundaries when combined with
 * {@link ContextRunnable} and {@link ContextCallable}.</p>
 *
 * <h3>Thread safety</h3>
 * <p>Each thread has its own isolated context backed by a {@link ThreadLocal}.
 * Operations within a single thread are inherently safe. For cross-thread
 * propagation, use {@link #capture()} and {@link #restore(Map)}, or the
 * convenience wrappers {@link ContextRunnable} and {@link ContextCallable}.</p>
 *
 * <h3>Usage example</h3>
 * <pre>
 * // In before():
 * ThreadContext.put("traceId", "abc123");
 * ThreadContext.put("startTime", System.nanoTime());
 *
 * // In after():
 * String traceId = ThreadContext.get("traceId");
 * long startTime = ThreadContext.get("startTime", 0L);
 *
 * // Cross-thread propagation:
 * Map&lt;String, Object&gt; captured = ThreadContext.capture();
 * executor.submit(() -&gt; {
 *     ThreadContext.restore(captured);
 *     // Now traceId and startTime are available in the new thread
 *     String traceId = ThreadContext.get("traceId");
 * });
 *
 * // Or use ContextRunnable for automatic propagation:
 * executor.submit(new ContextRunnable(() -&gt; {
 *     String traceId = ThreadContext.get("traceId"); // automatically available
 * }));</pre>
 *
 * @see ContextRunnable
 * @see ContextCallable
 * @since 1.0.0
 */
public class ThreadContext {

    private static final ThreadLocal<Map<String, Object>> CONTEXT =
            ThreadLocal.withInitial(HashMap::new);

    /**
     * Store a value in the current thread's context.
     *
     * @param key   the context key
     * @param value the value to store; may be null
     */
    public static void put(String key, Object value) {
        CONTEXT.get().put(key, value);
    }

    /**
     * Store all values in the current thread's context.
     *
     * @param values values to add; must not be null
     * @since 1.6.0
     */
    public static void putAll(Map<String, Object> values) {
        CONTEXT.get().putAll(values);
    }

    /**
     * Retrieve a value from the current thread's context.
     *
     * <p>The return type is inferred from the call site via unchecked cast.
     * Callers should ensure the stored type matches the expected type.</p>
     *
     * @param key the context key
     * @param <T> the expected value type
     * @return the value associated with the key, or null if not found
     */
    @SuppressWarnings("unchecked")
    public static <T> T get(String key) {
        return (T) CONTEXT.get().get(key);
    }

    /**
     * Retrieve a value from the current thread's context with a default fallback.
     *
     * <p>If the key exists in the context (even with a null value), the stored value
     * is returned. If the key does not exist, the default value is returned.</p>
     *
     * @param key          the context key
     * @param defaultValue the value to return if the key is not present
     * @param <T>          the expected value type
     * @return the value associated with the key, or defaultValue if not found
     */
    @SuppressWarnings("unchecked")
    public static <T> T get(String key, T defaultValue) {
        Map<String, Object> ctx = CONTEXT.get();
        return ctx.containsKey(key) ? (T) ctx.get(key) : defaultValue;
    }

    /**
     * Remove a value from the current thread's context.
     *
     * @param key the context key to remove
     */
    public static void remove(String key) {
        CONTEXT.get().remove(key);
    }

    /**
     * Returns whether the current thread's context contains the given key.
     *
     * @param key the context key
     * @return true if the key exists, including keys mapped to null
     * @since 1.6.0
     */
    public static boolean containsKey(String key) {
        return CONTEXT.get().containsKey(key);
    }

    /**
     * Clear all values from the current thread's context and remove the ThreadLocal entry
     * to prevent memory leaks in thread pool environments.
     */
    public static void clear() {
        CONTEXT.remove();
    }

    /**
     * Capture the current thread's context as an immutable snapshot.
     *
     * <p>The snapshot can be restored in another thread via {@link #restore(Map)}.
     * This is the foundation for cross-thread context propagation.</p>
     *
     * @return an unmodifiable copy of the current thread's context
     */
    public static Map<String, Object> capture() {
        return Collections.unmodifiableMap(new HashMap<>(CONTEXT.get()));
    }

    /**
     * Restore a previously captured context into the current thread.
     *
     * <p>This replaces all values in the current thread's context with the
     * values from the captured snapshot. Use this when manually propagating
     * context across threads, or prefer {@link ContextRunnable}/{@link ContextCallable}
     * for automatic propagation.</p>
     *
     * @param captured a context snapshot previously obtained from {@link #capture()}
     */
    public static void restore(Map<String, Object> captured) {
        if (captured == null || captured.isEmpty()) {
            clear();
            return;
        }
        Map<String, Object> context = CONTEXT.get();
        context.clear();
        context.putAll(captured);
    }

    /**
     * Capture the current thread's context as a {@link ContextSnapshot}.
     *
     * @return immutable context snapshot
     * @since 1.6.0
     */
    public static ContextSnapshot snapshot() {
        return ContextSnapshot.capture();
    }
}
