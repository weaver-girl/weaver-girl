package com.github.cc11001100.weavergirl.annotation;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Marks a method as a before-advice matching target methods by regex pattern. Unlike {@link Before}
 * which matches by exact method name, this annotation uses a regular expression to match method
 * names.
 *
 * <p>Must be used within a class annotated with {@link WeaveClass}.
 *
 * <h3>Example:</h3>
 *
 * <pre>
 * &#64;WeaveClass(target = "com.example.UserService")
 * public class UserServiceInterceptor {
 *     &#64;OnMethodPattern("find.*")   // matches findById, findByName, findAll, etc.
 *     public void beforeFind(MethodInvocation inv) {
 *         log.info("Finding: {}", inv.getMethodName());
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
public @interface OnMethodPattern {

  /**
   * Regex pattern to match target method names.
   *
   * @return a Java regex pattern (e.g., {@code "find.*"}, {@code "save|update"})
   */
  String value();
}
