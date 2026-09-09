package com.github.cc11001100.weavergirl.annotation;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Declares precedence ordering between aspect classes.
 *
 * <p>When multiple aspects apply to the same join point, this annotation controls their relative
 * execution order. Aspects with a higher precedence value execute their advice before aspects with a
 * lower precedence value.
 *
 * <p>This is the programmatic equivalent of AspectJ's {@code declare precedence} statement. It
 * provides a more expressive alternative to flat numeric {@link Order} values by allowing
 * aspect-level ordering constraints to be declared explicitly.
 *
 * <h3>Usage example:</h3>
 *
 * <pre>
 * &#64;DeclarePrecedence("com.example.SecurityAspect, com.example.LoggingAspect, com.example.MetricsAspect")
 * public class OrderingConfig {}
 * </pre>
 *
 * <p>In this example, {@code SecurityAspect} advice runs first, followed by {@code LoggingAspect},
 * then {@code MetricsAspect}. The order is determined left-to-right: earlier aspects have higher
 * precedence.
 *
 * <h3>Rules:</h3>
 *
 * <ul>
 *   <li>If both aspects declare {@code @DeclarePrecedence}, the ordering is determined by their
 *       relative positions in each other's declarations. If they conflict, the aspect with the
 *       higher {@link Order} value wins.
 *   <li>If only one aspect declares precedence, it takes precedence over aspects that don't.
 *   <li>This annotation is optional. Without it, ordering falls back to {@link Order} values and
 *       registration order.
 * </ul>
 *
 * @see Order
 * @see WeaveClass
 * @since 2.0.0
 */
@Target(ElementType.TYPE)
@Retention(RetentionPolicy.RUNTIME)
public @interface DeclarePrecedence {

  /**
   * Comma-separated list of fully-qualified aspect class names, ordered from highest precedence to
   * lowest precedence.
   *
   * <p>Whitespace around class names is ignored.
   *
   * @return ordered list of aspect class names
   */
  String value();
}
