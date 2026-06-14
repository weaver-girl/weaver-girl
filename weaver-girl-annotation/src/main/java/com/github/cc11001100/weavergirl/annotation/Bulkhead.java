package com.github.cc11001100.weavergirl.annotation;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Limits the number of concurrent executions of the intercepted method. When the limit is reached,
 * additional calls are rejected or queued.
 *
 * <p>Must be used within a class annotated with {@link WeaveClass}.
 *
 * <h3>Example:</h3>
 *
 * <pre>
 * &#64;WeaveClass(target = "com.example.HeavyService")
 * public class ConcurrencyGuard {
 *     &#64;Bulkhead(value = "process", maxConcurrent = 10, maxWaitMs = 5000)
 *     public void limitConcurrency(MethodInvocation inv) {
 *         // concurrency is automatically limited
 *     }
 * }
 * </pre>
 *
 * @since 1.5.0
 */
@Target(ElementType.METHOD)
@Retention(RetentionPolicy.RUNTIME)
public @interface Bulkhead {

  /** Name of the target method to intercept. */
  String value();

  /** Maximum number of concurrent executions. */
  int maxConcurrent() default 10;

  /** Maximum time to wait for a slot in milliseconds. 0 means no waiting (fail fast). */
  long maxWaitMs() default 0;
}
