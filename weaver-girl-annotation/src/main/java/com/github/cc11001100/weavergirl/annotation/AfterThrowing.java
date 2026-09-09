package com.github.cc11001100.weavergirl.annotation;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Marks a method as an after-throwing advice for a specific target method and exception type.
 *
 * <p>This advice runs only when the target method throws an exception of the specified type (or a
 * subclass). Unlike {@link OnException}, which runs on any exception, {@code @AfterThrowing} allows
 * selective exception handling and can optionally rethrow or suppress the exception.
 *
 * <p>Must be used within a class annotated with {@link WeaveClass}. The annotated method must accept
 * a single {@code MethodInvocation} parameter. The thrown exception is available via {@code
 * invocation.getThrowable()}.
 *
 * <h3>Usage example:</h3>
 *
 * <pre>
 * &#64;AfterThrowing(value = "save", exceptionType = IOException.class)
 * public void handleIOError(MethodInvocation inv) {
 *     IOException ex = (IOException) inv.getThrowable();
 *     // handle or wrap...
 * }
 * </pre>
 *
 * <h3>Exception filtering:</h3>
 *
 * <p>Use {@link #exceptionType()} to only handle specific exception types. When not specified
 * (default {@code Throwable.class}), all exceptions are handled.
 *
 * @see WeaveClass
 * @see Before
 * @see After
 * @see OnException
 * @since 2.0.0
 */
@Target(ElementType.METHOD)
@Retention(RetentionPolicy.RUNTIME)
public @interface AfterThrowing {

  /**
   * Name of the target method to intercept.
   *
   * @return the target method name
   */
  String value();

  /**
   * Fully-qualified parameter type names to disambiguate overloaded methods. When empty (default),
   * matches the method by name only.
   *
   * @return parameter type names, or empty array to match by name only
   * @since 2.0.0
   */
  String[] parameterTypes() default {};

  /**
   * Filter exceptions by type. Only exceptions that are instances of the specified type will
   * trigger this advice. When not specified (default {@code Throwable.class}), all exceptions are
   * handled.
   *
   * <p>Example: {@code exceptionType = IOException.class} will only handle IOException and its
   * subclasses.
   *
   * @return the exception type to filter by
   * @since 2.0.0
   */
  Class<? extends Throwable> exceptionType() default Throwable.class;
}
