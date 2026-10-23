package com.github.cc11001100.weavergirl.api.observability;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * One evaluation of every registered {@link HealthCheck}.
 *
 * @since 1.0.0
 */
public final class HealthReport {

  private final HealthCheckResult.Status overallStatus;
  private final Map<String, HealthCheckResult> components;
  private final long durationMs;
  private final long timestamp;

  HealthReport(
      HealthCheckResult.Status overallStatus,
      Map<String, HealthCheckResult> components,
      long durationMs,
      long timestamp) {
    this.overallStatus = overallStatus;
    this.components = Collections.unmodifiableMap(new LinkedHashMap<String, HealthCheckResult>(components));
    this.durationMs = durationMs;
    this.timestamp = timestamp;
  }

  /**
   * Aggregate status: {@code DOWN} if any component is down, else {@code DEGRADED} if any is
   * degraded, else {@code UP}. With no checks registered the report is {@code UP}.
   */
  public HealthCheckResult.Status getOverallStatus() {
    return overallStatus;
  }

  /** Per-component results in registration order; never null. */
  public Map<String, HealthCheckResult> getComponents() {
    return components;
  }

  /** Wall-clock time spent evaluating the checks, in milliseconds. */
  public long getDurationMs() {
    return durationMs;
  }

  /** When this report was produced, in milliseconds since epoch. */
  public long getTimestamp() {
    return timestamp;
  }

  /** Number of components evaluated. */
  public int getComponentCount() {
    return components.size();
  }

  /** Whether the overall status is {@link HealthCheckResult.Status#UP}. */
  public boolean isUp() {
    return overallStatus == HealthCheckResult.Status.UP;
  }
}
