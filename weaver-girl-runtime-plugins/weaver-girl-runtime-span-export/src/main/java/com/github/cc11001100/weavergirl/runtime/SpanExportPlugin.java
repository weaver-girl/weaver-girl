package com.github.cc11001100.weavergirl.runtime;

import com.github.cc11001100.weavergirl.api.plugin.PluginContext;
import com.github.cc11001100.weavergirl.api.plugin.WeaverPlugin;
import com.github.cc11001100.weavergirl.api.registry.InterceptorRegistry;
import com.github.cc11001100.weavergirl.core.exporter.SpanExport;

/**
 * Exports completed trace spans. Enabled with {@code otlpEndpoint}, which sends them to a collector
 * as OTLP/HTTP JSON, or with {@code spanExport=true}, which logs them.
 *
 * <p>This registers no interceptors. It listens for spans the tracer finishes, and only when one of
 * those keys is set, so shipping the jar costs nothing until then.
 */
public class SpanExportPlugin implements WeaverPlugin {

  static final String NAME = "span-export";

  private SpanExport spanExport;

  @Override
  public String name() {
    return NAME;
  }

  @Override
  public boolean isEnabled(PluginContext context) {
    String endpoint = context.getConfig("otlpEndpoint");
    return (endpoint != null && !endpoint.trim().isEmpty())
        || context.getConfigBoolean("spanExport", false);
  }

  @Override
  public void init(PluginContext context) {
    // init() runs before isEnabled(). With neither key set there is nothing to export.
    if (!isEnabled(context)) {
      return;
    }
    spanExport = new SpanExport();
    spanExport.start(context.getAllConfig());
  }

  @Override
  public void registerInterceptors(InterceptorRegistry registry) {
    // span listener only
  }

  @Override
  public void destroy() {
    if (spanExport != null) {
      spanExport.stop();
    }
  }
}
