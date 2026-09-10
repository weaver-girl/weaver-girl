package com.github.cc11001100.weavergirl.api.context;

import java.util.Objects;
import java.util.concurrent.Executor;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.ScheduledExecutorService;

/**
 * {@link Executor} wrapper that captures {@link ThreadContext} at task submission time and
 * activates it while the task runs.
 *
 * <p>Use this wrapper when code accepts only the base {@link Executor} contract. For {@link
 * java.util.concurrent.ExecutorService}, prefer {@link ContextExecutorService} so submit and batch
 * methods are covered too.
 *
 * @see ContextExecutorService
 * @see ContextPropagators
 * @since 1.6.0
 */
public class ContextExecutor implements Executor {

  private final Executor delegate;

  /**
   * Create a context-propagating executor.
   *
   * @param delegate executor to wrap
   */
  public ContextExecutor(Executor delegate) {
    this.delegate = Objects.requireNonNull(delegate, "delegate");
  }

  /**
   * Execute a command with the caller's current context.
   *
   * @param command command to run
   */
  @Override
  public void execute(Runnable command) {
    delegate.execute(ContextPropagators.wrap(command));
  }

  /**
   * Return the wrapped executor.
   *
   * @return delegate executor
   */
  public Executor getDelegate() {
    return delegate;
  }

  /**
   * Wrap an executor with context propagation.
   *
   * <p>If the delegate is already a {@link ContextExecutor}, it is returned unchanged to avoid
   * double-wrapping when the same executor passes through multiple wrapper boundaries.
   *
   * @param delegate executor to wrap
   * @return context-propagating executor
   */
  public static ContextExecutor wrap(Executor delegate) {
    if (delegate instanceof ContextExecutor) {
      return (ContextExecutor) delegate;
    }
    if (delegate instanceof java.util.concurrent.ScheduledExecutorService) {
      return ContextScheduledExecutorService.wrap((java.util.concurrent.ScheduledExecutorService) delegate);
    }
    if (delegate instanceof java.util.concurrent.ExecutorService) {
      return ContextExecutorService.wrap((java.util.concurrent.ExecutorService) delegate);
    }
    return new ContextExecutor(delegate);
  }

  /**
   * Start a virtual thread with the caller's current context propagated.
   *
   * <p>This is a no-op on JVMs that do not support virtual threads (pre-Java 21); it falls back to
   * a platform thread wrapped with context propagation.
   *
   * @param task task to run; must not be null
   * @since 1.9.0
   */
  public static void startVirtualThread(Runnable task) {
    Objects.requireNonNull(task, "task");
    Runnable wrapped = ContextPropagators.wrap(task);
    try {
      java.lang.reflect.Method startVirtualThread =
          Thread.class.getMethod("startVirtualThread", Runnable.class);
      startVirtualThread.invoke(null, wrapped);
    } catch (NoSuchMethodException e) {
      // Pre-Java 21: fall back to a platform thread with propagated context.
      new Thread(wrapped).start();
    } catch (Throwable t) {
      throw new RuntimeException("Failed to start virtual thread with context propagation", t);
    }
  }
}
