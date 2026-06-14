package com.github.cc11001100.weavergirl.core.metrics;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Immutable snapshot of metric values at a point in time.
 */
public class MetricSnapshot {

    private final long timestamp;
    private final Map<String, Object> values;

    public MetricSnapshot(long timestamp, Map<String, Object> values) {
        this.timestamp = timestamp;
        this.values = Collections.unmodifiableMap(new LinkedHashMap<>(values));
    }

    public long getTimestamp() { return timestamp; }
    public Map<String, Object> getValues() { return values; }
    public int size() { return values.size(); }

    /**
     * Get a specific metric value.
     */
    public Object get(String name) { return values.get(name); }

    /**
     * Render as JSON string.
     */
    public String toJson() {
        StringBuilder sb = new StringBuilder();
        sb.append("{\"timestamp\":").append(timestamp);
        sb.append(",\"metrics\":{");
        boolean first = true;
        for (Map.Entry<String, Object> entry : values.entrySet()) {
            if (!first) sb.append(",");
            sb.append("\"").append(entry.getKey()).append("\":");
            Object val = entry.getValue();
            if (val instanceof Number) {
                sb.append(val);
            } else {
                sb.append("\"").append(val).append("\"");
            }
            first = false;
        }
        sb.append("}}");
        return sb.toString();
    }

    @Override
    public String toString() {
        return "MetricSnapshot{ts=" + timestamp + ", metrics=" + values.size() + "}";
    }
}
