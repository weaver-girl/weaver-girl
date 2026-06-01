package com.github.cc11001100.weavergirl.api.context;

import java.util.Map;
import java.util.concurrent.Callable;

/**
 * Callable wrapper that captures the ThreadContext at creation time
 * and restores it in the executing thread.
 */
public class ContextCallable<V> implements Callable<V> {

    private final Callable<V> delegate;
    private final Map<String, Object> capturedContext;

    public ContextCallable(Callable<V> delegate) {
        this.delegate = delegate;
        this.capturedContext = ThreadContext.capture();
    }

    @Override
    public V call() throws Exception {
        Map<String, Object> previous = ThreadContext.capture();
        try {
            ThreadContext.restore(capturedContext);
            return delegate.call();
        } finally {
            ThreadContext.restore(previous);
        }
    }
}
