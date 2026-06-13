package com.github.cc11001100.weavergirl.annotation;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Injects the original {@link java.lang.reflect.Method} into the advice method parameter.
 *
 * <h3>Example:</h3>
 * <pre>
 * &#64;WeaveClass(target = "com.example.UserService")
 * public class UserOriginInterceptor {
 *     &#64;Before("save")
 *     public void beforeSave(MethodInvocation inv, &#64;Origin java.lang.reflect.Method method) {
 *         // method is the intercepted Method object
 *     }
 * }
 * </pre>
 *
 * @since 1.5.0
 */
@Target(ElementType.PARAMETER)
@Retention(RetentionPolicy.RUNTIME)
public @interface Origin {
}
