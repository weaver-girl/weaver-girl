package com.github.cc11001100.weavergirl.annotation;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Marks a method as an exception-advice for a specific target method.
 * Called when the target method throws an exception.
 *
 * <p>Must be used within a class annotated with {@link WeaveClass}.
 * The annotated method must accept a single {@code MethodInvocation} parameter.
 * The thrown exception is available via {@code invocation.getThrowable()}.</p>
 *
 * <h3>Exception type filtering:</h3>
 * <p>Use {@link #exceptionType()} to only handle specific exception types:</p>
 * <pre>
 * &#64;OnException(value = "save", exceptionType = IOException.class)
 * public void handleIOError(MethodInvocation inv) { ... }
 * </pre>
 *
 * @see WeaveClass
 * @see Before
 * @see After
 * @see Around
 * @since 1.3.0
 */
@Target(ElementType.METHOD)
@Retention(RetentionPolicy.RUNTIME)
public @interface OnException {

    /**
     * Name of the target method to intercept.
     *
     * @return the target method name
     */
    String value();

    /**
     * Fully-qualified parameter type names to disambiguate overloaded methods.
     * When empty (default), matches the method by name only.
     *
     * @return parameter type names, or empty array to match by name only
     * @since 1.4.0
     */
    String[] parameterTypes() default {};

    /**
     * Filter exceptions by type. Only exceptions that are instances of
     * the specified type will trigger this advice.
     * When not specified (default), all exceptions are handled.
     *
     * <p>Example: {@code exceptionType = IOException.class} will only
     * handle IOException and its subclasses.</p>
     *
     * @return the exception type to filter by, or {@code Throwable.class} for all
     * @since 1.4.0
     */
    Class<? extends Throwable> exceptionType() default Throwable.class;
}
