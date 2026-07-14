package com.github.cc11001100.weavergirl.api.context;

import java.util.Objects;
import java.util.concurrent.Executor;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.ScheduledExecutorService;

/**
 * {@link Executor} wrapper that captures {@link ThreadContext} at task
 * submission time and activates it while the task runs.
 *
 * <p>Use this wrapper when code accepts only the base {@link Executor}
 * contract. For {@link java.util.concurrent.ExecutorService}, prefer
 * {@link ContextExecutorService} so submit and batch methods are covered too.</p>
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
     * @param delegate executor to wrap
     * @return context-propagating executor
     */
    public static ContextExecutor wrap(Executor delegate) {
        if (delegate instanceof ContextExecutor) {
            return (ContextExecutor) delegate;
        }
        if (delegate instanceof ScheduledExecutorService) {
            return ContextScheduledExecutorService.wrap((ScheduledExecutorService) delegate);
        }
        if (delegate instanceof ExecutorService) {
            return ContextExecutorService.wrap((ExecutorService) delegate);
        }
        return new ContextExecutor(delegate);
    }
}
