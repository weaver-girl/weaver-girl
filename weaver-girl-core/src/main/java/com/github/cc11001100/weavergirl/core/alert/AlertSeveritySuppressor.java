package com.github.cc11001100.weavergirl.core.alert;

import java.util.concurrent.ConcurrentHashMap;

/**
 * Suppresses lower-severity alerts when a higher-severity alert for the same metric is already
 * active. Severity order: critical > warning > info.
 *
 * @since 1.2.0
 */
public class AlertSeveritySuppressor {

  private static final int SEVERITY_CRITICAL = 3;
  private static final int SEVERITY_WARNING = 2;
  private static final int SEVERITY_INFO = 1;

  /** Maps metric name to current highest active severity level. */
  private final ConcurrentHashMap<String, Integer> activeSeverity = new ConcurrentHashMap<>();

  /**
   * Check if an alert should be suppressed due to a higher-severity alert being active for the same
   * metric.
   *
   * @param metricName the metric name
   * @param severity the alert severity ("info", "warning", "critical")
   * @return true if this alert should be suppressed
   */
  public boolean shouldSuppress(String metricName, String severity) {
    int level = severityLevel(severity);
    Integer active = activeSeverity.get(metricName);
    return active != null && active > level;
  }

  /**
   * Record that an alert was fired at the given severity for a metric.
   *
   * @param metricName the metric name
   * @param severity the alert severity
   */
  public void recordActive(String metricName, String severity) {
    int level = severityLevel(severity);
    activeSeverity.merge(metricName, level, Math::max);
  }

  /**
   * Clear the active severity for a metric (e.g., when the alert recovers).
   *
   * @param metricName the metric name
   */
  public void clearActive(String metricName) {
    activeSeverity.remove(metricName);
  }

  /** Clear all active severity tracking. */
  public void clear() {
    activeSeverity.clear();
  }

  static int severityLevel(String severity) {
    if (severity == null) return SEVERITY_INFO;
    switch (severity.toLowerCase()) {
      case "critical":
        return SEVERITY_CRITICAL;
      case "warning":
        return SEVERITY_WARNING;
      default:
        return SEVERITY_INFO;
    }
  }
}
