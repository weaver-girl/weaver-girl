package com.github.cc11001100.weavergirl.annotation;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Ensures a method argument is not null.
 * Applied to parameters of advice methods within a {@link WeaveClass} interceptor.
 *
 * <h3>Example:</h3>
 * <pre>
 * &#64;WeaveClass(target = "com.example.UserService")
 * public class NotNullGuard {
 *     &#64;Before("save")
 *     public void beforeSave(MethodInvocation inv, &#64;NotNull &#64;Arg(0) String username) {
 *         // username is automatically checked for null
 *     }
 * }
 * </pre>
 *
 * @since 1.5.0
 */
@Target(ElementType.PARAMETER)
@Retention(RetentionPolicy.RUNTIME)
public @interface NotNull {

    /**
     * Error message when the argument is null.
     */
    String message() default "Argument must not be null";
}
