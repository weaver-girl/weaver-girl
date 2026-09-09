package com.github.cc11001100.weavergirl.api.interceptor;

/**
 * Core interceptor interface for method-level around-advice.
 *
 * <p>Implementations provide {@code before}/{@code after}/{@code onException} hooks that are
 * invoked around intercepted method calls. This is the primary extension point for plugin
 * developers to observe or modify application behavior.
 *
 * <p>All methods have default no-op implementations so implementors only need to override the hooks
 * they care about.
 *
 * <h3>Execution order</h3>
 *
 * <p>When multiple interceptors target the same method, they are executed in {@link
 * InterceptorDefinition#getPriority() priority} order (lower value = higher priority = executed
 * first). Within the same priority, registration order is used.
 *
 * <h3>Thread safety</h3>
 *
 * <p>Implementations must be <strong>thread-safe</strong>. A single interceptor instance may be
 * invoked concurrently by multiple threads. Avoid mutable instance state; if state is required, use
 * {@link com.github.cc11001100.weavergirl.api.context.ThreadContext} or other thread-local
 * mechanisms.
 *
 * <h3>Error handling</h3>
 *
 * <p>Implementations MUST NOT throw exceptions that escape these callback methods. If an exception
 * occurs inside a callback, catch it internally. The framework will also catch exceptions as a
 * safety net, but implementations should handle their own errors gracefully to avoid interfering
 * with the target application.
 *
 * <h3>Usage example</h3>
 *
 * <pre>
 * Interceptor timingInterceptor = new Interceptor() {
 *     &#64;Override
 *     public void before(MethodInvocation invocation) {
 *         ThreadContext.put("startTime", System.nanoTime());
 *     }
 *
 *     &#64;Override
 *     public void after(MethodInvocation invocation) {
 *         long elapsed = System.nanoTime() - ThreadContext.get("startTime");
 *         System.out.println(invocation.getMethodName() + " took " + elapsed + " ns");
 *     }
 * };</pre>
 *
 * @see MethodInvocation
 * @see InterceptorDefinition
 * @since 1.0.0
 */
public interface Interceptor {

  /**
   * Called before the target method executes.
   *
   * <p>Use {@link MethodInvocation#skipMethod()} to prevent the original method from executing. Use
   * {@link MethodInvocation#setReturnValue(Object)} to supply a return value when skipping.
   *
   * @param invocation context object containing the target class, method name, arguments, and
   *     controls for skipping or overriding the method
   */
  default void before(MethodInvocation invocation) {}

  /**
   * Called after the target method executes successfully (i.e., without throwing).
   *
   * <p>The original return value is available via {@link MethodInvocation#getReturnValue()}.
   * Override it with {@link MethodInvocation#setReturnValue(Object)}.
   *
   * @param invocation context object containing the return value and invocation details
   */
  default void after(MethodInvocation invocation) {}

  /**
   * Called when the target method throws an exception.
   *
   * <p>The thrown exception is available via {@link MethodInvocation#getThrowable()}. Note: this
   * callback is invoked <em>instead of</em> {@link #after(MethodInvocation)}, not in addition to
   * it.
   *
   * @param invocation context object containing the thrown exception and invocation details
   */
  default void onException(MethodInvocation invocation) {}

  /**
   * Around advice: called before the target method executes. Implementations may call {@code
   * invocation.proceed()} to continue the interception chain, or omit it to short-circuit the call.
   *
   * <p>When multiple around interceptors target the same method, they execute in priority order.
   * Each interceptor must call {@link MethodInvocation#proceed()} to allow the chain (and ultimately
   * the original method) to continue. If any interceptor does not call proceed, the method is
   * skipped and any return value set by an interceptor is used instead.
   *
   * <p>This callback is optional. If not implemented, the method proceeds with the normal
   * before/after/onException lifecycle.
   *
   * @param invocation context object containing the target class, method name, arguments, and
   *     controls for proceeding or short-circuiting
   * @since 1.6.0
   */
  default void around(MethodInvocation invocation) {}

  /**
   * Returns whether this interceptor implements around advice. Used by the framework to select
   * interceptors for the around-advice chain without reflection.
   *
   * @return true if around advice is implemented
   * @since 1.6.0
   */
  default boolean hasAround() {
    return false;
  }
}
