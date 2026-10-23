package com.github.cc11001100.weavergirl.api.observability;

/**
 * Supplies metrics at scrape time.
 *
 * <p>{@link com.github.cc11001100.weavergirl.api.metrics.MetricRegistry} records values as they
 * happen and aggregates them over a sliding window. That covers counters and latency summaries, but
 * not values that only make sense when read — a connection-pool size, a queue depth, a cache hit
 * ratio kept elsewhere. A {@code MetricSource} is pulled whenever a metrics backend renders its
 * output, reads those values, and hands them to the {@link MetricCollector}.
 *
 * <p>Register through {@link com.github.cc11001100.weavergirl.api.plugin.PluginContext#registerMetricSource}
 * (or {@link MetricSources#register}). The Prometheus runtime plugin renders registered sources on
 * every {@code /metrics} scrape. With no metrics backend enabled nothing calls {@link
 * #collect}, so a source costs nothing.
 *
 * <h3>Rules</h3>
 *
 * <ul>
 *   <li>{@link #collect} runs on the scrape thread. It must be fast, read-only and thread-safe, and
 *       it must not block on network or I/O.
 *   <li>Emit the same series with the same label set on every scrape while they exist. A dropped
 *       series looks to Prometheus like a process restart for counters.
 *   <li>The {@code weavergirl_} prefix is reserved for series the agent exports itself; a source
 *       that emits such a name has that family skipped, so it cannot shadow or corrupt an existing
 *       series. Names must otherwise be unique across sources; a later duplicate is ignored.</li>
 *   <li>An exception from {@code collect} is caught and counted, and the remaining sources are
 *       still collected.
 * </ul>
 *
 * @since 1.0.0
 */
public interface MetricSource {

  /**
   * A stable name used in logs and in the error counter's {@code source} label.
   *
   * @return the source name, never null
   */
  String name();

  /**
   * Emit this source's current values.
   *
   * @param collector the collector to report gauges and counters to, never null
   */
  void collect(MetricCollector collector);
}
