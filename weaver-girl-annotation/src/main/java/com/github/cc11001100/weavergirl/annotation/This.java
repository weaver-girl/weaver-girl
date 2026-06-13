package com.github.cc11001100.weavergirl.annotation;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Injects the target instance (this) into the advice method parameter.
 * Only valid in non-static target methods.
 *
 * <h3>Example:</h3>
 * <pre>
 * &#64;WeaveClass(target = "com.example.UserService")
 * public class UserThisInterceptor {
 *     &#64;Before("save")
 *     public void beforeSave(MethodInvocation inv, &#64;This Object target) {
 *         // target is the UserService instance
 *     }
 * }
 * </pre>
 *
 * @since 1.5.0
 */
@Target(ElementType.PARAMETER)
@Retention(RetentionPolicy.RUNTIME)
public @interface This {
}
