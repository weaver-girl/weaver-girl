package com.github.cc11001100.weavergirl.annotation;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Records execution time as a histogram metric.
 * Applied at the method level within a {@link WeaveClass} interceptor.
 *
 * <h3>Example:</h3>
 * <pre>
 * &#64;WeaveClass(target = "com.example.OrderService")
 * public class OrderMetrics {
 *     &#64;Histogram(value = "placeOrder", name = "order.latency", buckets = {1, 5, 10, 50, 100})
 *     public void recordLatency(MethodInvocation inv) {
 *         // histogram is automatically recorded
 *     }
 * }
 * </pre>
 *
 * @since 1.5.0
 */
@Target(ElementType.METHOD)
@Retention(RetentionPolicy.RUNTIME)
public @interface Histogram {

    /**
     * Name of the target method to intercept.
     */
    String value();

    /**
     * Histogram metric name.
     */
    String name() default "";

    /**
     * Bucket boundaries in milliseconds.
     */
    long[] buckets() default {1, 5, 10, 50, 100, 500, 1000};
}
