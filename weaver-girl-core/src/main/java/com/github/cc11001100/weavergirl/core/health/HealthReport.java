package com.github.cc11001100.weavergirl.core.health;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Composite health report containing the overall status and per-component details.
 */
public class HealthReport {

    private final String overallStatus;
    private final Map<String, HealthStatus> components;
    private final long checkDurationMs;
    private final long timestamp;

    HealthReport(String overallStatus, Map<String, HealthStatus> components, long checkDurationMs) {
        this.overallStatus = overallStatus;
        this.components = Collections.unmodifiableMap(new LinkedHashMap<>(components));
        this.checkDurationMs = checkDurationMs;
        this.timestamp = System.currentTimeMillis();
    }

    /** Overall status: UP, DEGRADED, DOWN. */
    public String getOverallStatus() { return overallStatus; }

    /** Per-component health status map. */
    public Map<String, HealthStatus> getComponents() { return components; }

    /** Time taken to run all health checks (ms). */
    public long getCheckDurationMs() { return checkDurationMs; }

    /** Timestamp of this report. */
    public long getTimestamp() { return timestamp; }

    /** Number of components checked. */
    public int getComponentCount() { return components.size(); }

    /** Number of components with DOWN status. */
    public long getDownCount() {
        return components.values().stream().filter(HealthStatus::isDown).count();
    }

    /** Number of components with UP status. */
    public long getUpCount() {
        return components.values().stream().filter(HealthStatus::isUp).count();
    }

    /** Is the overall status UP? */
    public boolean isHealthy() { return "UP".equals(overallStatus); }

    /**
     * Render as a JSON string suitable for HTTP health endpoints.
     */
    public String toJson() {
        StringBuilder sb = new StringBuilder();
        sb.append("{\"status\":\"").append(overallStatus).append("\"");
        sb.append(",\"timestamp\":").append(timestamp);
        sb.append(",\"checkDurationMs\":").append(checkDurationMs);
        sb.append(",\"components\":{");
        boolean first = true;
        for (Map.Entry<String, HealthStatus> entry : components.entrySet()) {
            if (!first) sb.append(",");
            sb.append("\"").append(entry.getKey()).append("\":")
              .append(entry.getValue().toJson());
            first = false;
        }
        sb.append("}}");
        return sb.toString();
    }

    @Override
    public String toString() {
        return String.format("HealthReport{status=%s, components=%d, duration=%dms}",
                overallStatus, components.size(), checkDurationMs);
    }
}
