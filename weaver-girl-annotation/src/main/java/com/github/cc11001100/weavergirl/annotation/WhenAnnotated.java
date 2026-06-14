package com.github.cc11001100.weavergirl.annotation;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Marks a method as a before-advice that matches target methods bearing a specific annotation.
 *
 * <p>Unlike {@link Before} which matches by method name, this annotation intercepts any method
 * annotated with the specified annotation class.
 *
 * <p>Must be used within a class annotated with {@link WeaveClass}.
 *
 * <h3>Example:</h3>
 *
 * <pre>
 * &#64;WeaveClass(targetPattern = "com\\.example\\..*")
 * public class MonitoredInterceptor {
 *     &#64;WhenAnnotated("com.example.Monitored")
 *     public void beforeMonitored(MethodInvocation inv) {
 *         metrics.record(inv.getMethodName());
 *     }
 * }
 * </pre>
 *
 * @see Before
 * @see WeaveClass
 * @since 1.4.0
 */
@Target(ElementType.METHOD)
@Retention(RetentionPolicy.RUNTIME)
public @interface WhenAnnotated {

  /**
   * Fully-qualified class name of the annotation to match on target methods.
   *
   * @return the annotation class name (e.g., {@code "com.example.Monitored"})
   */
  String value();
}
