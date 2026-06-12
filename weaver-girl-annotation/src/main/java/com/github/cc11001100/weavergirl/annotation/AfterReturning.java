package com.github.cc11001100.weavergirl.annotation;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Marks a method as an after-returning advice for a specific target method.
 * Called after the target method returns successfully (does not throw).
 *
 * <p>Must be used within a class annotated with {@link WeaveClass}.
 * The annotated method must accept a single {@code MethodInvocation} parameter.
 * The return value is available via {@code invocation.getReturnValue()} and
 * can be overridden via {@code invocation.setReturnValue(...)}.</p>
 *
 * <p>Unlike {@link After}, this annotation is guaranteed to be called only
 * on successful return (not on exception). This provides cleaner semantics
 * when you need to post-process the return value.</p>
 *
 * <h3>Example:</h3>
 * <pre>
 * &#64;WeaveClass(target = "com.example.UserService")
 * public class UserResultTransformer {
 *     &#64;AfterReturning("findById")
 *     public void maskSensitiveFields(MethodInvocation inv) {
 *         User user = (User) inv.getReturnValue();
 *         if (user != null) {
 *             user.setPassword(null); // mask sensitive data
 *         }
 *     }
 * }
 * </pre>
 *
 * @see WeaveClass
 * @see Before
 * @see After
 * @see OnException
 * @since 1.3.0
 */
@Target(ElementType.METHOD)
@Retention(RetentionPolicy.RUNTIME)
public @interface AfterReturning {

    /**
     * Name of the target method to intercept.
     *
     * @return the target method name
     */
    String value();
}
