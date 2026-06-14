package com.github.cc11001100.weavergirl.annotation;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Sets a maximum execution time for the intercepted method. If the method exceeds the timeout, it
 * is interrupted and a fallback value or exception is used instead.
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

  /** Whether to cancel the thread on timeout. */
  boolean cancelOnTimeout() default true;
}
