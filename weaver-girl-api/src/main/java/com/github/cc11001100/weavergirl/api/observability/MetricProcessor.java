package com.github.cc11001100.weavergirl.api.observability;

/**
 * Governs one metric sample at scrape time, before the agent renders it for a consumer.
 *
 * <p>A processor sees every sample of every registered {@link MetricSource}. It returns the sample
 * unchanged or modified, or {@code null} to drop it. This is the hook for metric hygiene the agent
 * cannot decide for you: dropping a noisy series, renaming a series your backend expects, stripping
 * a high-cardinality label, or capping an unbounded value.
 *
 * <p>Processors run in {@link MetricProcessor#priority()} order (ascending) inside the collector a
 * scrape drives, so they never touch the application's thread. A processor that throws is skipped
 * and counted; the sample is kept as it was, so one broken processor cannot silence another.
 *
 * <p>Register through {@link com.github.cc11001100.weavergirl.api.plugin.PluginContext#registerMetricProcessor}
 * so the processor is withdrawn when the plugin is unloaded; registering here directly is possible
 * but then the plugin must unregister it itself.
 *
 * @since 1.3.0
 */
public interface MetricProcessor {

  /**
   * A name for this processor, used in stats and the performance tracker.
   *
   * @return the name, trimmed; null or blank is reported as {@code "unknown"}
   */
  String name();

  /**
   * Execution priority; lower values run first.
   *
   * @return the priority, default 0
   */
  default int priority() {
    return 0;
  }

  /**
   * Decide about one metric sample.
   *
   * @param point the sample as the source reported it, never null
   * @return the sample to render (the same object, or a modified copy), or null to drop it
   */
  MetricPoint process(MetricPoint point);
}