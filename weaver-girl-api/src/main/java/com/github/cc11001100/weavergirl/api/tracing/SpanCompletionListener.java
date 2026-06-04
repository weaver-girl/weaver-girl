package com.github.cc11001100.weavergirl.api.tracing;

/**
 * Listener called when a span is completed.
 * @since 1.1.0
 */
public interface SpanCompletionListener {

    /**
     * Called when a span ends.
     * @param span the completed span context
     * @param durationMs the duration in milliseconds
     */
    void onSpanComplete(SpanContext span, long durationMs);
}
