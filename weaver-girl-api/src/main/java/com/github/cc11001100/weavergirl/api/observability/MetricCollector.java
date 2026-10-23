package com.github.cc11001100.weavergirl.api.observability;

import java.util.Collections;
import java.util.Map;

/**
 * Receives the values one {@link MetricSource} emits during a scrape.
 *
 * <p>Every method takes the metric name, an optional help text shown by the backend, and the
 * current value. The labeled overloads additionally attach a label set. Null or blank names are
 * ignored by implementations, as are label entries with a null key or value.
 *
 * @since 1.0.0
 */
public interface MetricCollector {

  /** Report a gauge, for example a pool size or a queue depth. */
  void gauge(String name, String help, double value);

  /** Report a gauge with labels. */
  void gauge(String name, String help, Map<String, String> labels, double value);

  /** Report a counter, for example requests handled since start. */
  void counter(String name, String help, double value);

  /** Report a counter with labels. */
  void counter(String name, String help, Map<String, String> labels, double value);

  /** Report a histogram without labels; see the labeled overload for the meaning of the arrays. */
  default void histogram(
      String name,
      String help,
      double[] upperBounds,
      long[] cumulativeCounts,
      double sum) {
    histogram(
        name,
        help,
        Collections.<String, String>emptyMap(),
        upperBounds,
        cumulativeCounts,
        sum);
  }

  /**
   * Report a histogram, for a distribution that already exists in your own metrics library.
   *
   * <p>The two arrays are parallel and describe the same observation set:
   *
   * <ul>
   *   <li>{@code upperBounds} — ascending, finite bucket upper bounds, e.g. {@code {0.005, 0.01,
   *       0.1}}; the implicit {@code +Inf} bucket is added by the collector.
   *   <li>{@code cumulativeCounts} — {@code cumulativeCounts[i]} is the number of observations
   *       {@code <= upperBounds[i]}, so the array is non-decreasing and its last element is the
   *       total count. If your recorder stores per-bucket (non-cumulative) counts, accumulate them
   *       before calling.
   * </ul>
   *
   * <p>Malformed input (mismatched lengths, non-ascending bounds, decreasing counts) is ignored
   * rather than rendered as an exposition Prometheus would reject. Collectors that cannot render
   * histograms ignore the call; the Prometheus exporter renders {@code _bucket} (including {@code
   * le="+Inf"}), {@code _sum} and {@code _count}.
   *
   * @param name metric name; a Prometheus exporter appends {@code _bucket}/{@code _sum}/{@code
   *     _count} itself
   * @param help help text, or null
   * @param labels label set for this distribution, or null
   * @param upperBounds ascending finite bucket upper bounds
   * @param cumulativeCounts observation counts up to each bound, ending with the total count
   * @param sum sum of all observed values
   */
  default void histogram(
      String name,
      String help,
      Map<String, String> labels,
      double[] upperBounds,
      long[] cumulativeCounts,
      double sum) {
    // Collectors that cannot render histograms ignore them rather than failing the whole scrape.
  }
}
