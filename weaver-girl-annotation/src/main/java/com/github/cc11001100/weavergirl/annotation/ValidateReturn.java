package com.github.cc11001100.weavergirl.annotation;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Validates the return value after the target method executes. Applied at the method level within a
 * {@link WeaveClass} interceptor.
 *
 * <h3>Example:</h3>
 *
 * <pre>
 * &#64;WeaveClass(target = "com.example.UserService")
 * public class UserResultValidator {
 *     &#64;ValidateReturn(value = "findById", rules = {"result != null"})
 *     public void validateResult(MethodInvocation inv) {
 *         // return value validation is automatic
 *     }
 * }
 * </pre>
 *
 * @since 1.5.0
 */
@Target(ElementType.METHOD)
@Retention(RetentionPolicy.RUNTIME)
public @interface ValidateReturn {

  /** Name of the target method to intercept. */
  String value();

  /** Validation rules as SpEL-like expressions. Example: "result != null", "result.length() > 0" */
  String[] rules() default {};

  /** Exception message when validation fails. */
  String message() default "Return value validation failed";
}
