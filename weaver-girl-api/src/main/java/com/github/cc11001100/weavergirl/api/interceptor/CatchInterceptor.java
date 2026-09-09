package com.github.cc11001100.weavergirl.api.interceptor;

import com.github.cc11001100.weavergirl.api.pointcut.CatchPointcut;

/**
 * Extension of {@link Interceptor} for catch-block interception.
 *
 * <p>Implementations observe or modify exception handling by overriding the catch-specific callback
 * methods. A single implementation may target multiple catch blocks by returning catch pointcuts from
 * {@link #catchPointcuts()}.
 *
 * <h3>Callback model</h3>
 *
 * <ul>
 *   <li>{@link #onCatch(CatchInvocation)} &mdash; called when a matched catch block is entered</li>
 * </ul>
 *
 * <h3>Exception replacement / suppression</h3>
 *
 * <p>The framework resolves conflicting modifications from multiple interceptors by a stable priority
 * order, with "last write wins" semantics. Interceptors execute in priority order defined by {@link
 * #getPriority()}; because the framework advances from lower to higher priority, the highest-priority
 * interceptor that runs last determines the final behavior.
 *
 * <h3>Registration</h3>
 *
 * <p>Catch interceptors are typically registered by the core engine via {@link
 * com.github.cc11001100.weavergirl.core.CatchAdvice} at catch-block join points it instruments.
 * Plugin developers declare catch interception through higher-level DSL or annotation configuration
 * rather than calling low-level registration APIs directly.
 *
 * <h3>Thread safety</h3>
 *
 * <p>Like {@link Interceptor}, implementations must be thread-safe. A single instance may be invoked
 * concurrently from multiple threads. Avoid mutable instance state; use {@link
 * com.github.cc11001100.weavergirl.api.context.ThreadContext} or other thread-local mechanisms if
 * needed.
 *
 * @since 1.8.0
 */
public interface CatchInterceptor {

  /**
   * Called when a matched catch block is entered.
   *
   * <p>Use {@link CatchInvocation#suppressCatch()} to prevent the catch block from executing. Use
   * {@link CatchInvocation#setCaughtException(Throwable)} to replace the caught exception. Use {@link
   * CatchInvocation#setCatchReturnValue(Object)} to override the return value of the catch block.
   *
   * <p>This is a no-op default; override only the callbacks you need.
   *
   * @param invocation context object describing the catch block and caught exception
   * @since 1.8.0
   */
  default void onCatch(CatchInvocation invocation) {}

  /**
   * Returns the pointcuts that select which catch blocks this interceptor targets.
   *
   * <p>The core engine evaluates these pointcuts at catch-block join points to decide whether to
   * invoke this interceptor. An empty or null list means the interceptor is never invoked for
   * catch blocks.
   *
   * @return the list of catch pointcuts
   * @since 1.8.0
   */
  CatchPointcut[] catchPointcuts();

  /**
   * Returns the execution priority of this catch interceptor.
   *
   * <p>Lower values indicate higher priority. When multiple interceptors match the same catch block,
   * callbacks execute in ascending priority order (lowest first). Within the same priority,
   * registration order is used.
   *
   * @return the priority value, or {@code 0} for default priority
   * @since 1.8.0
   */
  default int getPriority() {
    return 0;
  }
}
