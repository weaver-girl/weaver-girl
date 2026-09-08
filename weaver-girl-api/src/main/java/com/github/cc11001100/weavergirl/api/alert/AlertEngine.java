package com.github.cc11001100.weavergirl.api.alert;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * Central alerting engine that evaluates rules and notifies channels.
 *
 * @since 1.2.0
 */
public final class AlertEngine {

  private static final ConcurrentHashMap<String, AlertRule> rules = new ConcurrentHashMap<>();
  private static final CopyOnWriteArrayList<AlertChannel> channels = new CopyOnWriteArrayList<>();
  private static final CopyOnWriteArrayList<AlertEvent> history = new CopyOnWriteArrayList<>();
  private static final int MAX_HISTORY = 200;

  private AlertEngine() {}

  /** Add an alert rule. */
  public static void addRule(AlertRule rule) {
    if (rule != null && rule.getName() != null) {
      rules.put(rule.getName(), rule);
    }
  }

  /** Remove an alert rule. */
  public static void removeRule(String name) {
    if (name != null) rules.remove(name);
  }

  /** Add an alert notification channel. */
  public static void addChannel(AlertChannel channel) {
    if (channel != null) channels.add(channel);
  }

  /** Remove an alert notification channel. */
  public static void removeChannel(AlertChannel channel) {
    channels.remove(channel);
  }

  /**
   * Evaluate a metric value against all rules for the given metric.
   *
   * @param metric the metric name
   * @param value the current value
   * @return list of triggered alerts (may be empty)
   */
  public static List<AlertEvent> evaluate(String metric, double value) {
    List<AlertEvent> triggered = new ArrayList<>();
    for (AlertRule rule : rules.values()) {
      if (rule.isEnabled() && metric.equals(rule.getMetric()) && rule.evaluate(value)) {
        AlertEvent alert =
            new AlertEvent(
                System.currentTimeMillis(),
                rule.getName(),
                rule.getSeverity(),
                rule.getMessage() != null
                    ? rule.getMessage()
                    : rule.getMetric() + " " + rule.getOperator() + " " + rule.getThreshold(),
                value,
                rule.getThreshold());
        triggered.add(alert);
        history.add(alert);
        trimHistory();
        notifyChannels(alert);
      }
    }
    return triggered;
  }

  /** Get all registered rules. */
  public static Collection<AlertRule> getRules() {
    return Collections.unmodifiableCollection(rules.values());
  }

  /** Get alert history. */
  public static List<AlertEvent> getHistory() {
    return Collections.unmodifiableList(new ArrayList<>(history));
  }

  /** Clear all rules, channels, and history. */
  public static void clear() {
    rules.clear();
    channels.clear();
    history.clear();
  }

  private static void notifyChannels(AlertEvent alert) {
    for (AlertChannel channel : channels) {
      try {
        channel.onAlert(alert);
      } catch (Exception ignored) {
      }
    }
  }

  private static void trimHistory() {
    while (history.size() > MAX_HISTORY) {
      history.remove(0);
    }
  }
}
