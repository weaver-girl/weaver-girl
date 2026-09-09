package com.github.cc11001100.weavergirl.api.interceptor;

import com.github.cc11001100.weavergirl.api.pointcut.FieldPointcut;

/**
 * Extension of {@link Interceptor} for field-level interception.
 *
 * <p>Implementations observe or modify field access by overriding the field-specific callback methods.
 * A single implementation may target multiple fields by returning field pointcuts from {@link
 * #fieldPointcuts()}.
 *
 * <h3>Callback model</h3>
 *
 * <ul>
 *   <li>{@link #onFieldGet(FieldInvocation, Object)} &mdash; called when a matched field is read</li>
 *   <li>{@link #onFieldSet(FieldInvocation, Object, Object)} &mdash; called when a matched field is
 *       written</li>
 * </ul>
 *
 * <h3>Return value / write value override</h3>
 *
 * <p>The framework resolves conflicting overrides from multiple interceptors by a stable priority
 * order, with "last write wins" semantics. Interceptors execute in priority order defined by {@link
 * #getPriority()}; because the framework advances from lower to higher priority, the highest-priority
 * interceptor that runs last determines the final value.
 *
 * <h3>Registration</h3>
 *
 * <p>Field interceptors are typically registered by the core engine via {@link
 * com.github.cc11001100.weavergirl.core.InterceptAdvice} at the field-access join points it
 * instruments. Plugin developers declare field interception through higher-level DSL or annotation
 * configuration rather than calling low-level registration APIs directly.
 *
 * <h3>Thread safety</h3>
 *
 * <p>Like {@link Interceptor}, implementations must be thread-safe. A single instance may be invoked
 * concurrently from multiple threads. Avoid mutable instance state; use {@link
 * com.github.cc11001100.weavergirl.api.context.ThreadContext} or other thread-local mechanisms if
 * needed.
 *
 * @since 1.7.0
 */
public interface FieldInterceptor {

  /**
   * Called before a matched field is read (getfield).
   *
   * <p>This callback is invoked after the target field has been loaded from memory but before the
   * value is returned to the caller. Interceptors may modify or replace the value via {@link
   * FieldInvocation#setReturnValue(Object)}.
   *
   * <p>This is a no-op default; override only the callbacks you need.
   *
   * @param invocation context object describing the field access
   * @param originalValue the value read from the field
   * @see FieldInvocation#getReturnValue()
   * @see FieldInvocation#setReturnValue(Object)
   * @since 1.7.0
   */
  default void onFieldGet(FieldInvocation invocation, Object originalValue) {}

  /**
   * Called before a matched field is written (putfield).
   *
   * <p>This callback is invoked before the new value is stored to the field. Interceptors may modify
   * or reject the write by calling {@link FieldInvocation#skipWrite()}.
   *
   * <p>This is a no-op default; override only the callbacks you need.
   *
   * @param invocation context object describing the field access
   * @param target the object whose field is being written, or null for static fields
   * @param originalValue the value being written (may be null)
   * @see FieldInvocation#getWriteValue()
   * @see FieldInvocation#skipWrite()
   * @since 1.7.0
   */
  default void onFieldSet(FieldInvocation invocation, Object target, Object originalValue) {}

  /**
   * Returns the pointcuts that select which fields this interceptor targets.
   *
   * <p>The core engine evaluates these pointcuts at field-access join points to decide whether to
   * invoke this interceptor. An empty or null list means the interceptor is never invoked for field
   * access.
   *
   * @return the list of field pointcuts
   * @since 1.7.0
   */
  FieldPointcut[] fieldPointcuts();

  /**
   * Returns the execution priority of this field interceptor.
   *
   * <p>Lower values indicate higher priority. When multiple interceptors match the same field
   * access, callbacks execute in ascending priority order (lowest first). Within the same priority,
   * registration order is used.
   *
   * @return the priority value, or {@code 0} for default priority
   * @since 1.7.0
   */
  default int getPriority() {
    return 0;
  }
}
