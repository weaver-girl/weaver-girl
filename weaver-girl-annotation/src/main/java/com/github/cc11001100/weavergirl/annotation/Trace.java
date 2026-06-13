package com.github.cc11001100.weavergirl.annotation;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Marks an interceptor method as creating a distributed trace span around
 * the intercepted method execution.
 *
 * <p>Must be used within a class annotated with {@link WeaveClass}.</p>
 *
 * <h3>Example:</h3>
 * <pre>
 * &#64;WeaveClass(target = "com.example.OrderService")
 * public class OrderTracing {
 *     &#64;Trace(value = "placeOrder", spanName = "order.place")
 *     public void traceOrder(MethodInvocation inv) {
 *         // span is automatically created and closed
 *     }
 * }
 * </pre>
 *
 * @see WeaveClass
 * @since 1.5.0
 */
@Target(ElementType.METHOD)
@Retention(RetentionPolicy.RUNTIME)
public @interface Trace {

    /**
     * Name of the target method to intercept.
     */
    String value();

    /**
     * Custom span name. When empty, the target method name is used.
     */
    String spanName() default "";

    /**
     * Span kind: INTERNAL, SERVER, CLIENT, PRODUCER, CONSUMER.
     */
    SpanKind kind() default SpanKind.INTERNAL;

    /**
     * Whether to record exception details in the span.
     */
    boolean recordException() default true;
}
