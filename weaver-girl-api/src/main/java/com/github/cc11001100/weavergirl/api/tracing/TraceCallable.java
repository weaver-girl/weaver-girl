package com.github.cc11001100.weavergirl.api.tracing;

import java.util.concurrent.Callable;

/**
 * Callable wrapper that propagates trace context.
 * @since 1.1.0
 */
public class TraceCallable<V> implements Callable<V> {
    private final Callable<V> delegate;
    private final TracingSnapshot snapshot;

    public TraceCallable(Callable<V> delegate) {
        this.delegate = delegate;
        this.snapshot = Tracer.capture();
    }

    @Override
    public V call() throws Exception {
        Tracer.restore(snapshot);
        try {
            return delegate.call();
        } finally {
            Tracer.clearCurrentSpan();
        }
    }
}
