package com.github.cc11001100.weavergirl.annotation;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Records a gauge value for the intercepted method. Applied at the method level within a {@link
 * WeaveClass} interceptor.
 *
 * <h3>Example:</h3>
 *
 * <pre>
 * &#64;WeaveClass(target = "com.example.OrderService")
 * public class OrderMetrics {
 *     &#64;Gauge(value = "getQueueSize", name = "queue.size", useReturn = true)
 *     public void gaugeQueueSize(MethodInvocation inv) {
 *         // gauge is automatically recorded from return value
 *     }
 * }
 * </pre>
 *
 * @since 1.5.0
 */
@Target(ElementType.METHOD)
@Retention(RetentionPolicy.RUNTIME)
public @interface Gauge {

  /** Name of the target method to intercept. */
  String value();

  /** Gauge metric name. */
  String name();

  /** Index of the argument to use as the gauge value. */
  int argIndex() default -1;

  /** Whether to use the return value as the gauge value. */
  boolean useReturn() default false;
}
