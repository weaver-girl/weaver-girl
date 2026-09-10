package com.github.cc11001100.weavergirl.api.context;

import java.util.Collection;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Future;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

/**
 * {@link ExecutorService} wrapper that propagates the caller's context to all submitted tasks.
 *
 * <p>The context is captured at each submission boundary, not when the executor is wrapped. Batch
 * methods share one snapshot for the full submitted collection, matching the single logical
 * submission.
 *
 * @see ContextExecutor
 * @see ContextPropagatorss
 * @since 1.6.0
 */
public class ContextExecutorService extends ContextExecutor implements ExecutorService {

  private final ExecutorService delegate;

  /**
   * Create a context-propagating executor service.
   *
   * @param delegate executor service to wrap
   */
  public ContextExecutorService(ExecutorService delegate) {
    super(delegate);
    this.delegate = Objects.requireNonNull(delegate, "delegate");
  }

  @Override
  public void shutdown() {
    delegate.shutdown();
  }

  @Override
  public List<Runnable> shutdownNow() {
    return delegate.shutdownNow();
  }

  @Override
  public boolean isShutdown() {
    return delegate.isShutdown();
  }

  @Override
  public boolean isTerminated() {
    return delegate.isTerminated();
  }

  @Override
  public boolean awaitTermination(long timeout, TimeUnit unit) throws InterruptedException {
    return delegate.awaitTermination(timeout, unit);
  }

  @Override
  public <T> Future<T> submit(Callable<T> task) {
    return delegate.submit(ContextPropagators.wrap(task));
  }

  @Override
  public <T> Future<T> submit(Runnable task, T result) {
    Runnable wrapped = (task instanceof ContextRunnable) ? task : ContextPropagators.wrap(task);
    return delegate.submit(wrapped, result);
  }

  @Override
  public Future<?> submit(Runnable task) {
    Runnable wrapped = (task instanceof ContextRunnable) ? task : ContextPropagators.wrap(task);
    return delegate.submit(wrapped);
  }

  @Override
  public <T> List<Future<T>> invokeAll(Collection<? extends Callable<T>> tasks)
      throws InterruptedException {
    return delegate.invokeAll(ContextPropagators.wrapCallables(tasks));
  }

  @Override
  public <T> List<Future<T>> invokeAll(
      Collection<? extends Callable<T>> tasks, long timeout, TimeUnit unit)
      throws InterruptedException {
    return delegate.invokeAll(ContextPropagators.wrapCallables(tasks), timeout, unit);
  }

  @Override
  public <T> T invokeAny(Collection<? extends Callable<T>> tasks)
      throws InterruptedException, ExecutionException {
    return delegate.invokeAny(ContextPropagators.wrapCallables(tasks));
  }

  @Override
  public <T> T invokeAny(Collection<? extends Callable<T>> tasks, long timeout, TimeUnit unit)
      throws InterruptedException, ExecutionException, TimeoutException {
    return delegate.invokeAny(ContextPropagators.wrapCallables(tasks), timeout, unit);
  }

  /**
   * Return the wrapped executor service.
   *
   * @return delegate executor service
   */
  @Override
  public ExecutorService getDelegate() {
    return delegate;
  }

  /**
   * Wrap an executor service with context propagation.
   *
   * @param delegate executor service to wrap
   * @return context-propagating executor service
   */
  public static ContextExecutorService wrap(ExecutorService delegate) {
    if (delegate instanceof ContextExecutorService) {
      return (ContextExecutorService) delegate;
    }
    if (delegate instanceof ScheduledExecutorService) {
      return ContextScheduledExecutorService.wrap((ScheduledExecutorService) delegate);
    }
    return new ContextExecutorService(delegate);
  }
}
