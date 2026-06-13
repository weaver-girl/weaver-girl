package com.github.cc11001100.weavergirl.annotation;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Logs method entry, exit, and exception information.
 * Applied at the method level within a {@link WeaveClass} interceptor.
 *
 * <h3>Example:</h3>
 * <pre>
 * &#64;WeaveClass(target = "com.example.OrderService")
 * public class OrderLogger {
 *     &#64;Logged(value = "placeOrder", level = "DEBUG", logArgs = true, logResult = true)
 *     public void logOrder(MethodInvocation inv) {
 *         // logging is handled automatically
 *     }
 * }
 * </pre>
 *
 * @since 1.5.0
 */
@Target(ElementType.METHOD)
@Retention(RetentionPolicy.RUNTIME)
public @interface Logged {

    /**
     * Name of the target method to intercept.
     */
    String value();

    /**
     * Log level: TRACE, DEBUG, INFO, WARN, ERROR.
     */
    String level() default "DEBUG";

    /**
     * Whether to log method arguments.
     */
    boolean logArgs() default false;

    /**
     * Whether to log the return value.
     */
    boolean logResult() default false;

    /**
     * Whether to log execution time.
     */
    boolean logTime() default true;

    /**
     * Custom log message prefix.
     */
    String prefix() default "";
}
