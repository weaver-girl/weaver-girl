package com.github.cc11001100.weavergirl.api.observability;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * Registry of {@link MetricSource}s.
 *
 * <p>Sources are collected in registration order. Register during plugin initialization through
 * {@link com.github.cc11001100.weavergirl.api.plugin.PluginContext#registerMetricSource}, which
 * withdraws the source automatically when the plugin is unloaded. Registering the same instance
 * twice is a no-op.
 *
 * @since 1.0.0
 */
public final class MetricSources {

  private static final List<MetricSource> sources = new CopyOnWriteArrayList<>();

  private MetricSources() {}

  /** Register a source. */
  public static void register(MetricSource source) {
    if (source != null && !sources.contains(source)) {
      sources.add(source);
    }
  }

  /** Remove a source. */
  public static void unregister(MetricSource source) {
    if (source != null) {
      sources.remove(source);
    }
  }

  /** The registered sources, in collection order. */
  public static List<MetricSource> getSources() {
    return Collections.unmodifiableList(new ArrayList<>(sources));
  }

  /** Remove every source. Tests use this so one test does not see another's metrics. */
  public static void clear() {
    sources.clear();
  }
}
