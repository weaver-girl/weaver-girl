package com.github.cc11001100.weavergirl.api.tracing;

/**
 * Immutable snapshot of tracing state for cross-thread propagation.
 *
 * @since 1.1.0
 */
public final class TracingSnapshot {

    private final SpanContext spanContext;

    /**
     * Create a tracing snapshot.
     *
     * @param spanContext the span context to capture (may be null)
     */
    public TracingSnapshot(SpanContext spanContext) {
        this.spanContext = spanContext;
    }

    /** The captured span context, or null if no span was active. */
    public SpanContext getSpanContext() { return spanContext; }

    /** Whether this snapshot has an active span. */
    public boolean hasSpan() { return spanContext != null; }
}
