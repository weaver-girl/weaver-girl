package com.github.cc11001100.weavergirl.api.context;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.concurrent.Callable;

/**
 * Utilities for capturing, activating, and wrapping in-process context.
 *
 * <p>This class is the preferred facade for framework and plugin code that
 * crosses asynchronous boundaries. It keeps propagation behavior consistent
 * across direct wrappers, executor plugins, and future carriers.</p>
 *
 * @see ContextSnapshot
 * @see ContextScope
 * @see ContextRunnable
 * @see ContextCallable
 * @since 1.6.0
 */
public final class ContextPropagators {

    private ContextPropagators() {
    }

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
     * <p>A new mutable list is returned so immutable input collections can be
     * safely passed to ExecutorService methods after argument replacement.</p>
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
}
