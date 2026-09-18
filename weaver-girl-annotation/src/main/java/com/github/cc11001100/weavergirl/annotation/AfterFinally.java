package com.github.cc11001100.weavergirl.annotation;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Marks a method as a finally-advice for a specific target method. Always called on method exit,
 * regardless of whether the target method returned normally, threw an exception, or was skipped by
 * another interceptor.
 *
 * <p>This is the annotation counterpart of {@link
 * com.github.cc11001100.weavergirl.api.interceptor.Interceptor#afterFinally(
 * com.github.cc11001100.weavergirl.api.interceptor.MethodInvocation)}. It runs after {@link After}
 * / {@link OnException} and after any return-value override or exception suppression, so it is the
 * right place for cleanup that must happen no matter what (releasing locks, clearing thread-local
 * state, recording exit metrics).
 *
 * <p>Must be used within a class annotated with {@link WeaveClass}. The annotated method must
 * accept a single {@code MethodInvocation} parameter. Do not call {@code skipMethod()} or {@code
 * suppressException()} here — the outcome is already decided.
 *
 * <h3>Example:</h3>
 *
 * <pre>
 * &#64;WeaveClass(target = "com.example.OrderService")
 * public class OrderCleanup {
 *     &#64;AfterFinally("placeOrder")
 *     public void cleanup(MethodInvocation inv) {
 *         // always runs: success, failure, or skipped
 *         RequestContext.clear();
 *     }
 * }
 * </pre>
 *
 * @see WeaveClass
 * @see After
 * @see OnException
 * @since 2.1.0
 */
@Target(ElementType.METHOD)
@Retention(RetentionPolicy.RUNTIME)
public @interface AfterFinally {

  /**
   * Name of the target method to intercept.
   *
   * @return the target method name
   */
  String value();

  /**
   * Fully-qualified parameter type names to disambiguate overloaded methods. When empty (default),
   * matches the method by name only.
   *
   * @return parameter type names, or empty array to match by name only
   */
  String[] parameterTypes() default {};
}
