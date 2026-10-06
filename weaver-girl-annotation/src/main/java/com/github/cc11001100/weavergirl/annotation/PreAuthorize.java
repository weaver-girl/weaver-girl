package com.github.cc11001100.weavergirl.annotation;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Evaluates an access-control expression before the intercepted method executes. Applied at the
 * method level within a {@link WeaveClass} interceptor.
 *
 * <p>When {@link #expression()} does not hold, the original method never executes: the call is
 * skipped and the invocation carries a {@code "preAuthorize.denied"} attachment with {@link
 * #message()} so a downstream interceptor (or around advice) can surface the denial. This mirrors
 * how the other closed-short wrappers ({@link RequiresRole}, {@link DenyAll}, {@link
 * ValidateArgs}) report rejection, and is the more general counterpart to {@link RequiresRole}
 * (fixed role check): use it for fine-grained, argument-aware authorization such as "the current
 * user owns the resource being accessed".
 *
 * <p>The expression language supports the following primitives (mirroring {@code @ValidateArgs}
 * but evaluated for <em>authorization</em> rather than input validation):
 *
 * <ul>
 *   <li>{@code arg[i]} — the i-th method argument
 *   <li>{@code arg[i] != null} / {@code arg[i] == null}
 *   <li>{@code arg[i] > n} / {@code arg[i] >= n} / {@code arg[i] < n} / {@code arg[i] <= n} — numeric
 *       comparison (also usable standalone with a boolean argument, e.g. {@code arg[0]})
 * </ul>
 *
 * <p>Multiple requirements can be combined by repeating the annotation, or by using {@link
 * RequiresRole} alongside it.
 *
 * <h3>Example:</h3>
 *
 * <pre>
 * &#64;WeaveClass(target = "com.example.AccountService")
 * public class AccountGuard {
 *     &#64;PreAuthorize(value = "transfer", expression = "arg[2] >= 0",
 *         message = "negative transfer amounts are not permitted")
 *     public void guardTransfer(MethodInvocation inv) {
 *         // negative transfers are rejected before the target method runs
 *     }
 * }
 * </pre>
 *
 * @see RequiresRole
 * @see DenyAll
 * @since 1.9.0
 */
@Target(ElementType.METHOD)
@Retention(RetentionPolicy.RUNTIME)
public @interface PreAuthorize {

  /** Name of the target method to intercept. */
  String value();

  /** Access-control expression to evaluate. See the class javadoc for the supported grammar. */
  String expression();

  /** Custom error message when authorization fails. */
  String message() default "Access denied";
}
