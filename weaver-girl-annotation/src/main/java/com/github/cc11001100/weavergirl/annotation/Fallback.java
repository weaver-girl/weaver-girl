package com.github.cc11001100.weavergirl.annotation;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Specifies a fallback method to call when the target method fails. The fallback method must have
 * the same signature as the target method.
 *
 * <p>Must be used within a class annotated with {@link WeaveClass}.
 *
 * <h3>Example:</h3>
 *
 * <pre>
 * &#64;WeaveClass(target = "com.example.PaymentService")
 * public class PaymentFallback {
 *     &#64;Fallback(value = "processPayment", method = "fallbackPayment")
 *     public void handleFallback(MethodInvocation inv) {
 *         // fallback is automatically invoked on failure
 *     }
 * }
 * </pre>
 *
 * @see CircuitBreaker
 * @see Timeout
 * @since 1.5.0
 */
@Target(ElementType.METHOD)
@Retention(RetentionPolicy.RUNTIME)
public @interface Fallback {

  /** Name of the target method to intercept. */
  String value();

  /** Name of the fallback method in the same target class. */
  String method();

  /** Exception types that trigger the fallback. When empty, all exceptions trigger the fallback. */
  Class<? extends Throwable>[] onExceptions() default {};
}
