package com.github.cc11001100.weavergirl.annotation;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Marks a method as an advice that intercepts the constructor of the target class. Called when a
 * new instance of the target class is created.
 *
 * <p>Must be used within a class annotated with {@link WeaveClass}.
 *
 * <h3>Example:</h3>
 *
 * <pre>
 * &#64;WeaveClass(target = "com.example.UserService")
 * public class UserServiceConstructorInterceptor {
 *     &#64;OnConstructor
 *     public void onNewInstance(MethodInvocation inv) {
 *         log.info("UserService created");
 *     }
 * }
 * </pre>
 *
 * @see WeaveClass
 * @since 1.4.0
 */
@Target(ElementType.METHOD)
@Retention(RetentionPolicy.RUNTIME)
public @interface OnConstructor {

  /**
   * Fully-qualified parameter type names to match a specific constructor overload. When empty
   * (default), matches all constructors.
   *
   * @return parameter type names, or empty array to match all constructors
   */
  String[] parameterTypes() default {};
}
