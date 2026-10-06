package com.github.cc11001100.weavergirl.annotation;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Suppresses matching exceptions thrown by the intercepted method so the caller observes a normal
 * return (null unless a {@code defaultReturn} marker applies) instead of the exception. Applied at
 * the method level within a {@link WeaveClass} interceptor.
 *
 * <p>Unlike {@link Fallback}, no fallback method runs — the exception is simply swallowed. Use
 * sparingly and prefer narrow {@link #value}-style exception filters via {@link #suppressFor()}
 * over blanket suppression.
 *
 * <h3>Example:</h3>
 *
 * <pre>
 * &#64;WeaveClass(target = "com.example.CacheWarmer")
 * public class BestEffortWarm {
 *     &#64;SuppressExceptions(value = "warm", suppressFor = {RedisConnectionException.class})
 *     public void warmBestEffort(MethodInvocation inv) {
 *         // Redis blips no longer abort application startup
 *     }
 * }
 * </pre>
 *
 * @see Fallback
 * @see FallbackValue
 * @since 1.9.0
 */
@Target(ElementType.METHOD)
@Retention(RetentionPolicy.RUNTIME)
public @interface SuppressExceptions {

  /** Name of the target method to intercept. */
  String value();

  /** Exception types to suppress. When empty, all exceptions are suppressed. */
  Class<? extends Throwable>[] suppressFor() default {};
}
