package com.github.cc11001100.weavergirl.core.exporter;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Immutable trace span data for export.
 */
public class SpanData {

    private final String traceId;
    private final String spanId;
    private final String parentSpanId;
    private final String operationName;
    private final long startTimeMs;
    private final long durationMs;
    private final Map<String, String> attributes;
    private final String status;

    private SpanData(Builder builder) {
        this.traceId = builder.traceId;
        this.spanId = builder.spanId;
        this.parentSpanId = builder.parentSpanId;
        this.operationName = builder.operationName;
        this.startTimeMs = builder.startTimeMs;
        this.durationMs = builder.durationMs;
        this.attributes = Collections.unmodifiableMap(new LinkedHashMap<>(builder.attributes));
        this.status = builder.status;
    }

    public String getTraceId() { return traceId; }
    public String getSpanId() { return spanId; }
    public String getParentSpanId() { return parentSpanId; }
    public String getOperationName() { return operationName; }
    public long getStartTimeMs() { return startTimeMs; }
    public long getDurationMs() { return durationMs; }
    public Map<String, String> getAttributes() { return attributes; }
    public String getStatus() { return status; }

    /**
     * Convert to OTLP-compatible JSON string.
     */
    public String toOtlpJson() {
        StringBuilder sb = new StringBuilder();
        sb.append("{");
        sb.append("\"traceId\":\"").append(traceId).append("\"");
        sb.append(",\"spanId\":\"").append(spanId).append("\"");
        if (parentSpanId != null) {
            sb.append(",\"parentSpanId\":\"").append(parentSpanId).append("\"");
        }
        sb.append(",\"name\":\"").append(operationName).append("\"");
        sb.append(",\"startTimeUnixNano\":\"").append(startTimeMs * 1_000_000).append("\"");
        sb.append(",\"endTimeUnixNano\":\"").append((startTimeMs + durationMs) * 1_000_000).append("\"");
        sb.append(",\"status\":{\"code\":\"").append(status).append("\"}");
        if (!attributes.isEmpty()) {
            sb.append(",\"attributes\":[");
            boolean first = true;
            for (Map.Entry<String, String> entry : attributes.entrySet()) {
                if (!first) sb.append(",");
                sb.append("{\"key\":\"").append(entry.getKey())
                  .append("\",\"value\":{\"stringValue\":\"").append(entry.getValue()).append("\"}}");
                first = false;
            }
            sb.append("]");
        }
        sb.append("}");
        return sb.toString();
    }

    @Override
    public String toString() {
        return String.format("SpanData{trace=%s, span=%s, op=%s, duration=%dms}",
                traceId, spanId, operationName, durationMs);
    }

    public static Builder builder() { return new Builder(); }

    public static class Builder {
        private String traceId;
        private String spanId;
        private String parentSpanId;
        private String operationName;
        private long startTimeMs;
        private long durationMs;
        private final Map<String, String> attributes = new LinkedHashMap<>();
        private String status = "OK";

        public Builder traceId(String v) { this.traceId = v; return this; }
        public Builder spanId(String v) { this.spanId = v; return this; }
        public Builder parentSpanId(String v) { this.parentSpanId = v; return this; }
        public Builder operationName(String v) { this.operationName = v; return this; }
        public Builder startTimeMs(long v) { this.startTimeMs = v; return this; }
        public Builder durationMs(long v) { this.durationMs = v; return this; }
        public Builder status(String v) { this.status = v; return this; }
        public Builder attribute(String key, String value) { attributes.put(key, value); return this; }

        public SpanData build() { return new SpanData(this); }
    }
}
