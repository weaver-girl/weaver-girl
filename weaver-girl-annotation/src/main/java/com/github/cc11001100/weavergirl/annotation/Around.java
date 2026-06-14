package com.github.cc11001100.weavergirl.annotation;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Marks a method as an around-advice for a specific target method. The advice method receives the
 * MethodInvocation and can call before/after/exception logic in a single method.
 *
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
