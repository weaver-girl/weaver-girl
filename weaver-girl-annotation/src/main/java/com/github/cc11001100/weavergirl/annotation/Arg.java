package com.github.cc11001100.weavergirl.annotation;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Injects a specific method argument into the advice method parameter.
 * Applied to parameters of advice methods within a {@link WeaveClass} interceptor.
 *
 * <h3>Example:</h3>
 * <pre>
 * &#64;WeaveClass(target = "com.example.UserService")
 * public class UserArgInterceptor {
 *     &#64;Before("save")
 *     public void beforeSave(MethodInvocation inv,
 *                            &#64;Arg(0) String username,
 *                            &#64;Arg(1) int age) {
 *         // username and age are automatically injected
 *     }
 * }
 * </pre>
 *
 * @since 1.5.0
 */
@Target(ElementType.PARAMETER)
@Retention(RetentionPolicy.RUNTIME)
public @interface Arg {

    /**
     * Index of the method argument to inject.
     */
    int value();
}
