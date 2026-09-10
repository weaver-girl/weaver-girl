package com.github.cc11001100.weavergirl.api.context;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.function.Consumer;
import java.util.function.Function;

/**
 * Utilities for capturing, activating, and wrapping in-process context.
 *
 * <p>This class is the preferred facade for framework and plugin code that crosses asynchronous
 * boundaries. It keeps propagation behavior consistent across direct wrappers, executor plugins,
 * and future carriers.
 *
 * @see ContextSnapshot
 * @see ContextScope
 * @see ContextRunnable
 * @see ContextCallable
 * @since 1.6.0
 */
public final class ContextPropagators {

  private ContextPropagators() {}

  /**
   * Capture the current thread's propagatable context.
   *
   * @return immutable context snapshot
   */
  public static ContextSnapshot capture() {
    return ContextSnapshot.capture();
  }

  /**
   * Activate a snapshot on the current thread.
   *
   * @param snapshot snapshot to activate; null is treated as empty
   * @return scope that restores the previous context when closed
   */
  public static ContextScope scope(ContextSnapshot snapshot) {
    return ContextScope.activate(snapshot);
  }

  /**
   * Wrap a Runnable with the current context.
   *
   * @param runnable delegate runnable
   * @return context-propagating runnable
   */
  public static Runnable wrap(Runnable runnable) {
    return wrap(runnable, capture());
  }

  /**
   * Wrap a Runnable with an explicit context snapshot.
   *
   * @param runnable delegate runnable
   * @param snapshot snapshot to activate while running
   * @return context-propagating runnable
   */
  public static Runnable wrap(Runnable runnable, ContextSnapshot snapshot) {
    if (runnable instanceof ContextRunnable) {
      return runnable;
    }
    return new ContextRunnable(runnable, snapshot);
  }

  /**
   * Wrap a Callable with the current context.
   *
   * @param callable delegate callable
   * @param <V> return type
   * @return context-propagating callable
   */
  public static <V> Callable<V> wrap(Callable<V> callable) {
    return wrap(callable, capture());
  }

  /**
   * Wrap a Callable with an explicit context snapshot.
   *
   * @param callable delegate callable
   * @param snapshot snapshot to activate while calling
   * @param <V> return type
   * @return context-propagating callable
   */
  public static <V> Callable<V> wrap(Callable<V> callable, ContextSnapshot snapshot) {
    if (callable instanceof ContextCallable) {
      return callable;
    }
    return new ContextCallable<V>(callable, snapshot);
  }

  /**
   * Wrap every Callable in a collection with the same captured context.
   *
   * <p>A new mutable list is returned so immutable input collections can be safely passed to
   * ExecutorService methods after argument replacement.
   *
   * @param callables callable collection
   * @param <V> return type
   * @return list containing wrapped callables
   */
  public static <V> List<Callable<V>> wrapCallables(Collection<? extends Callable<V>> callables) {
    return wrapCallables(callables, capture());
  }

  /**
   * Wrap every Callable in a collection with an explicit context snapshot.
   *
   * @param callables callable collection
   * @param snapshot snapshot to activate while each callable runs
   * @param <V> return type
   * @return list containing wrapped callables
   */
  public static <V> List<Callable<V>> wrapCallables(
      Collection<? extends Callable<V>> callables, ContextSnapshot snapshot) {
    if (callables == null) {
      return null;
    }
    List<Callable<V>> wrapped = new ArrayList<>(callables.size());
    for (Callable<V> callable : callables) {
      wrapped.add(wrap(callable, snapshot));
    }
    return wrapped;
  }

  /**
   * Wrap a Function with the current context.
   *
   * @param function delegate function
   * @param <T> input type
   * @param <R> result type
   * @return context-propagating function
   * @since 1.9.0
   */
  public static <T, R> Function<T, R> wrap(Function<T, R> function) {
    return wrap(function, capture());
  }

  /**
   * Wrap a Function with an explicit context snapshot.
   *
   * @param function delegate function
   * @param snapshot snapshot to activate while running
   * @param <T> input type
   * @param <R> result type
   * @return context-propagating function
   * @since 1.9.0
   */
  public static <T, R> Function<T, R> wrap(Function<T, R> function, ContextSnapshot snapshot) {
    if (function instanceof ContextPropagatingFunction) {
      return function;
    }
    return new ContextPropagatingFunction<>(function, snapshot);
  }

  /**
   * Wrap a Consumer with the current context.
   *
   * @param consumer delegate consumer
   * @param <T> input type
   * @return context-propagating consumer
   * @since 1.9.0
   */
  public static <T> Consumer<T> wrap(Consumer<T> consumer) {
    return wrap(consumer, capture());
  }

  /**
   * Wrap a Consumer with an explicit context snapshot.
   *
   * @param consumer delegate consumer
   * @param snapshot snapshot to activate while running
   * @param <T> input type
   * @return context-propagating consumer
   * @since 1.9.0
   */
  public static <T> Consumer<T> wrap(Consumer<T> consumer, ContextSnapshot snapshot) {
    if (consumer instanceof ContextPropagatingConsumer) {
      return consumer;
    }
    return new ContextPropagatingConsumer<>(consumer, snapshot);
  }

  /** Function wrapper that activates a captured context snapshot before delegating. */
  private static final class ContextPropagatingFunction<T, R> implements Function<T, R> {
    private final Function<T, R> delegate;
    private final ContextSnapshot snapshot;

    ContextPropagatingFunction(Function<T, R> delegate, ContextSnapshot snapshot) {
      this.delegate = delegate;
      this.snapshot = snapshot;
    }

    @Override
    public R apply(T t) {
      try (ContextScope ignored = ContextScope.activate(snapshot)) {
        return delegate.apply(t);
      }
    }
  }

  /** Consumer wrapper that activates a captured context snapshot before delegating. */
  private static final class ContextPropagatingConsumer<T> implements Consumer<T> {
    private final Consumer<T> delegate;
    private final ContextSnapshot snapshot;

    ContextPropagatingConsumer(Consumer<T> delegate, ContextSnapshot snapshot) {
      this.delegate = delegate;
      this.snapshot = snapshot;
    }

    @Override
    public void accept(T t) {
      try (ContextScope ignored = ContextScope.activate(snapshot)) {
        delegate.accept(t);
      }
    }
  }
}
