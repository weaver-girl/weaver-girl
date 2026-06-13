package com.github.cc11001100.weavergirl.annotation;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Requires a specific role to invoke the intercepted method.
 * Applied at the method level within a {@link WeaveClass} interceptor.
 *
 * <h3>Example:</h3>
 * <pre>
 * &#64;WeaveClass(target = "com.example.AdminService")
 * public class AdminGuard {
 *     &#64;RequiresRole(value = "deleteUser", role = "ADMIN")
 *     public void guardAdmin(MethodInvocation inv) {
 *         // access control is enforced automatically
 *     }
 * }
 * </pre>
 *
 * @since 1.5.0
 */
@Target(ElementType.METHOD)
@Retention(RetentionPolicy.RUNTIME)
public @interface RequiresRole {

    /**
     * Name of the target method to intercept.
     */
    String value();

    /**
     * Required role name.
     */
    String role();

    /**
     * Index of the argument containing the principal/username.
     */
    int principalArgIndex() default 0;

    /**
     * Custom error message when access is denied.
     */
    String message() default "Access denied";
}
