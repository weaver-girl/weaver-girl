package com.github.cc11001100.weavergirl.api.plugin;

import com.github.cc11001100.weavergirl.api.plugin.WeaverPlugin;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.LongAdder;

/**
 * Tracks performance metrics for plugins at runtime.
 *
 * <p>Records interceptor invocation counts, error counts, and timing statistics per plugin.
 * These metrics are used by PluginManager to populate PluginInfo and by PrometheusExporter
 * to expose plugin-level performance metrics.
 *
 * @since 1.2.0
 */
public final class PluginPerformanceTracker {

  private static final ConcurrentMap<String, PluginMetrics> metricsMap =
      new ConcurrentHashMap<>();

  private PluginPerformanceTracker() {}

  /**
   * Record an interceptor invocation for a plugin.
   *
   * @param plugin the plugin that owns the interceptor
   * @param durationMs callback duration in milliseconds
   * @param error whether the callback threw an exception
   */
  public static void recordInvocation(WeaverPlugin plugin, long durationMs, boolean error) {
    if (plugin == null) {
      return;
    }
    recordInvocationByName(plugin.name(), durationMs, error);
  }

  /**
   * Record an interceptor invocation for a plugin by name.
   *
   * @param pluginName the plugin name
   * @param durationMs callback duration in milliseconds
   * @param error whether the callback threw an exception
   */
  public static void recordInvocationByName(String pluginName, long durationMs, boolean error) {
    if (pluginName == null || pluginName.isEmpty()) {
      return;
    }
    PluginMetrics metrics = metricsMap.computeIfAbsent(pluginName, k -> new PluginMetrics());
    metrics.recordInvocation(durationMs, error);
  }

  /**
   * Get performance metrics for a plugin.
   *
   * @param pluginName the plugin name
   * @return metrics snapshot, or null if not found
   */
  public static PluginMetricsSnapshot getMetrics(String pluginName) {
    PluginMetrics metrics = metricsMap.get(pluginName);
    return metrics != null ? metrics.snapshot() : null;
  }

  /**
   * Get all metrics.
   *
   * @return unmodifiable snapshot of all plugin metrics
   */
  public static ConcurrentMap<String, PluginMetricsSnapshot> getAllMetrics() {
    ConcurrentMap<String, PluginMetricsSnapshot> snapshot = new ConcurrentHashMap<>();
    for (java.util.Map.Entry<String, PluginMetrics> entry : metricsMap.entrySet()) {
      snapshot.put(entry.getKey(), entry.getValue().snapshot());
    }
    return snapshot;
  }

  /**
   * Clear all metrics. Used in tests.
   */
  public static void clear() {
    metricsMap.clear();
  }

  /**
   * Remove metrics for a specific plugin. Called when a plugin is unloaded.
   *
   * @param pluginName the plugin name
   */
  public static void removeMetrics(String pluginName) {
    metricsMap.remove(pluginName);
  }

  /**
   * Thread-safe metrics container for a single plugin.
   */
  static final class PluginMetrics {
    private final LongAdder invocationCount = new LongAdder();
    private final LongAdder errorCount = new LongAdder();
    private final LongAdder totalDurationMs = new LongAdder();
    private final AtomicLong lastInvocationAt = new AtomicLong(0);
    private final AtomicLong lastErrorAt = new AtomicLong(0);

    void recordInvocation(long durationMs, boolean error) {
      invocationCount.increment();
      totalDurationMs.add(durationMs);
      lastInvocationAt.set(System.currentTimeMillis());
      if (error) {
        errorCount.increment();
        lastErrorAt.set(System.currentTimeMillis());
      }
    }

    PluginMetricsSnapshot snapshot() {
      long invocations = invocationCount.sum();
      long errors = errorCount.sum();
      long totalDuration = totalDurationMs.sum();
      double avgDuration = invocations > 0 ? (double) totalDuration / invocations : 0.0;
      return new PluginMetricsSnapshot(
          invocations,
          errors,
          avgDuration,
          lastInvocationAt.get(),
          lastErrorAt.get());
    }
  }

  /**
   * Immutable snapshot of plugin performance metrics.
   */
  public static final class PluginMetricsSnapshot {
    private final long invocationCount;
    private final long errorCount;
    private final double avgDurationMs;
    private final long lastInvocationAt;
    private final long lastErrorAt;

    PluginMetricsSnapshot(
        long invocationCount,
        long errorCount,
        double avgDurationMs,
        long lastInvocationAt,
        long lastErrorAt) {
      this.invocationCount = invocationCount;
      this.errorCount = errorCount;
      this.avgDurationMs = avgDurationMs;
      this.lastInvocationAt = lastInvocationAt;
      this.lastErrorAt = lastErrorAt;
    }

    public long getInvocationCount() {
      return invocationCount;
    }

    public long getErrorCount() {
      return errorCount;
    }

    public double getAvgDurationMs() {
      return avgDurationMs;
    }

    public long getLastInvocationAt() {
      return lastInvocationAt;
    }

    public long getLastErrorAt() {
      return lastErrorAt;
    }

    public double getErrorRate() {
      return invocationCount > 0 ? (double) errorCount / invocationCount : 0.0;
    }
  }
}
