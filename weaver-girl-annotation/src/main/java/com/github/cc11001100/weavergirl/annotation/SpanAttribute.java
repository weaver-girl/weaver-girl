package com.github.cc11001100.weavergirl.annotation;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Adds a key-value attribute to the current trace span. Applied at the method level within a
 * {@link WeaveClass} interceptor.
 *
 * <p>Unlike {@link Tag}, which is a general-purpose label, {@code @SpanAttribute} is explicitly
 * scoped to distributed-tracing spans: the resolved value is stored under the {@code
 * "spanAttr.<key>"} attachment so exporters can pick it up as span metadata.
 *
 * <h3>Example:</h3>
 *
 * <pre>
 * &#64;WeaveClass(target = "com.example.OrderService")
 * public class OrderSpanAttributes {
 *     &#64;SpanAttribute(value = "placeOrder", key = "order.id", argIndex = 0)
 *     public void orderIdAttr(MethodInvocation inv) {
 *         // attribute is automatically added to the current span
 *     }
 * }
 * </pre>
 *
 * @see Trace
 * @see Tag
 * @since 1.9.0
 */
@Target(ElementType.METHOD)
@Retention(RetentionPolicy.RUNTIME)
public @interface SpanAttribute {

  /** Name of the target method to intercept. */
  String value();

  /** Attribute key name. */
  String key();

  /** Static attribute value. Use when the value is known at compile time. */
  String attributeValue() default "";

  /**
   * Index of the method argument to use as the attribute value. Only used when {@link
   * #attributeValue()} is empty.
   */
  int argIndex() default -1;

  /** Whether to use the return value as the attribute value. */
  boolean useReturn() default false;
}
