package com.github.cc11001100.weavergirl.annotation;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Marks a method as an after-advice for a specific target method. Called after the target method
 * returns successfully.
 *
 * <p>The annotated method must accept a single MethodInvocation parameter.
 *
 * @since 1.0.0
 */
@Target(ElementType.METHOD)
@Retention(RetentionPolicy.RUNTIME)
public @interface After {

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
