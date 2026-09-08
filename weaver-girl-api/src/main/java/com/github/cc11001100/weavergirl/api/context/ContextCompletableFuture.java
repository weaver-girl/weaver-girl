package com.github.cc11001100.weavergirl.api.context;

import java.util.Objects;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executor;
import java.util.concurrent.ForkJoinPool;
import java.util.function.Supplier;

/**
 * Context-propagating factories for {@link CompletableFuture} async stages.
 *
 * <p>{@code CompletableFuture.supplyAsync}/{@code runAsync} accept an {@link Executor} but cannot
 * be transparently woven by the agent: the {@code CompletableFuture} class itself lives in the
 * bootstrap classloader, where {@code ARGUMENT_REWRITE} advice is skipped, and the no-executor
 * overloads use {@link ForkJoinPool#commonPool()} internally (also bootstrap). Use these factories
 * instead of the raw {@code CompletableFuture} overloads — they wrap the target executor with
 * {@link ContextExecutor} at submission time, so {@code ThreadContext}, the Tracer span, {@code
 * TenantContext}, and MDC flow into the worker thread exactly as with {@code
 * ContextExecutorService} submissions.
 *
 * <h3>Usage example</h3>
 *
 * <pre>
 * // Instead of:
 * CompletableFuture.supplyAsync(() -&gt; loadUser(id), executor);
 * // Use:
 * ContextCompletableFuture.supplyAsync(() -&gt; loadUser(id), executor);
 * </pre>
 *
 * @see ContextExecutor
 * @see ContextPropagators
 * @since 1.9.0
 */
public final class ContextCompletableFuture {

  private ContextCompletableFuture() {}

  /**
   * Run a Supplier asynchronously with the caller's context, using the common ForkJoinPool wrapped
   * for context propagation.
   *
   * @param supplier supplier producing the result; must not be null
   * @param <U> result type
   * @return future completing with the supplier's result
   */
  public static <U> CompletableFuture<U> supplyAsync(Supplier<U> supplier) {
    return supplyAsync(supplier, ForkJoinPool.commonPool());
  }

  /**
   * Run a Supplier asynchronously on the given executor with the caller's context propagated to the
   * worker thread.
   *
   * @param supplier supplier producing the result; must not be null
   * @param executor executor to run on; null selects the common ForkJoinPool
   * @param <U> result type
   * @return future completing with the supplier's result
   */
  public static <U> CompletableFuture<U> supplyAsync(Supplier<U> supplier, Executor executor) {
    Objects.requireNonNull(supplier, "supplier");
    return CompletableFuture.supplyAsync(supplier, wrapExecutor(executor));
  }

  /**
   * Run a Runnable asynchronously with the caller's context, using the common ForkJoinPool wrapped
   * for context propagation.
   *
   * @param runnable task to run; must not be null
   * @return future completing when the task finishes
   */
  public static CompletableFuture<Void> runAsync(Runnable runnable) {
    return runAsync(runnable, ForkJoinPool.commonPool());
  }

  /**
   * Run a Runnable asynchronously on the given executor with the caller's context propagated to the
   * worker thread.
   *
   * @param runnable task to run; must not be null
   * @param executor executor to run on; null selects the common ForkJoinPool
   * @return future completing when the task finishes
   */
  public static CompletableFuture<Void> runAsync(Runnable runnable, Executor executor) {
    Objects.requireNonNull(runnable, "runnable");
    return CompletableFuture.runAsync(runnable, wrapExecutor(executor));
  }

  /**
   * Wrap an executor for context propagation, defaulting a null executor to the common ForkJoinPool
   * so callers can pass through an optional executor without branching.
   *
   * @param executor executor to wrap; may be null
   * @return context-propagating executor; never null
   */
  static Executor wrapExecutor(Executor executor) {
    if (executor == null) {
      return ContextExecutor.wrap(ForkJoinPool.commonPool());
    }
    return ContextExecutor.wrap(executor);
  }
}
