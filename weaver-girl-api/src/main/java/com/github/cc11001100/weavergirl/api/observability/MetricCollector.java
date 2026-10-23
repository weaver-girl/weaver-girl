package com.github.cc11001100.weavergirl.api.observability;

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
}
