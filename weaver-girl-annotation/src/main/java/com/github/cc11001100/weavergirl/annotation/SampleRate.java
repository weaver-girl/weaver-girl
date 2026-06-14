package com.github.cc11001100.weavergirl.annotation;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Controls the sampling rate for an interceptor class. Only a percentage of invocations will
 * trigger the interceptor's advice methods.
 *
 * <p>Applied at the class level alongside {@link WeaveClass}.
 *
 * <h3>Example:</h3>
 *
 * <pre>
 * &#64;WeaveClass(target = "com.example.HeavyService")
 * &#64;SampleRate(0.1)  // Only intercept 10% of calls
 * public class SamplingInterceptor { ... }
 * </pre>
 *
 * @see WeaveClass
 * @since 1.4.0
 */
@Target(ElementType.TYPE)
@Retention(RetentionPolicy.RUNTIME)
public @interface SampleRate {

  /**
   * The sampling rate as a value between 0.0 (never) and 1.0 (always).
   *
   * <p>For example:
   *
   * <ul>
   *   <li>{@code 1.0} — intercept every call (default, no sampling)
   *   <li>{@code 0.5} — intercept approximately 50% of calls
   *   <li>{@code 0.01} — intercept approximately 1% of calls
   * </ul>
   *
   * @return the sampling rate between 0.0 and 1.0
   */
  double value() default 1.0;
}
