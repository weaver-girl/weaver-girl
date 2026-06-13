package com.github.cc11001100.weavergirl.annotation;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Validates method arguments before the target method executes.
 * Applied at the method level within a {@link WeaveClass} interceptor.
 *
 * <h3>Example:</h3>
 * <pre>
 * &#64;WeaveClass(target = "com.example.UserService")
 * public class UserValidator {
 *     &#64;ValidateArgs(value = "save", rules = {"arg[0] != null", "arg[1] > 0"})
 *     public void validateSave(MethodInvocation inv) {
 *         // validation is automatically performed
 *     }
 * }
 * </pre>
 *
 * @since 1.5.0
 */
@Target(ElementType.METHOD)
@Retention(RetentionPolicy.RUNTIME)
public @interface ValidateArgs {

    /**
     * Name of the target method to intercept.
     */
    String value();

    /**
     * Validation rules as SpEL-like expressions.
     * Example: "arg[0] != null", "arg[1] > 0", "arg[2].length() < 100"
     */
    String[] rules() default {};

    /**
     * Exception message when validation fails.
     */
    String message() default "Argument validation failed";
}
