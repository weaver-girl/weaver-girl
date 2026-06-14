package com.github.cc11001100.weavergirl.core.health;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Health status of a single component.
 *
 * <p>Status values:</p>
 * <ul>
 *   <li>{@code UP} — component is healthy</li>
 *   <li>{@code DEGRADED} — component is functioning but with issues</li>
 *   <li>{@code DOWN} — component is unhealthy</li>
 *   <li>{@code UNKNOWN} — component has no registered indicator</li>
 * </ul>
 */
public class HealthStatus {

    private final String status;
    private final Map<String, Object> details;
    private final long timestamp;

    private HealthStatus(Builder builder) {
        this.status = builder.status;
        this.details = Collections.unmodifiableMap(new LinkedHashMap<>(builder.details));
        this.timestamp = System.currentTimeMillis();
    }

    /** Status string: UP, DEGRADED, DOWN, UNKNOWN. */
    public String getStatus() { return status; }

    /** Detailed key-value information about the component's health. */
    public Map<String, Object> getDetails() { return details; }

    /** Timestamp when this status was computed. */
    public long getTimestamp() { return timestamp; }

    /** Convenience: is the status UP? */
    public boolean isUp() { return "UP".equals(status); }

    /** Convenience: is the status DOWN? */
    public boolean isDown() { return "DOWN".equals(status); }

    public static Builder builder() { return new Builder(); }

    /**
     * Render as a simple JSON-like string.
     */
    public String toJson() {
        StringBuilder sb = new StringBuilder();
        sb.append("{\"status\":\"").append(status).append("\"");
        if (!details.isEmpty()) {
            sb.append(",\"details\":{");
            boolean first = true;
            for (Map.Entry<String, Object> entry : details.entrySet()) {
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
            sb.append("}");
        }
        sb.append("}");
        return sb.toString();
    }

    @Override
    public String toString() {
        return "HealthStatus{" + status + ", details=" + details + "}";
    }

    public static class Builder {
        private String status = "UP";
        private final Map<String, Object> details = new LinkedHashMap<>();

        public Builder status(String status) { this.status = status; return this; }
        public Builder up() { this.status = "UP"; return this; }
        public Builder degraded() { this.status = "DEGRADED"; return this; }
        public Builder down() { this.status = "DOWN"; return this; }

        public Builder detail(String key, Object value) {
            details.put(key, value);
            return this;
        }

        public HealthStatus build() { return new HealthStatus(this); }
    }
}
