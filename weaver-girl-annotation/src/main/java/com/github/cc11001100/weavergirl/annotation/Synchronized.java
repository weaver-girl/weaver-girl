package com.github.cc11001100.weavergirl.annotation;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Ensures the intercepted method is executed under synchronization. Applied at the method level
 * within a {@link WeaveClass} interceptor.
 *
 * <h3>Example:</h3>
 *
 * <pre>
 * &#64;WeaveClass(target = "com.example.CounterService")
 * public class CounterSync {
 *     &#64;Synchronized("increment")
 *     public void syncIncrement(MethodInvocation inv) {
 *         // synchronization is automatically applied
 *     }
 * }
 * </pre>
 *
 * @since 1.5.0
 */
@Target(ElementType.METHOD)
@Retention(RetentionPolicy.RUNTIME)
public @interface Synchronized {

  /** Name of the target method to intercept. */
  String value();

  /** Lock key prefix. When empty, the target class name is used. */
  String lockKey() default "";

  /** Whether to lock on the target instance (this) or a class-level lock. */
  boolean instanceLevel() default true;
}
