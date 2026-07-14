package com.github.cc11001100.weavergirl.api.tracing;

import com.github.cc11001100.weavergirl.api.context.ContextPropagators;

import java.util.concurrent.Callable;

/**
 * Callable wrapper that propagates trace context.
 *
 * @deprecated Use {@link ContextPropagators#wrap(Callable)} which propagates
 *             both ThreadContext and Tracer span (and any registered
 *             {@link com.github.cc11001100.weavergirl.api.context.ContextPropagator}).
 *             This class only propagates the Tracer span and does not bridge
 *             traceId/spanId into ThreadContext.
 * @since 1.1.0
 */
@Deprecated
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
