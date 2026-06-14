package com.github.cc11001100.weavergirl.annotation;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Injects the return value of the target method into the advice method parameter. Only valid in
 * {@link After} and {@link AfterReturning} advice methods.
 *
 * <h3>Example:</h3>
 *
 * <pre>
 * &#64;WeaveClass(target = "com.example.UserService")
 * public class UserResultInterceptor {
 *     &#64;AfterReturning("findById")
 *     public void transformResult(MethodInvocation inv, &#64;Return User user) {
 *         user.setPassword(null);
 *     }
 * }
 * </pre>
 *
 * @since 1.5.0
 */
@Target(ElementType.PARAMETER)
@Retention(RetentionPolicy.RUNTIME)
public @interface Return {}
