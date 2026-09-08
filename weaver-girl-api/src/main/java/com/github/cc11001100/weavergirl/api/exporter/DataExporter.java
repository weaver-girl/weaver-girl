package com.github.cc11001100.weavergirl.api.exporter;

import com.github.cc11001100.weavergirl.api.event.InterceptorEvent;

/**
 * SPI interface for exporting intercepted data to external systems.
 *
 * <p>Implementations can send data to various backends: files, OTLP collectors, Zipkin, Jaeger,
 * custom HTTP endpoints, etc.
 *
 * <h3>Usage:</h3>
 *
 * <pre>
 * DataExporter exporter = new MyCustomExporter();
 * ExporterRegistry.register(exporter);
 * ExporterRegistry.setActive("my-exporter");
 * </pre>
 *
 * @since 1.2.0
 */
public interface DataExporter {

  /**
   * Unique name for this exporter.
   *
   * @return the exporter name (e.g. "logging", "otlp", "zipkin")
   */
  String name();

  /**
   * Export an interceptor event.
   *
   * <p>Called for every intercepted event. Implementations should be fast and non-blocking.
   *
   * @param event the event to export
   */
  void export(InterceptorEvent event);

  /** Flush any buffered data. */
  default void flush() {
    // Default: no-op
  }

  /**
   * Initialize the exporter with configuration.
   *
   * @param config exporter-specific configuration
   */
  default void init(java.util.Map<String, String> config) {
    // Default: no-op
  }

  /** Shut down the exporter and release resources. */
  default void shutdown() {
    // Default: no-op
  }

  /**
   * Check if this exporter is healthy and operational.
   *
   * @return true if the exporter is functioning normally
   */
  default boolean isHealthy() {
    return true;
  }
}
