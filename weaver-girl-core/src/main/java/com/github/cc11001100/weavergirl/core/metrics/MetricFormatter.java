package com.github.cc11001100.weavergirl.core.metrics;

import java.util.Map;

/** Interface for formatting metric snapshots into different output formats. */
public interface MetricFormatter {

  /**
   * Format and output a metric snapshot.
   *
   * @param snapshot the metric snapshot to format
   */
  void format(MetricSnapshot snapshot);

  /** JSON formatter — outputs structured JSON to SLF4J. */
  class JsonFormatter implements MetricFormatter {
    private static final org.slf4j.Logger log =
        org.slf4j.LoggerFactory.getLogger(JsonFormatter.class);

    @Override
    public void format(MetricSnapshot snapshot) {
      log.info("[Metrics] {}", snapshot.toJson());
    }
  }

  /** Log formatter — outputs human-readable lines to SLF4J. */
  class LogFormatter implements MetricFormatter {
    private static final org.slf4j.Logger log =
        org.slf4j.LoggerFactory.getLogger(LogFormatter.class);

    @Override
    public void format(MetricSnapshot snapshot) {
      StringBuilder sb = new StringBuilder();
      sb.append("[Metrics Report] ");
      for (Map.Entry<String, Object> entry : snapshot.getValues().entrySet()) {
        sb.append(entry.getKey()).append("=").append(entry.getValue()).append(" ");
      }
      log.info(sb.toString().trim());
    }
  }
}
