package com.github.cc11001100.weavergirl.annotation;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Controls whether an interceptor class is enabled based on a system property
 * or environment variable. Applied at the class level alongside {@link WeaveClass}.
 *
 * <p>The interceptor is only active when the specified condition evaluates to true.</p>
 *
 * <h3>Example (system property):</h3>
 * <pre>
 * &#64;WeaveClass(target = "com.example.UserService")
 * &#64;EnableIf(property = "debug.mode", matchIfMissing = false)
 * public class DebugInterceptor { ... }
 * </pre>
 *
 * <h3>Example (environment variable):</h3>
 * <pre>
 * &#64;WeaveClass(target = "com.example.UserService")
 * &#64;EnableIf(env = "ENABLE_TRACING", havingValue = "true")
 * public class TracingInterceptor { ... }
 * </pre>
 *
 * @see WeaveClass
 * @since 1.4.0
 */
@Target(ElementType.TYPE)
@Retention(RetentionPolicy.RUNTIME)
public @interface EnableIf {

    /**
     * System property name to check. The interceptor is enabled when the property
     * value equals {@link #havingValue()}.
     *
     * @return the system property name
     */
    String property() default "";

    /**
     * Environment variable name to check. The interceptor is enabled when the
     * environment variable value equals {@link #havingValue()}.
     *
     * @return the environment variable name
     */
    String env() default "";

    /**
     * The expected value for the property or environment variable.
     * Defaults to {@code "true"}.
     *
     * @return the expected value
     */
    String havingValue() default "true";

    /**
     * Whether to enable the interceptor when the property/env variable is not set.
     *
     * @return true to enable by default when the condition is not found
     */
    boolean matchIfMissing() default false;
}
