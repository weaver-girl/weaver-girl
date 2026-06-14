package com.github.cc11001100.weavergirl.annotation;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Applies circuit breaker pattern to the intercepted method. When failures exceed the threshold,
 * the circuit opens and subsequent calls fail fast without invoking the target method.
 *
 * <p>Must be used within a class annotated with {@link WeaveClass}.
 *
 * <h3>Example:</h3>
 *
 * <pre>
 * &#64;WeaveClass(target = "com.example.PaymentService")
 * public class PaymentCircuitBreaker {
 *     &#64;CircuitBreaker(value = "processPayment", failureThreshold = 5, openTimeoutMs = 30000)
 *     public void guardPayment(MethodInvocation inv) {
 *         // circuit breaker is automatically managed
 *     }
 * }
 * </pre>
 *
 * @see Fallback
 * @since 1.5.0
 */
@Target(ElementType.METHOD)
@Retention(RetentionPolicy.RUNTIME)
public @interface CircuitBreaker {

  /** Name of the target method to intercept. */
  String value();

  /** Number of consecutive failures before opening the circuit. */
  int failureThreshold() default 5;

  /** Time in milliseconds the circuit stays open before half-opening. */
  long openTimeoutMs() default 30000;

  /** Number of successful calls in half-open state to close the circuit. */
  int successThreshold() default 3;

  /** Exception types that count as failures. When empty, all exceptions count. */
  Class<? extends Throwable>[] failureTypes() default {};
}
