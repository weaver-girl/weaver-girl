package com.github.cc11001100.weavergirl.annotation;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Supplies a static fallback return value when the intercepted method fails, without invoking a
 * fallback method. Applied at the method level within a {@link WeaveClass} interceptor.
 *
 * <p>Simpler than {@link Fallback} (which routes to another method): when the target throws a
 * matching exception, the configured {@code stringValue}/{@code longValue}/{@code
 * doubleValue}/{@code booleanValue} is used as the return value (first
 * non-default in that precedence order, falling back to {@code null} for object returns) and the
 * exception is suppressed so the caller observes a normal return.
 *
 * <h3>Example:</h3>
 *
 * <pre>
 * &#64;WeaveClass(target = "com.example.PricingService")
 * public class PricingDefaults {
 *     &#64;FallbackValue(value = "quote", doubleValue = 0.0d, onExceptions = {TimeoutException.class})
 *     public void defaultQuote(MethodInvocation inv) {
 *         // callers see 0.0 instead of a TimeoutException
 *     }
 * }
 * </pre>
 *
 * @see Fallback
 * @see CircuitBreaker
 * @since 1.9.0
 */
@Target(ElementType.METHOD)
@Retention(RetentionPolicy.RUNTIME)
public @interface FallbackValue {

  /** Name of the target method to intercept. */
  String value();

  /** Static string fallback. Takes precedence when non-empty. */
  String stringValue() default "";

  /** Static long fallback. Takes precedence when non-zero. */
  long longValue() default 0L;

  /** Static double fallback. Takes precedence when non-NaN. */
  double doubleValue() default Double.NaN;

  /** Static boolean fallback. */
  boolean booleanValue() default false;

  /** Whether the boolean fallback should be applied. */
  boolean useBooleanValue() default false;

  /** Exception types that trigger the fallback. When empty, all exceptions trigger it. */
  Class<? extends Throwable>[] onExceptions() default {};
}
