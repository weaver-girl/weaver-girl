package com.github.cc11001100.weavergirl.annotation;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Marks a method as a before-advice for a specific target method.
 * Must be used within a class annotated with @WeaveClass.
 *
 * <p>The annotated method must accept a single MethodInvocation parameter.</p>
 *
 * <h3>Method overloading support:</h3>
 * <p>Use {@link #parameterTypes()} to disambiguate between overloaded methods:</p>
 * <pre>
 * &#64;Before(value = "save", parameterTypes = {"java.lang.String"})
 * public void beforeSaveString(MethodInvocation inv) { ... }
 *
 * &#64;Before(value = "save", parameterTypes = {"java.lang.String", "int"})
 * public void beforeSaveStringAndInt(MethodInvocation inv) { ... }
 * </pre>
 *
 * @since 1.0.0
 */
@Target(ElementType.METHOD)
@Retention(RetentionPolicy.RUNTIME)
public @interface Before {

    /**
     * Name of the target method to intercept.
     */
    String value();

    /**
     * Fully-qualified parameter type names to disambiguate overloaded methods.
     * When empty (default), matches the method by name only.
     *
     * <p>Example: {@code {"java.lang.String", "int"}} matches
     * {@code void save(String s, int count)}</p>
     *
     * @return parameter type names, or empty array to match by name only
     * @since 1.4.0
     */
    String[] parameterTypes() default {};
}
