package com.github.cc11001100.weavergirl.annotation;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Ensures the intercepted method is idempotent — multiple calls with the same arguments produce the
 * same result and side effects.
 *
 * <p>Must be used within a class annotated with {@link WeaveClass}.
 *
 * <h3>Example:</h3>
 *
 * <pre>
 * &#64;WeaveClass(target = "com.example.PaymentService")
 * public class PaymentIdempotency {
 *     &#64;Idempotent(value = "processPayment", ttlMs = 3600000)
 *     public void ensureIdempotent(MethodInvocation inv) {
 *         // idempotency is automatically enforced
 *     }
 * }
 * </pre>
 *
 * @since 1.5.0
 */
@Target(ElementType.METHOD)
@Retention(RetentionPolicy.RUNTIME)
public @interface Idempotent {

  /** Name of the target method to intercept. */
  String value();

  /** Time-to-live for idempotency keys in milliseconds. */
  long ttlMs() default 3600000;

  /** Indices of arguments to use for the idempotency key. When empty, all arguments are used. */
  int[] keyArgIndices() default {};
}
