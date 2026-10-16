package com.github.cc11001100.weavergirl.core.exporter;

import com.github.cc11001100.weavergirl.api.tracing.Tracer;
import com.github.cc11001100.weavergirl.core.trace.TracerSpanExporterBridge;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Assembles the span batch exporter from configuration and owns its lifecycle.
 *
 * <p>Nothing is created unless {@code otlpEndpoint} or {@code spanExport=true} is set, so the
 * default configuration costs no background thread. {@code otlpEndpoint} sends spans to a collector
 * as OTLP/HTTP JSON; {@code spanExport=true} without an endpoint logs them instead.
 *
 * <p>Optional tuning keys: {@code spanExportBatchSize} (default 100), {@code spanExportIntervalMs}
 * (default 5000), {@code spanExportBufferSize} (default 10000), {@code otlpHeaders} (semicolon
 * separated {@code name=value} pairs).
 */
public final class SpanExport {

  private static final Logger log = LoggerFactory.getLogger(SpanExport.class);

  private SpanExporter exporter;
  private TracerSpanExporterBridge bridge;

  /**
   * Wire span export for this configuration. Returns false, creating nothing, when neither {@code
   * otlpEndpoint} nor {@code spanExport=true} is present.
   */
  public boolean start(Map<String, String> config) {
    if (config == null) {
      return false;
    }
    String endpoint = config.get("otlpEndpoint");
    boolean hasEndpoint = endpoint != null && !endpoint.trim().isEmpty();
    boolean logOnly = "true".equalsIgnoreCase(config.get("spanExport"));
    if (!hasEndpoint && !logOnly) {
      return false;
    }
    try {
      SpanExporter created =
          new SpanExporter(
              parsePositiveInt(config.get("spanExportBatchSize"), 100),
              parsePositiveLong(config.get("spanExportIntervalMs"), 5_000L),
              parsePositiveInt(config.get("spanExportBufferSize"), 10_000));
      if (hasEndpoint) {
        OtlpHttpExporter.Builder builder = OtlpHttpExporter.builder().endpoint(endpoint.trim());
        String headers = config.get("otlpHeaders");
        if (headers != null && !headers.trim().isEmpty()) {
          for (String pair : headers.split(";")) {
            int eq = pair.indexOf('=');
            if (eq > 0) {
              builder.header(pair.substring(0, eq).trim(), pair.substring(eq + 1).trim());
            }
          }
        }
        created.addFormatter(builder.build());
        log.info("Span export: OTLP HTTP -> {}", endpoint.trim());
      } else {
        created.addFormatter(new SpanFormatter.LoggingSpanFormatter());
        log.info("Span export: log-only (no otlpEndpoint configured)");
      }
      created.start();
      bridge = new TracerSpanExporterBridge(created);
      // Additive, so it coexists with other consumers such as the topology bridge.
      Tracer.addCompletionListener(bridge);
      exporter = created;
      return true;
    } catch (Exception e) {
      log.warn("Failed to initialize span export: {}", e.getMessage());
      return false;
    }
  }

  /** The running exporter, or null when {@link #start} did not enable one. */
  public SpanExporter exporter() {
    return exporter;
  }

  /** Detach the bridge and stop the exporter. Safe to call when nothing was started. */
  public void stop() {
    if (bridge != null) {
      try {
        Tracer.removeCompletionListener(bridge);
      } catch (Exception e) {
        log.warn("Failed to detach span export bridge: {}", e.getMessage());
      }
      bridge = null;
    }
    if (exporter != null) {
      try {
        exporter.stop();
      } catch (Exception e) {
        log.warn("Failed to stop span exporter: {}", e.getMessage());
      }
      exporter = null;
    }
  }

  public static int parsePositiveInt(String value, int defaultValue) {
    if (value == null) {
      return defaultValue;
    }
    try {
      int parsed = Integer.parseInt(value.trim());
      return parsed > 0 ? parsed : defaultValue;
    } catch (NumberFormatException e) {
      return defaultValue;
    }
  }

  public static long parsePositiveLong(String value, long defaultValue) {
    if (value == null) {
      return defaultValue;
    }
    try {
      long parsed = Long.parseLong(value.trim());
      return parsed > 0 ? parsed : defaultValue;
    } catch (NumberFormatException e) {
      return defaultValue;
    }
  }
}
