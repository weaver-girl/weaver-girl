package com.github.cc11001100.weavergirl.api.tracing;

import java.util.*;

/**
 * Represents a distributed tracing span with context propagation.
 *
 * <p>Carries trace ID, span ID, parent span ID, and baggage items
 * across thread and process boundaries.</p>
 *
 * @since 1.1.0
 */
public class SpanContext {

    private final String traceId;
    private final String spanId;
    private final String parentSpanId;
    private final Map<String, String> baggage;
    private final boolean sampled;

    private SpanContext(Builder builder) {
        this.traceId = builder.traceId;
        this.spanId = builder.spanId;
        this.parentSpanId = builder.parentSpanId;
        this.baggage = Collections.unmodifiableMap(new LinkedHashMap<>(builder.baggage));
        this.sampled = builder.sampled;
    }

    /** Trace ID (unique per trace). */
    public String getTraceId() { return traceId; }

    /** Span ID (unique per span within a trace). */
    public String getSpanId() { return spanId; }

    /** Parent span ID, or null if this is the root span. */
    public String getParentSpanId() { return parentSpanId; }

    /** Whether this span is sampled. */
    public boolean isSampled() { return sampled; }

    /** Baggage items for cross-span propagation. */
    public Map<String, String> getBaggage() { return baggage; }

    /** Get a specific baggage item. */
    public String getBaggageItem(String key) {
        return baggage.get(key);
    }

    /** Whether this is a root span (no parent). */
    public boolean isRoot() { return parentSpanId == null; }

    /**
     * Create a child span context from this one.
     *
     * @param childSpanId the new span ID
     * @return a new SpanContext with this as parent
     */
    public SpanContext newChild(String childSpanId) {
        return new Builder()
                .traceId(traceId)
                .spanId(childSpanId)
                .parentSpanId(spanId)
                .sampled(sampled)
                .baggage(baggage)
                .build();
    }

    /**
     * Create a new builder.
     */
    public static Builder builder() {
        return new Builder();
    }

    /**
     * Builder for SpanContext.
     */
    public static class Builder {
        private String traceId;
        private String spanId;
        private String parentSpanId;
        private final Map<String, String> baggage = new LinkedHashMap<>();
        private boolean sampled = true;

        public Builder traceId(String traceId) { this.traceId = traceId; return this; }
        public Builder spanId(String spanId) { this.spanId = spanId; return this; }
        public Builder parentSpanId(String id) { this.parentSpanId = id; return this; }
        public Builder sampled(boolean sampled) { this.sampled = sampled; return this; }
        public Builder baggage(String key, String value) { this.baggage.put(key, value); return this; }
        public Builder baggage(Map<String, String> items) { this.baggage.putAll(items); return this; }

        public SpanContext build() {
            if (traceId == null || traceId.isEmpty()) {
                throw new IllegalArgumentException("traceId is required");
            }
            if (spanId == null || spanId.isEmpty()) {
                throw new IllegalArgumentException("spanId is required");
            }
            return new SpanContext(this);
        }
    }

    @Override
    public String toString() {
        return "SpanContext{trace=" + traceId + ", span=" + spanId
                + ", parent=" + parentSpanId + ", baggage=" + baggage.size() + "}";
    }
}
