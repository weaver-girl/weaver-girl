package com.github.cc11001100.weavergirl.core.exporter;

import com.github.cc11001100.weavergirl.api.event.InterceptorEvent;
import com.github.cc11001100.weavergirl.api.exporter.DataExporter;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Built-in exporter that logs events in structured JSON format.
 *
 * <p>Outputs interceptor events to SLF4J logger as structured JSON strings, suitable for log
 * aggregation systems (ELK, Splunk, CloudWatch).
 *
 * @since 1.2.0
 */
public class LoggingExporter implements DataExporter {

  private static final Logger log =
      LoggerFactory.getLogger("com.github.cc11001100.weavergirl.exporter.logging");

  private volatile boolean includeAttributes = true;
  private volatile long exportCount = 0;

  @Override
  public String name() {
    return "logging";
  }

  @Override
  public void export(InterceptorEvent event) {
    StringBuilder json = new StringBuilder(256);
    json.append("{");
    appendField(json, "type", event.getType());
    json.append(",");
    appendField(json, "plugin", event.getPlugin());
    json.append(",");
    appendField(json, "class", event.getClassName());
    json.append(",");
    appendField(json, "method", event.getMethodName());
    json.append(",");
    json.append("\"timestamp\":").append(event.getTimestamp());

    if (event.getDurationMs() > 0) {
      json.append(",");
      json.append("\"durationMs\":").append(event.getDurationMs());
    }

    if (includeAttributes && event.getAttributes() != null && !event.getAttributes().isEmpty()) {
      json.append(",\"attributes\":{");
      boolean first = true;
      for (Map.Entry<String, String> entry : event.getAttributes().entrySet()) {
        if (!first) json.append(",");
        appendField(json, entry.getKey(), String.valueOf(entry.getValue()));
        first = false;
      }
      json.append("}");
    }

    json.append("}");

    log.info(json.toString());
    exportCount++;
  }

  @Override
  public void init(Map<String, String> config) {
    if (config != null) {
      String val = config.get("includeAttributes");
      if (val != null) {
        includeAttributes = Boolean.parseBoolean(val);
      }
    }
  }

  @Override
  public boolean isHealthy() {
    return true;
  }

  /** Get total exported event count. */
  public long getExportCount() {
    return exportCount;
  }

  private void appendField(StringBuilder sb, String key, String value) {
    sb.append("\"").append(key).append("\":");
    if (value == null) {
      sb.append("null");
    } else {
      sb.append("\"").append(escapeJson(value)).append("\"");
    }
  }

  private String escapeJson(String value) {
    return value
        .replace("\\", "\\\\")
        .replace("\"", "\\\"")
        .replace("\n", "\\n")
        .replace("\r", "\\r")
        .replace("\t", "\\t");
  }
}
