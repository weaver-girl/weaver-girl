package com.github.cc11001100.weavergirl.annotation;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Marks a method as read-only, allowing optimizations and preventing write operations within the
 * intercepted method.
 *
 * <p>Must be used within a class annotated with {@link WeaveClass}.
 *
 * <h3>Example:</h3>
 *
 * <pre>
 * &#64;WeaveClass(target = "com.example.UserService")
 * public class ReadOnlyGuard {
 *     &#64;ReadOnly("findById")
 *     public void enforceReadOnly(MethodInvocation inv) {
 *         // read-only enforcement is automatic
 *     }
 * }
 * </pre>
 *
 * @since 1.5.0
 */
@Target(ElementType.METHOD)
@Retention(RetentionPolicy.RUNTIME)
public @interface ReadOnly {

  /** Name of the target method to intercept. */
  String value();
}
