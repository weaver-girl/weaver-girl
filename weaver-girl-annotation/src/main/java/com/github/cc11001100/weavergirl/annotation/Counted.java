package com.github.cc11001100.weavergirl.annotation;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Counts method invocations using a named counter.
 * Applied at the method level within a {@link WeaveClass} interceptor.
 *
 * <h3>Example:</h3>
 * <pre>
 * &#64;WeaveClass(target = "com.example.OrderService")
 * public class OrderMetrics {
 *     &#64;Counted(value = "placeOrder", name = "orders.placed", extraTags = {"region", "us-east"})
 *     public void countOrders(MethodInvocation inv) {
 *         // counter is automatically incremented
 *     }
 * }
 * </pre>
 *
 * @since 1.5.0
 */
@Target(ElementType.METHOD)
@Retention(RetentionPolicy.RUNTIME)
public @interface Counted {

    /**
     * Name of the target method to intercept.
     */
    String value();

    /**
     * Counter metric name.
     */
    String name() default "";

    /**
     * Description for the counter metric.
     */
    String description() default "";

    /**
     * Whether to record success/failure separately.
     */
    boolean recordFailuresOnly() default false;
}
