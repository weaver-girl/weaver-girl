package com.github.cc11001100.weavergirl.core.alert;

import com.github.cc11001100.weavergirl.api.alert.AlertChannel;
import com.github.cc11001100.weavergirl.api.alert.AlertEvent;
import com.github.cc11001100.weavergirl.api.alert.AlertRule;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Tracks which rules are currently in alert state and detects when a previously-firing rule no
 * longer triggers (recovery). Recovery events are dispatched to all registered channels.
 *
 * @since 1.2.0
 */
public class AlertRecoveryTracker {

  /** Set of rule names currently in alert state. */
  private final Set<String> activeAlerts = ConcurrentHashMap.newKeySet();

  /**
   * Called after evaluation to update alert state and detect recoveries.
   *
   * @param rule the evaluated rule
   * @param value the current metric value
   * @param alertFired whether the alert fired this evaluation
   * @param channels channels to notify on recovery
   * @return true if a recovery was detected and notified
   */
  public boolean updateAndCheckRecovery(
      AlertRule rule, double value, boolean alertFired, List<AlertChannel> channels) {
    String ruleName = rule.getName();
    if (alertFired) {
      activeAlerts.add(ruleName);
      return false;
    }
    if (activeAlerts.remove(ruleName)) {
      // Rule was active, now recovered
      AlertEvent recoveryEvent =
          new AlertEvent(
              System.currentTimeMillis(),
              ruleName,
              rule.getSeverity(),
              "RECOVERED: "
                  + (rule.getMessage() != null
                      ? rule.getMessage()
                      : rule.getMetric() + " " + rule.getOperator() + " " + rule.getThreshold()),
              value,
              rule.getThreshold());
      for (AlertChannel channel : channels) {
        try {
          channel.onAlert(recoveryEvent);
        } catch (Exception ignored) {
          // Channel failures during recovery notification are non-critical
        }
      }
      return true;
    }
    return false;
  }

  /** Check if a rule is currently in alert state. */
  public boolean isActive(String ruleName) {
    return activeAlerts.contains(ruleName);
  }

  /** Clear all active alert tracking. */
  public void clear() {
    activeAlerts.clear();
  }
}
