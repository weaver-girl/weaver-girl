package com.github.cc11001100.weavergirl.annotation;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Marks a method as an around-advice for a specific target method.
 *
 * <p>The advice method runs <em>instead of</em> the target method and must call {@code
 * invocation.proceed()} to let the original method body execute. Advice that never calls {@code
 * proceed()} skips the target method entirely, and any return value set via {@code
 * setReturnValue(...)} replaces the original result. When several {@code @Around} methods target
 * the same method they run in declaration order, each wrapping the next.
 *
 * <p>{@code proceed()} only decides whether the target method runs. The {@code @Before} advice of
 * the same interceptor runs inside the chain, right before the target method, but the method's
 * return value and thrown exception only exist once the method has exited, so they are delivered
 * to the {@code @After}, {@code @AfterReturning}, {@code @OnException} and {@code @AfterFinally}
 * advice rather than being visible to this method after {@code proceed()} returns.
 *
 * <h3>Example:</h3>
 *
 * <pre>
 * &#64;WeaveClass(target = "com.example.PaymentService")
 * public class PaymentGuard {
 *     &#64;Around("charge")
 *     public void guardCharge(MethodInvocation inv) {
 *         if (!allowed(inv.getArgument(0))) {
 *             inv.setReturnValue(false); // skips the target method
 *             return;
 *         }
 *         inv.proceed(); // the target method runs; its result is seen by @After advice
 *     }
 * }
 * </pre>
 *
 * @see com.github.cc11001100.weavergirl.api.interceptor.MethodInvocation#proceed()
 * @since 1.0.0
 */
@Target(ElementType.METHOD)
@Retention(RetentionPolicy.RUNTIME)
public @interface Around {

  /** Name of the target method to intercept. */
  String value();

  /**
   * Fully-qualified parameter type names to disambiguate overloaded methods. When empty (default),
   * matches the method by name only.
   *
   * @return parameter type names, or empty array to match by name only
   * @since 1.4.0
   */
  String[] parameterTypes() default {};
}
