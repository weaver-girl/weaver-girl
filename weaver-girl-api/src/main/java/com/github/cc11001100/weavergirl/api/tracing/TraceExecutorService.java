package com.github.cc11001100.weavergirl.api.tracing;

import java.util.*;
import java.util.concurrent.*;

/**
 * ExecutorService wrapper that automatically propagates trace context
 * to all submitted tasks.
 * @since 1.1.0
 */
public class TraceExecutorService implements ExecutorService {

    private final ExecutorService delegate;

    public TraceExecutorService(ExecutorService delegate) {
        this.delegate = delegate;
    }

    // Wrap methods to propagate trace context
    @Override
    public void execute(Runnable command) {
        delegate.execute(TraceRunnable.wrap(command));
    }

    @Override
    public <T> Future<T> submit(Callable<T> task) {
        return delegate.submit(new TraceCallable<T>(task));
    }

    @Override
    public Future<?> submit(Runnable task) {
        return delegate.submit(TraceRunnable.wrap(task));
    }

    @Override
    public <T> Future<T> submit(Runnable task, T result) {
        return delegate.submit(TraceRunnable.wrap(task), result);
    }

    @Override
    public <T> List<Future<T>> invokeAll(Collection<? extends Callable<T>> tasks) throws InterruptedException {
        return delegate.invokeAll(wrapCallables(tasks));
    }

    @Override
    public <T> List<Future<T>> invokeAll(Collection<? extends Callable<T>> tasks, long timeout, TimeUnit unit) throws InterruptedException {
        return delegate.invokeAll(wrapCallables(tasks), timeout, unit);
    }

    @Override
    public <T> T invokeAny(Collection<? extends Callable<T>> tasks) throws InterruptedException, ExecutionException {
        return delegate.invokeAny(wrapCallables(tasks));
    }

    @Override
    public <T> T invokeAny(Collection<? extends Callable<T>> tasks, long timeout, TimeUnit unit) throws InterruptedException, ExecutionException, TimeoutException {
        return delegate.invokeAny(wrapCallables(tasks), timeout, unit);
    }

    // Delegate shutdown methods directly (no trace context needed)
    @Override public void shutdown() { delegate.shutdown(); }
    @Override public List<Runnable> shutdownNow() { return delegate.shutdownNow(); }
    @Override public boolean isShutdown() { return delegate.isShutdown(); }
    @Override public boolean isTerminated() { return delegate.isTerminated(); }
    @Override public boolean awaitTermination(long timeout, TimeUnit unit) throws InterruptedException { return delegate.awaitTermination(timeout, unit); }

    // Wrap helper
    private <T> Collection<Callable<T>> wrapCallables(Collection<? extends Callable<T>> tasks) {
        List<Callable<T>> wrapped = new ArrayList<>();
        for (Callable<T> task : tasks) {
            wrapped.add(new TraceCallable<T>(task));
        }
        return wrapped;
    }

    /** Wrap an ExecutorService with trace propagation. */
    public static TraceExecutorService wrap(ExecutorService delegate) {
        return new TraceExecutorService(delegate);
    }
}
