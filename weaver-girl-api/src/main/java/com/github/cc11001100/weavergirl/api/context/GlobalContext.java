package com.github.cc11001100.weavergirl.api.context;

import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Function;

/**
 * Process-wide, thread-visible blackboard for arbitrary interceptor state.
 *
 * <p>Any value written via {@link #put} is immediately visible to all threads in the JVM, on any
 * hook point. This is the <em>global</em> layer complementing the other three propagation
 * primitives:
 *
 * <h3>The four propagation layers</h3>
 *
 * <table border="1">
 *   <tr><th>Layer</th><th>Scope</th><th>When to use</th></tr>
 *   <tr><td>{@code MethodInvocation.setAttachment}</td>
 *       <td>single call (before &rarr; after)</td>
 *       <td>state scoped to one intercepted invocation</td></tr>
 *   <tr><td>{@link ThreadContext}</td>
 *       <td>single thread (ThreadLocal)</td>
 *       <td>state that must NOT leak across threads; cross-thread needs explicit
 *           {@link ThreadContext#capture() capture}/{@link ThreadContext#restore restore}
 *           or {@link ContextRunnable}/{@link ContextCallable}</td></tr>
 *   <tr><td><b>GlobalContext</b></td>
 *       <td>whole process, all threads</td>
 *       <td>state that SHOULD be visible to every thread / hook point</td></tr>
 *   <tr><td>{@code Tracer.inject/extract}</td>
 *       <td>cross-process</td>
 *       <td>HTTP header propagation to another service</td></tr>
 * </table>
 *
 * <h3>When to use GlobalContext</h3>
 *
 * <ul>
 *   <li><b>Async handoff fallback</b> — when an async servlet or thread pool switches threads,
 *       {@link ThreadContext} state is lost. A plugin can mirror the value into GlobalContext as a
 *       process-level fallback so a downstream hook point on a different thread still sees it.
 *   <li><b>Process-wide counters/flags</b> — avoid ad-hoc {@code static AtomicLong} or {@code
 *       static volatile} fields scattered across plugin classes; use {@link #computeIfAbsent} with
 *       an {@link java.util.concurrent.atomic.AtomicLong} value for a uniform, observable counter.
 *   <li><b>Runtime-mutable global config</b> — a feature flag or threshold that any hook point can
 *       read and that can be toggled at runtime from a management endpoint.
 * </ul>
 *
 * <h3>Key naming</h3>
 *
 * <p>Keys are flat strings. To avoid collisions between plugins, use the {@code
 * "<pluginName>.<key>"} convention (e.g. {@code "trace.counter"}, {@code "servlet.traceId"}).
 * Cross-plugin "protocol keys" (e.g. a shared {@code "traceId"}) are by convention; document them
 * where multiple plugins agree to use them.
 *
 * <h3>Memory management</h3>
 *
 * <p><strong>Callers are responsible for removal.</strong> GlobalContext retains keys for the
 * lifetime of the JVM unless explicitly {@link #remove}d or {@link #clear}d. There is no TTL or
 * automatic eviction. For request- or session-scoped state, wrap the {@code put} in a try/finally
 * and {@code remove} in the finally block:
 *
 * <pre>
 * GlobalContext.put("servlet.requestUri", uri);
 * try {
 *     // ... intercepted work ...
 * } finally {
 *     GlobalContext.remove("servlet.requestUri");
 * }</pre>
 *
 * <p>Long-lived "configuration" keys may stay for the JVM lifetime by design.
 *
 * <h3>Null handling</h3>
 *
 * <p>Unlike {@link ThreadContext} (backed by {@link java.util.HashMap}), GlobalContext is backed by
 * {@link ConcurrentHashMap} and does <strong>not</strong> support null keys or null values — a null
 * key or value raises {@link NullPointerException}. Code migrating from ThreadContext must account
 * for this difference.
 *
 * @see ThreadContext
 * @see ContextRunnable
 * @see com.github.cc11001100.weavergirl.api.interceptor.MethodInvocation#setAttachment(String,
 *     Object)
 * @since 1.6.0
 */
public final class GlobalContext {

  private static final ConcurrentHashMap<String, Object> STORE = new ConcurrentHashMap<>();

  private GlobalContext() {}

  /**
   * Store a value in the global context, visible to all threads.
   *
   * @param key the context key; must not be null
   * @param value the value to store; must not be null
   * @throws NullPointerException if key or value is null
   */
  public static void put(String key, Object value) {
    STORE.put(key, value);
  }

  /**
   * Retrieve a value from the global context.
   *
   * <p>The return type is inferred from the call site via unchecked cast. Callers should ensure the
   * stored type matches the expected type.
   *
   * @param key the context key; must not be null
   * @param <T> the expected value type
   * @return the value associated with the key, or null if not found
   * @throws NullPointerException if key is null
   */
  @SuppressWarnings("unchecked")
  public static <T> T get(String key) {
    return (T) STORE.get(key);
  }

  /**
   * Retrieve a value from the global context with a default fallback.
   *
   * <p>If the key exists (even with a null value — though null values are rejected by {@link #put},
   * legacy stores from other paths could in theory produce one), the stored value is returned. If
   * the key does not exist, the default value is returned. This matches {@link
   * ThreadContext#get(String, Object)} semantics.
   *
   * @param key the context key; must not be null
   * @param defaultValue the value to return if the key is not present
   * @param <T> the expected value type
   * @return the value associated with the key, or defaultValue if not found
   * @throws NullPointerException if key is null
   */
  @SuppressWarnings("unchecked")
  public static <T> T get(String key, T defaultValue) {
    return STORE.containsKey(key) ? (T) STORE.get(key) : defaultValue;
  }

  /**
   * Remove a value from the global context.
   *
   * @param key the context key; must not be null
   * @throws NullPointerException if key is null
   */
  public static void remove(String key) {
    STORE.remove(key);
  }

  /** Clear all values from the global context. */
  public static void clear() {
    STORE.clear();
  }

  /**
   * Returns whether the global context contains a value for the given key.
   *
   * @param key the context key; must not be null
   * @return true if a value is present (including a null value, though {@link #put} rejects nulls)
   * @throws NullPointerException if key is null
   */
  public static boolean containsKey(String key) {
    return STORE.containsKey(key);
  }

  /**
   * Atomically compute and store a value if the key is absent.
   *
   * <p>This is the primary primitive for process-wide counters: store an {@link
   * java.util.concurrent.atomic.AtomicLong} once and increment it from any thread, replacing ad-hoc
   * {@code static AtomicLong} fields in plugin classes.
   *
   * <pre>
   * GlobalContext.&lt;AtomicLong&gt;computeIfAbsent("trace.counter", k -&gt; new AtomicLong())
   *         .incrementAndGet();
   * </pre>
   *
   * @param key the context key; must not be null
   * @param mappingFunction the function to compute a value if absent; must not return null
   * @param <T> the value type
   * @return the current (existing or computed) value, never null
   * @throws NullPointerException if key or mappingFunction is null, or mappingFunction returns null
   */
  @SuppressWarnings("unchecked")
  public static <T> T computeIfAbsent(String key, Function<String, T> mappingFunction) {
    return (T) STORE.computeIfAbsent(key, mappingFunction);
  }

  /**
   * Store a value only if the key is not already present.
   *
   * @param key the context key; must not be null
   * @param value the value to store; must not be null
   * @return the existing value if the key was present, or null if the value was stored (no prior
   *     value existed)
   * @throws NullPointerException if key or value is null
   */
  public static Object putIfAbsent(String key, Object value) {
    return STORE.putIfAbsent(key, value);
  }

  /**
   * Replace the value for a key only if it is currently present.
   *
   * @param key the context key; must not be null
   * @param value the new value; must not be null
   * @return the previous value if the key was present (and replaced), or null if the key was absent
   *     (no replacement occurred)
   * @throws NullPointerException if key or value is null
   */
  public static Object replace(String key, Object value) {
    return STORE.replace(key, value);
  }

  /**
   * Replace the value for a key only if it currently maps to the expected old value
   * (compare-and-set semantics).
   *
   * @param key the context key; must not be null
   * @param oldValue the expected current value; must not be null
   * @param newValue the new value; must not be null
   * @return true if the value was replaced
   * @throws NullPointerException if key, oldValue, or newValue is null
   */
  public static boolean replace(String key, Object oldValue, Object newValue) {
    return STORE.replace(key, oldValue, newValue);
  }

  /**
   * Return an unmodifiable snapshot of the global context contents. Useful for diagnostics and
   * management endpoints.
   *
   * <p>The returned map is a point-in-time snapshot; subsequent {@link #put}/{@link #remove} calls
   * are not reflected.
   *
   * @return an unmodifiable copy of the current global context
   * @since 1.7.0
   */
  @SuppressWarnings("unchecked")
  public static java.util.Map<String, Object> snapshot() {
    return java.util.Collections.unmodifiableMap(new java.util.HashMap<>(STORE));
  }

  /**
   * Clear all values from the global context. For test isolation only — production code should use
   * {@link #remove}/{@link #clear} explicitly.
   */
  static void resetForTest() {
    STORE.clear();
  }
}
