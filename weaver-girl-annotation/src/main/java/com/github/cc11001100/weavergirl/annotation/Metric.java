package com.github.cc11001100.weavergirl.annotation;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Records a metric value for the intercepted method. Applied at the method level within a {@link
 * WeaveClass} interceptor.
 *
 * <h3>Example:</h3>
 *
 * <pre>
 * &#64;WeaveClass(target = "com.example.OrderService")
 * public class OrderMetrics {
 *     &#64;Metric(value = "placeOrder", name = "order.amount", argIndex = 1)
 *     public void recordOrderAmount(MethodInvocation inv) {
 *         // metric is automatically recorded
 *     }
 * }
 * </pre>
 *
 * @since 1.5.0
 */
@Target(ElementType.METHOD)
@Retention(RetentionPolicy.RUNTIME)
public @interface Metric {

  /** Name of the target method to intercept. */
  String value();

  /** Metric name. */
  String name();

  /** Index of the argument to record as the metric value. */
  int argIndex() default -1;

  /** Whether to use the return value as the metric value. */
  boolean useReturn() default false;
}
