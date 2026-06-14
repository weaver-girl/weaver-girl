package com.github.cc11001100.weavergirl.annotation;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Adds a tag (key-value metadata) to the current trace span. Applied at the method level within a
 * {@link WeaveClass} interceptor.
 *
 * <h3>Example:</h3>
 *
 * <pre>
 * &#64;WeaveClass(target = "com.example.OrderService")
 * public class OrderTagger {
 *     &#64;Tag(value = "placeOrder", key = "order.id", argIndex = 0)
 *     public void tagOrderId(MethodInvocation inv) {
 *         // tag is automatically added to the current span
 *     }
 * }
 * </pre>
 *
 * @see Trace
 * @since 1.5.0
 */
@Target(ElementType.METHOD)
@Retention(RetentionPolicy.RUNTIME)
public @interface Tag {

  /** Name of the target method to intercept. */
  String value();

  /** Tag key name. */
  String key();

  /** Static tag value. Use when the value is known at compile time. */
  String tagValue() default "";

  /**
   * Index of the method argument to use as the tag value. Only used when {@link #tagValue()} is
   * empty.
   */
  int argIndex() default -1;

  /** Whether to use the return value as the tag value. */
  boolean useReturn() default false;
}
