package com.github.cc11001100.weavergirl.api.tracing;

/**
 * Runnable wrapper that propagates trace context to the executing thread.
 * @since 1.1.0
 */
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
