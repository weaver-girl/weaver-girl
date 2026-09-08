package com.github.cc11001100.weavergirl.api.metrics;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Registry of named metric aggregators.
 *
 * @since 1.2.0
 */
public final class MetricRegistry {

  private static final ConcurrentHashMap<String, TimeWindowAggregator> aggregators =
      new ConcurrentHashMap<>();

  /** Default 1-minute window. */
  public static final long WINDOW_1MIN = 60000;

  /** Default 5-minute window. */
  public static final long WINDOW_5MIN = 300000;

  /** Default 1-hour window. */
  public static final long WINDOW_1HOUR = 3600000;

  private MetricRegistry() {}

  /**
   * Get or create an aggregator with the default 1-minute window.
   *
   * @param name metric name
   * @return the aggregator
   */
  public static TimeWindowAggregator getOrCreate(String name) {
    return getOrCreate(name, WINDOW_1MIN);
  }

  /**
   * Get or create an aggregator with a custom window.
   *
   * @param name metric name
   * @param windowMs time window in milliseconds
   * @return the aggregator
   */
  public static TimeWindowAggregator getOrCreate(String name, long windowMs) {
    return aggregators.computeIfAbsent(name, n -> new TimeWindowAggregator(n, windowMs));
  }

  /**
   * Get or create an aggregator with a custom window, logging a warning if the requested window
   * differs from the existing aggregator's window.
   *
   * @param name metric name
   * @param windowMs time window in milliseconds
   * @return the aggregator
   */
  public static TimeWindowAggregator getOrCreateStrict(String name, long windowMs) {
    TimeWindowAggregator existing = aggregators.get(name);
    if (existing != null && existing.getWindowMs() != windowMs) {
      // java.util.logging (not System.Logger, which is JDK 9+) keeps the API
      // module dependency-free AND runnable on JDK 8.
      java.util.logging.Logger.getLogger(MetricRegistry.class.getName())
          .warning(
              "Metric '"
                  + name
                  + "' already exists with window "
                  + existing.getWindowMs()
                  + "ms, ignoring requested window "
                  + windowMs
                  + "ms");
    }
    return getOrCreate(name, windowMs);
  }

  /**
   * Record a value to a named metric.
   *
   * @param name metric name
   * @param value the value
   */
  public static void record(String name, double value) {
    getOrCreate(name).record(value);
  }

  /**
   * Take a snapshot of a named metric.
   *
   * @param name metric name
   * @return snapshot, or null if metric doesn't exist
   */
  public static MetricSnapshot snapshot(String name) {
    TimeWindowAggregator agg = aggregators.get(name);
    return agg != null ? agg.snapshot() : null;
  }

  /**
   * Take snapshots of all metrics.
   *
   * @return map of metric name to snapshot
   */
  public static Map<String, MetricSnapshot> snapshotAll() {
    Map<String, MetricSnapshot> result = new LinkedHashMap<>();
    for (Map.Entry<String, TimeWindowAggregator> entry : aggregators.entrySet()) {
      MetricSnapshot snapshot = entry.getValue().snapshot();
      Map<String, String> labels = labelsFromKey(entry.getKey());
      if (!labels.isEmpty()) {
        snapshot =
            new MetricSnapshot(
                snapshot.getWindowStartMs(),
                snapshot.getWindowEndMs(),
                snapshot.getMetricName(),
                snapshot.getCount(),
                snapshot.getSum(),
                snapshot.getMin(),
                snapshot.getMax(),
                snapshot.getPercentiles(),
                labels);
      }
      result.put(entry.getKey(), snapshot);
    }
    return result;
  }

  /** Get all registered metric names. */
  public static Set<String> getMetricNames() {
    return Collections.unmodifiableSet(aggregators.keySet());
  }

  /**
   * Record a value with labels/tags. The metric key is composed as "name{key1=val1,key2=val2}".
   *
   * @param name metric name
   * @param labels label key-value pairs
   * @param value the value
   */
  public static void record(String name, Map<String, String> labels, double value) {
    String key = labeledKey(name, labels);
    getOrCreate(key).record(value);
  }

  /**
   * Take a snapshot of a labeled metric.
   *
   * @param name metric name
   * @param labels label key-value pairs
   * @return snapshot with labels attached, or null if metric doesn't exist
   */
  public static MetricSnapshot snapshot(String name, Map<String, String> labels) {
    String key = labeledKey(name, labels);
    MetricSnapshot snap = snapshot(key);
    if (snap == null) return null;
    return new MetricSnapshot(
        snap.getWindowStartMs(),
        snap.getWindowEndMs(),
        snap.getMetricName(),
        snap.getCount(),
        snap.getSum(),
        snap.getMin(),
        snap.getMax(),
        snap.getPercentiles(),
        labels);
  }

  /**
   * Get all metric names that match a base name (with or without labels).
   *
   * @param baseName the base metric name
   * @return list of matching full metric keys
   */
  public static List<String> getMetricsByName(String baseName) {
    List<String> result = new ArrayList<>();
    for (String key : aggregators.keySet()) {
      if (key.equals(baseName) || key.startsWith(baseName + "{")) {
        result.add(key);
      }
    }
    return result;
  }

  private static String labeledKey(String name, Map<String, String> labels) {
    if (labels == null || labels.isEmpty()) return name;
    StringBuilder sb = new StringBuilder(name).append("{");
    boolean first = true;
    for (Map.Entry<String, String> e : new TreeMap<>(labels).entrySet()) {
      if (!first) sb.append(",");
      sb.append(e.getKey()).append("=").append(e.getValue());
      first = false;
    }
    sb.append("}");
    return sb.toString();
  }

  private static Map<String, String> labelsFromKey(String key) {
    int start = key.indexOf('{');
    if (start < 0 || !key.endsWith("}")) return Collections.emptyMap();
    Map<String, String> labels = new LinkedHashMap<>();
    String body = key.substring(start + 1, key.length() - 1);
    if (body.isEmpty()) return labels;
    for (String pair : body.split(",")) {
      int separator = pair.indexOf('=');
      if (separator > 0) {
        labels.put(pair.substring(0, separator), pair.substring(separator + 1));
      }
    }
    return labels;
  }

  /** Remove a metric. */
  public static void remove(String name) {
    aggregators.remove(name);
  }

  /** Clear all metrics. */
  public static void clear() {
    aggregators.clear();
  }

  /** Get registered metric count. */
  public static int size() {
    return aggregators.size();
  }
}
