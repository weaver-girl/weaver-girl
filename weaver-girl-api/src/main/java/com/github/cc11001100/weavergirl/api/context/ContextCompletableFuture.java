package com.github.cc11001100.weavergirl.api.context;

import java.util.Objects;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executor;
import java.util.concurrent.ForkJoinPool;
import java.util.function.Consumer;
import java.util.function.Function;
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

  /**
   * Apply a function to the result of this future, propagating context from the stage that produced
   * the result.
   *
   * <p>Use this in place of {@code thenApply} when you want the downstream stage to inherit the
   * caller's context that was active when the upstream stage completed.
   *
   * @param <T> input type
   * @param <U> output type
   * @param future the source future
   * @param function function to apply
   * @return a new future with the function's result
   * @since 1.9.0
   */
  public static <T, U> CompletableFuture<U> thenApply(
      CompletableFuture<T> future, java.util.function.Function<? super T, ? extends U> function) {
    Objects.requireNonNull(future, "future");
    Objects.requireNonNull(function, "function");
    return future.thenApply(ContextPropagators.wrap(function));
  }

  /**
   * Apply a function asynchronously to the result of this future, propagating context through the
   * async stage.
   *
   * @param <T> input type
   * @param <U> output type
   * @param future the source future
   * @param function function to apply
   * @param executor executor for the async stage; null uses {@link ForkJoinPool#commonPool()}
   * @return a new future with the function's result
   * @since 1.9.0
   */
  public static <T, U> CompletableFuture<U> thenApplyAsync(
      CompletableFuture<T> future,
      java.util.function.Function<? super T, ? extends U> function,
      Executor executor) {
    Objects.requireNonNull(future, "future");
    Objects.requireNonNull(function, "function");
    return future.thenApplyAsync(ContextPropagators.wrap(function), wrapExecutor(executor));
  }

  /**
   * Consume the result of this future with a callback, propagating context from the completing
   * thread.
   *
   * @param <T> result type
   * @param future the source future
   * @param action action to run
   * @return a new future completing with null
   * @since 1.9.0
   */
  public static <T> CompletableFuture<Void> thenAccept(
      CompletableFuture<T> future, Consumer<? super T> action) {
    Objects.requireNonNull(future, "future");
    Objects.requireNonNull(action, "action");
    return future.thenAccept(ContextPropagators.wrap(action));
  }

  /**
   * Consume the result of this future asynchronously with a callback, propagating context through
   * the async stage.
   *
   * @param <T> result type
   * @param future the source future
   * @param action action to run
   * @param executor executor for the async stage; null uses {@link ForkJoinPool#commonPool()}
   * @return a new future completing with null
   * @since 1.9.0
   */
  public static <T> CompletableFuture<Void> thenAcceptAsync(
      CompletableFuture<T> future,
      Consumer<? super T> action,
      Executor executor) {
    Objects.requireNonNull(future, "future");
    Objects.requireNonNull(action, "action");
    return future.thenAcceptAsync(ContextPropagators.wrap(action), wrapExecutor(executor));
  }

  /**
   * Run a Runnable after this future completes, propagating context from the completing thread.
   *
   * @param future the source future
   * @param action action to run
   * @return a new future completing with null
   * @since 1.9.0
   */
  public static CompletableFuture<Void> thenRun(
      CompletableFuture<?> future, Runnable action) {
    Objects.requireNonNull(future, "future");
    Objects.requireNonNull(action, "action");
    return future.thenRun(ContextPropagators.wrap(action));
  }

  /**
   * Run a Runnable asynchronously after this future completes, propagating context through the
   * async stage.
   *
   * @param future the source future
   * @param action action to run
   * @param executor executor for the async stage; null uses {@link ForkJoinPool#commonPool()}
   * @return a new future completing with null
   * @since 1.9.0
   */
  public static CompletableFuture<Void> thenRunAsync(
      CompletableFuture<?> future, Runnable action, Executor executor) {
    Objects.requireNonNull(future, "future");
    Objects.requireNonNull(action, "action");
    return future.thenRunAsync(ContextPropagators.wrap(action), wrapExecutor(executor));
  }

  /**
   * Compose a new future from the result of this future, propagating context through the dependent
   * stage.
   *
   * @param <T> input type
   * @param <U> output type
   * @param future the source future
   * @param function function producing the dependent future
   * @return a new future composed from the dependent future
   * @since 1.9.0
   */
  public static <T, U> CompletableFuture<U> thenCompose(
      CompletableFuture<T> future,
      java.util.function.Function<? super T, ? extends CompletableFuture<U>> function) {
    Objects.requireNonNull(future, "future");
    Objects.requireNonNull(function, "function");
    return future.thenCompose(ContextPropagators.wrap(function));
  }

  /**
   * Compose a new future asynchronously from the result of this future, propagating context through
   * the async stage.
   *
   * @param <T> input type
   * @param <U> output type
   * @param future the source future
   * @param function function producing the dependent future
   * @param executor executor for the async stage; null uses {@link ForkJoinPool#commonPool()}
   * @return a new future composed from the dependent future
   * @since 1.9.0
   */
  public static <T, U> CompletableFuture<U> thenComposeAsync(
      CompletableFuture<T> future,
      java.util.function.Function<? super T, ? extends CompletableFuture<U>> function,
      Executor executor) {
    Objects.requireNonNull(future, "future");
    Objects.requireNonNull(function, "function");
    return future.thenComposeAsync(ContextPropagators.wrap(function), wrapExecutor(executor));
  }

  /**
   * Handle exceptions from this future asynchronously, propagating context through the async
   * recovery stage.
   *
   * <p>Use this in place of {@code exceptionallyAsync} when you want the recovery function to run
   * with the caller's context propagated to the provided executor.
   *
   * @param <T> result type
   * @param future the source future
   * @param function function to apply if the future completed exceptionally
   * @param executor executor for the async stage; null uses {@link ForkJoinPool#commonPool()}
   * @return a new future completing with the function's result
   * @since 1.9.0
   */
  public static <T> CompletableFuture<T> exceptionallyAsync(
      CompletableFuture<T> future,
      java.util.function.Function<Throwable, ? extends T> function,
      Executor executor) {
    Objects.requireNonNull(future, "future");
    Objects.requireNonNull(function, "function");
    return future.exceptionallyAsync(ContextPropagators.wrap(function), wrapExecutor(executor));
  }

  /**
   * Handle either result or exception from this future asynchronously, propagating context through
   * the async handler stage.
   *
   * <p>Use this in place of {@code handleAsync} when you want the handler function to run with the
   * caller's context propagated to the provided executor.
   *
   * @param <T> result type
   * @param <U> handler result type
   * @param future the source future
   * @param function function to apply with the result or exception
   * @param executor executor for the async stage; null uses {@link ForkJoinPool#commonPool()}
   * @return a new future completing with the function's result
   * @since 1.9.0
   */
  public static <T, U> CompletableFuture<U> handleAsync(
      CompletableFuture<T> future,
      java.util.function.BiFunction<? super T, Throwable, ? extends U> function,
      Executor executor) {
    Objects.requireNonNull(future, "future");
    Objects.requireNonNull(function, "function");
    return future.handleAsync(ContextPropagators.wrap(function), wrapExecutor(executor));
  }

  /**
   * Run a callback when this future completes, asynchronously propagating context through the
   * handler stage.
   *
   * <p>Use this in place of {@code whenCompleteAsync} when you want the action to run with the
   * caller's context propagated to the provided executor.
   *
   * @param <T> result type
   * @param future the source future
   * @param action action to run with the result or exception
   * @param executor executor for the async stage; null uses {@link ForkJoinPool#commonPool()}
   * @return a new future completing with the same result or exception
   * @since 1.9.0
   */
  public static <T> CompletableFuture<T> whenCompleteAsync(
      CompletableFuture<T> future,
      java.util.function.BiConsumer<? super T, ? super Throwable> action,
      Executor executor) {
    Objects.requireNonNull(future, "future");
    Objects.requireNonNull(action, "action");
    return future.whenCompleteAsync(ContextPropagators.wrap(action), wrapExecutor(executor));
  }
}
