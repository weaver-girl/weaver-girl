package com.github.cc11001100.weavergirl.annotation;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Controls the execution order of interceptor definitions produced by a
 * {@code @WeaveClass}-annotated class.
 *
 * <p>Lower values indicate higher priority (executed first). The default priority is 0. This
 * annotation is optional; when absent, the default priority of 0 is used.
 *
 * <p>This annotation is only meaningful on classes that are also annotated with {@link WeaveClass}.
 *
 * <h3>Example:</h3>
 *
 * <pre>
 * &#64;WeaveClass(target = "com.example.UserService")
 * &#64;Order(10)
 * public class SecurityInterceptor {
 *     &#64;Before("save")
 *     public void validate(MethodInvocation inv) { ... }
 * }
 * </pre>
 *
 * @see WeaveClass
 * @since 1.3.0
 */
@Target(ElementType.TYPE)
@Retention(RetentionPolicy.RUNTIME)
public @interface Order {

  /**
   * The priority value for this interceptor. Lower values = higher priority = executed first.
   *
   * @return the priority value, defaults to 0
   */
  int value() default 0;
}
