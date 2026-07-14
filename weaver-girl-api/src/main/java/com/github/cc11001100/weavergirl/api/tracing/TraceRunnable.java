package com.github.cc11001100.weavergirl.api.tracing;

import com.github.cc11001100.weavergirl.api.context.ContextPropagators;

/**
 * Runnable wrapper that propagates trace context to the executing thread.
 *
 * @deprecated Use {@link ContextPropagators#wrap(Runnable)} which propagates
 *             both ThreadContext and Tracer span (and any registered
 *             {@link com.github.cc11001100.weavergirl.api.context.ContextPropagator}).
 *             This class only propagates the Tracer span and does not bridge
 *             traceId/spanId into ThreadContext.
 * @since 1.1.0
 */
@Deprecated
public class TraceRunnable implements Runnable {
    private final Runnable delegate;
    private final TracingSnapshot snapshot;

    public TraceRunnable(Runnable delegate) {
        this.delegate = delegate;
        this.snapshot = Tracer.capture();
    }

    @Override
    public void run() {
        Tracer.restore(snapshot);
        try {
            delegate.run();
        } finally {
            Tracer.clearCurrentSpan();
        }
    }

    /** Wrap a Runnable with trace propagation. */
    public static TraceRunnable wrap(Runnable runnable) {
        return new TraceRunnable(runnable);
    }
}
