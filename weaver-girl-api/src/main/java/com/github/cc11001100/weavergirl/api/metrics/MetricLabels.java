package com.github.cc11001100.weavergirl.api.metrics;

import java.util.*;

/**
 * Convenience builder for metric labels/tags.
 *
 * @since 1.1.0
 */
public class MetricLabels {
  private final LinkedHashMap<String, String> labels = new LinkedHashMap<>();

  public MetricLabels label(String key, String value) {
    labels.put(key, value);
    return this;
  }

  public Map<String, String> build() {
    return Collections.unmodifiableMap(new LinkedHashMap<>(labels));
  }

  public static MetricLabels of(String key, String value) {
    return new MetricLabels().label(key, value);
  }

  public static MetricLabels of(String k1, String v1, String k2, String v2) {
    return new MetricLabels().label(k1, v1).label(k2, v2);
  }

  public static Map<String, String> empty() {
    return Collections.emptyMap();
  }
}
