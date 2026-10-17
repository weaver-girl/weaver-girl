package com.github.cc11001100.weavergirl.annotation;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Sets a maximum execution time for the intercepted method. If the method exceeds the timeout, the
 * caller observes a {@link java.util.concurrent.TimeoutException} instead of the overdue return
 * value.
 *
 * <p>Timing is enforced at method <em>exit</em>: once the woven body returns, the advice compares
 * the elapsed time against the deadline and substitutes a {@code TimeoutException} for a result
 * that arrived too late. A body that is still running when the deadline passes cannot be forcibly
 * interrupted in a synchronous bytecode weave, so its side effects still complete; what the caller
 * receives, however, is the timeout signal. Combine with {@link Fallback} to turn the timeout into
 * a fallback value.
 *
 * <p>Must be used within a class annotated with {@link WeaveClass}.
 *
 * <h3>Example:</h3>
 *
 * <pre>
 * &#64;WeaveClass(target = "com.example.SlowService")
 * public class TimeoutGuard {
 *     &#64;Timeout(value = "compute", durationMs = 5000)
 *     public void enforceTimeout(MethodInvocation inv) {
 *         // timeout is automatically enforced
 *     }
 * }
 * </pre>
 *
 * @see Fallback
 * @since 1.5.0
 */
@Target(ElementType.METHOD)
@Retention(RetentionPolicy.RUNTIME)
public @interface Timeout {

  /** Name of the target method to intercept. */
  String value();

  /** Maximum execution time in milliseconds. */
  long durationMs() default 30000;

  /**
   * Whether the caller should receive a {@link java.util.concurrent.TimeoutException} on timeout.
   * When {@code false}, the timeout is observed only through the {@code timeout.exceeded}
   * attachment and the guard method, and the original return value passes through.
   */
  boolean cancelOnTimeout() default true;
}
