package com.github.cc11001100.weavergirl.runtime;

import com.github.cc11001100.weavergirl.api.event.InterceptorEventPublisher;
import com.github.cc11001100.weavergirl.api.plugin.PluginContext;
import com.github.cc11001100.weavergirl.api.plugin.WeaverPlugin;
import com.github.cc11001100.weavergirl.api.registry.InterceptorRegistry;
import com.github.cc11001100.weavergirl.runtime.metrics.PrometheusExporter;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Serves Prometheus metrics on {@code metricsPort}. Absent that config the plugin does nothing, so
 * shipping the jar costs nothing until the port is set.
 */
public class PrometheusPlugin implements WeaverPlugin {

  private static final Logger log = LoggerFactory.getLogger(PrometheusPlugin.class);

  static final String NAME = "prometheus";

  private PrometheusExporter exporter;

  @Override
  public String name() {
    return NAME;
  }

  @Override
  public boolean isEnabled(PluginContext context) {
    return context.getConfig("metricsPort") != null;
  }

  @Override
  public void init(PluginContext context) {
    // init() runs before isEnabled(). With no metricsPort there is no endpoint to serve.
    if (!isEnabled(context)) {
      return;
    }
    int port = context.getConfigInt("metricsPort", -1);
    if (port < 0) {
      log.warn("Invalid metricsPort value: {}, expected integer", context.getConfig("metricsPort"));
      return;
    }
    try {
      exporter = new PrometheusExporter();
      InterceptorEventPublisher.getInstance().addListener(exporter);
      exporter.start(port);
      log.info("Prometheus metrics endpoint enabled on port {}", port);
    } catch (Exception e) {
      log.warn("Failed to start Prometheus metrics server: {}", e.getMessage());
    }
  }

  @Override
  public void registerInterceptors(InterceptorRegistry registry) {
    // event subscriber only
  }

  @Override
  public void destroy() {
    if (exporter != null) {
      exporter.stop();
    }
  }
}
