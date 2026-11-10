package com.github.cc11001100.weavergirl.api.observability;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * One metric sample flowing through the governance pipeline, as emitted by a {@link MetricSource}.
 *
 * <p>A point carries everything a {@link MetricProcessor} may want to change: the metric name,
 * help text, type, label set and value — plus the bucket arrays for a histogram. Points are
 * immutable; a processor returns a new point built with {@link #toBuilder()} (or by reconstructing
 * with the static factories) instead of mutating the input. The type of the returned point may
 * differ from the input, though collectors are free to restrict which types they accept.
 *
 * <p>The pipeline lets a plugin govern what the agent exposes at scrape time: drop a noisy
 * series, rename a series, strip a high-cardinality label, or cap an unbounded value. Because a
 * point is the smallest unit the collector renders, a processor sees every sample of every
 * {@code MetricSource} and decides about that sample alone.
 *
 * @since 1.3.0
 */
public final class MetricPoint {

  /** The metric type, matching what {@link MetricCollector} reports. */
  public enum Type {
    GAUGE,
    COUNTER,
    HISTOGRAM
  }

  private final String name;
  private final String help;
  private final Type type;
  private final Map<String, String> labels;
  private final double value;
  private final double[] upperBounds;
  private final long[] cumulativeCounts;
  private final double sum;

  private MetricPoint(Builder builder) {
    this.name = builder.name;
    this.help = builder.help;
    this.type = builder.type;
    Map<String, String> labels = new LinkedHashMap<>();
    if (builder.labels != null) {
      for (Map.Entry<String, String> entry : builder.labels.entrySet()) {
        String key = entry.getKey();
        String value = entry.getValue();
        if (key != null && value != null) {
          labels.put(key, value);
        }
      }
    }
    this.labels = Collections.unmodifiableMap(labels);
    this.value = builder.value;
    this.upperBounds = builder.upperBounds != null ? builder.upperBounds.clone() : null;
    this.cumulativeCounts =
        builder.cumulativeCounts != null ? builder.cumulativeCounts.clone() : null;
    this.sum = builder.sum;
  }

  /** The metric name, as the source reported it. May be null or blank for a dropped sample. */
  public String name() {
    return name;
  }

  /** The help text, or null. */
  public String help() {
    return help;
  }

  /** The metric type. */
  public Type type() {
    return type;
  }

  /** The label set, an immutable snapshot with null keys and values removed. Never null. */
  public Map<String, String> labels() {
    return labels;
  }

  /** The gauge or counter value. Meaningless for a histogram; use {@link #sum()} then. */
  public double value() {
    return value;
  }

  /** Whether this point describes a histogram. */
  public boolean isHistogram() {
    return type == Type.HISTOGRAM;
  }

  /**
   * The histogram's bucket upper bounds, parallel to {@link #cumulativeCounts()}, or null for a
   * non-histogram.
   */
  public double[] upperBounds() {
    return upperBounds != null ? upperBounds.clone() : null;
  }

  /**
   * The histogram's cumulative counts, ending with the total observation count, or null for a
   * non-histogram.
   */
  public long[] cumulativeCounts() {
    return cumulativeCounts != null ? cumulativeCounts.clone() : null;
  }

  /** The histogram's sum of observed values, or 0 for a non-histogram. */
  public double sum() {
    return sum;
  }

  /** A builder initialised from this point, for processors that change one field. */
  public Builder toBuilder() {
    return new Builder()
        .name(name)
        .help(help)
        .type(type)
        .labels(labels)
        .value(value)
        .histogram(upperBounds, cumulativeCounts, sum);
  }

  /** A gauge point. */
  public static MetricPoint gauge(String name, String help, Map<String, String> labels, double value) {
    return new Builder().name(name).help(help).type(Type.GAUGE).labels(labels).value(value).build();
  }

  /** A counter point. */
  public static MetricPoint counter(
      String name, String help, Map<String, String> labels, double value) {
    return new Builder().name(name).help(help).type(Type.COUNTER).labels(labels).value(value).build();
  }

  /** A histogram point. */
  public static MetricPoint histogram(
      String name,
      String help,
      Map<String, String> labels,
      double[] upperBounds,
      long[] cumulativeCounts,
      double sum) {
    return new Builder()
        .name(name)
        .help(help)
        .type(Type.HISTOGRAM)
        .labels(labels)
        .histogram(upperBounds, cumulativeCounts, sum)
        .build();
  }

  /** Builder for {@link MetricPoint}. */
  public static final class Builder {
    private String name;
    private String help;
    private Type type;
    private Map<String, String> labels;
    private double value;
    private double[] upperBounds;
    private long[] cumulativeCounts;
    private double sum;

    public Builder name(String name) {
      this.name = name;
      return this;
    }

    public Builder help(String help) {
      this.help = help;
      return this;
    }

    public Builder type(Type type) {
      this.type = type;
      return this;
    }

    public Builder labels(Map<String, String> labels) {
      this.labels = labels;
      return this;
    }

    public Builder value(double value) {
      this.value = value;
      return this;
    }

    public Builder histogram(double[] upperBounds, long[] cumulativeCounts, double sum) {
      this.upperBounds = upperBounds;
      this.cumulativeCounts = cumulativeCounts;
      this.sum = sum;
      return this;
    }

    public MetricPoint build() {
      return new MetricPoint(this);
    }
  }
}