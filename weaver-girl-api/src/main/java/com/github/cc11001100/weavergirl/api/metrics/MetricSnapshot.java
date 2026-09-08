package com.github.cc11001100.weavergirl.api.metrics;

import java.util.*;

/**
 * A snapshot of aggregated metrics over a time window.
 *
 * @since 1.2.0
 */
public class MetricSnapshot {

  private final long windowStartMs;
  private final long windowEndMs;
  private final String metricName;
  private final long count;
  private final double sum;
  private final double min;
  private final double max;
  private final double avg;
  private final Map<Double, Double> percentiles;
  private final Map<String, String> labels;

  public MetricSnapshot(
      long windowStartMs,
      long windowEndMs,
      String metricName,
      long count,
      double sum,
      double min,
      double max,
      Map<Double, Double> percentiles) {
    this(
        windowStartMs,
        windowEndMs,
        metricName,
        count,
        sum,
        min,
        max,
        percentiles,
        Collections.emptyMap());
  }

  public MetricSnapshot(
      long windowStartMs,
      long windowEndMs,
      String metricName,
      long count,
      double sum,
      double min,
      double max,
      Map<Double, Double> percentiles,
      Map<String, String> labels) {
    this.windowStartMs = windowStartMs;
    this.windowEndMs = windowEndMs;
    this.metricName = metricName;
    this.count = count;
    this.sum = sum;
    this.min = min;
    this.max = max;
    this.avg = count > 0 ? sum / count : 0;
    this.percentiles =
        percentiles != null
            ? Collections.unmodifiableMap(new LinkedHashMap<>(percentiles))
            : Collections.emptyMap();
    this.labels =
        labels != null
            ? Collections.unmodifiableMap(new LinkedHashMap<>(labels))
            : Collections.emptyMap();
  }

  /** Window start timestamp (epoch millis). */
  public long getWindowStartMs() {
    return windowStartMs;
  }

  /** Window end timestamp (epoch millis). */
  public long getWindowEndMs() {
    return windowEndMs;
  }

  /** Metric name. */
  public String getMetricName() {
    return metricName;
  }

  /** Number of data points in the window. */
  public long getCount() {
    return count;
  }

  /** Sum of all values. */
  public double getSum() {
    return sum;
  }

  /** Minimum value. */
  public double getMin() {
    return min;
  }

  /** Maximum value. */
  public double getMax() {
    return max;
  }

  /** Average value. */
  public double getAvg() {
    return avg;
  }

  /** Metric labels/tags. */
  public Map<String, String> getLabels() {
    return labels;
  }

  /** Percentile values (e.g. 0.5 → p50, 0.99 → p99). */
  public Map<Double, Double> getPercentiles() {
    return percentiles;
  }

  /** Get a specific percentile. */
  public double getPercentile(double p) {
    return percentiles.getOrDefault(p, 0.0);
  }

  /** Rate per second over the window. */
  public double getRatePerSecond() {
    long windowMs = windowEndMs - windowStartMs;
    return windowMs > 0 ? (double) count / (windowMs / 1000.0) : 0;
  }

  @Override
  public String toString() {
    String labelStr = labels.isEmpty() ? "" : labels.toString();
    return String.format(
        "MetricSnapshot{%s%s: count=%d, avg=%.2f, p99=%.2f}",
        metricName, labelStr, count, avg, getPercentile(0.99));
  }
}
