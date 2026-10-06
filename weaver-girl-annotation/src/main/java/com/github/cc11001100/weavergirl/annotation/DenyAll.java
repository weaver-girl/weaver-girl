package com.github.cc11001100.weavergirl.annotation;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Denies all invocations of the intercepted method. Applied at the method level within a {@link
 * WeaveClass} interceptor.
 *
 * <p>The original method never executes: every call is skipped and the invocation carries a
 * {@code "denyAll.rejected"} attachment with {@link #message()} so a downstream interceptor (or an
 * around advice) can decide how to surface the denial. This mirrors how the other closed-short
 * wrappers ({@link CircuitBreaker}, {@link RateLimiter}, {@link ValidateArgs}) report rejection,
 * and is the closed-by-default counterpart to {@link RequiresRole} — use it to seal off dangerous
 * legacy endpoints while callers are migrated.
 *
 * <h3>Example:</h3>
 *
 * <pre>
 * &#64;WeaveClass(target = "com.example.LegacyAdminService")
 * public class SealLegacy {
 *     &#64;DenyAll(value = "dropDatabase", message = "disabled: use v2 API")
 *     public void sealDrop(MethodInvocation inv) {
 *         // dropDatabase is now permanently closed
 *     }
 * }
 * </pre>
 *
 * @see RequiresRole
 * @see PreAuthorize
 * @since 1.9.0
 */
@Target(ElementType.METHOD)
@Retention(RetentionPolicy.RUNTIME)
public @interface DenyAll {

  /** Name of the target method to intercept. */
  String value();

  /** Custom error message for the denial. */
  String message() default "Access denied";
}
