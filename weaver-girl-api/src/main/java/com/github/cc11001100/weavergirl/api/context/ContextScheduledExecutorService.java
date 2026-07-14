package com.github.cc11001100.weavergirl.api.context;

import java.util.Objects;
import java.util.concurrent.Callable;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;

/**
 * {@link ScheduledExecutorService} wrapper that propagates the caller's context
 * to delayed and periodic tasks.
 *
 * <p>The context is captured when each task is scheduled. Periodic tasks run
 * every execution under that same captured snapshot, and the worker thread's
 * previous context is restored after each run by {@link ContextRunnable}.</p>
 *
 * @see ContextExecutorService
 * @see ContextPropagators
 * @since 1.6.0
 */
public class ContextScheduledExecutorService extends ContextExecutorService implements ScheduledExecutorService {

    private final ScheduledExecutorService delegate;

    /**
     * Create a context-propagating scheduled executor service.
     *
     * @param delegate scheduled executor service to wrap
     */
    public ContextScheduledExecutorService(ScheduledExecutorService delegate) {
        super(delegate);
        this.delegate = Objects.requireNonNull(delegate, "delegate");
    }

    @Override
    public ScheduledFuture<?> schedule(Runnable command, long delay, TimeUnit unit) {
        return delegate.schedule(ContextPropagators.wrap(command), delay, unit);
    }

    @Override
    public <V> ScheduledFuture<V> schedule(Callable<V> callable, long delay, TimeUnit unit) {
        return delegate.schedule(ContextPropagators.wrap(callable), delay, unit);
    }

    @Override
    public ScheduledFuture<?> scheduleAtFixedRate(
            Runnable command, long initialDelay, long period, TimeUnit unit) {
        return delegate.scheduleAtFixedRate(
                ContextPropagators.wrap(command), initialDelay, period, unit);
    }

    @Override
    public ScheduledFuture<?> scheduleWithFixedDelay(
            Runnable command, long initialDelay, long delay, TimeUnit unit) {
        return delegate.scheduleWithFixedDelay(
                ContextPropagators.wrap(command), initialDelay, delay, unit);
    }

    /**
     * Return the wrapped scheduled executor service.
     *
     * @return delegate scheduled executor service
     */
    @Override
    public ScheduledExecutorService getDelegate() {
        return delegate;
    }

    /**
     * Wrap a scheduled executor service with context propagation.
     *
     * @param delegate scheduled executor service to wrap
     * @return context-propagating scheduled executor service
     */
    public static ContextScheduledExecutorService wrap(ScheduledExecutorService delegate) {
        if (delegate instanceof ContextScheduledExecutorService) {
            return (ContextScheduledExecutorService) delegate;
        }
        return new ContextScheduledExecutorService(delegate);
    }
}
