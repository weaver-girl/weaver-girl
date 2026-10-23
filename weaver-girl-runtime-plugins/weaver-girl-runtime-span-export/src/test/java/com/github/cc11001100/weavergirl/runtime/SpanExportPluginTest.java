package com.github.cc11001100.weavergirl.runtime;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.github.cc11001100.weavergirl.api.plugin.PluginContext;
import com.github.cc11001100.weavergirl.api.tracing.SpanContext;
import com.github.cc11001100.weavergirl.api.tracing.Tracer;
import com.github.cc11001100.weavergirl.core.exporter.SpanExport;
import java.util.Collections;
import java.util.HashMap;
import java.util.Map;
import org.junit.jupiter.api.Test;

/** The span-export capability switches on only when an endpoint or {@code spanExport=true} is set. */
class SpanExportPluginTest {

  @Test
  void disabledByDefault() {
    assertFalse(new SpanExportPlugin().isEnabled(context(Collections.emptyMap())));
  }

  @Test
  void disabledWhenEndpointBlank() {
    assertFalse(new SpanExportPlugin().isEnabled(context(config("otlpEndpoint", "  "))));
  }

  @Test
  void enabledWhenEndpointSet() {
    SpanExportPlugin plugin = new SpanExportPlugin();
    PluginContext ctx = context(config("otlpEndpoint", "http://127.0.0.1:9/v1/traces"));

    assertTrue(plugin.isEnabled(ctx));
    plugin.init(ctx);
    plugin.destroy();
  }

  @Test
  void enabledWhenOnlyTheDedicatedTracesEndpointIsSet() {
    SpanExportPlugin plugin = new SpanExportPlugin();
    PluginContext ctx =
        context(config("otlpTracesEndpoint", "http://127.0.0.1:9/v1/traces"));

    assertTrue(
        plugin.isEnabled(ctx),
        "a dedicated traces endpoint is enough; it used to be ignored unless spanExport was set");
    plugin.init(ctx);
    plugin.destroy();
  }

  @Test
  void enabledForLogOnlyExport() {
    assertTrue(new SpanExportPlugin().isEnabled(context(config("spanExport", "true"))));
  }

  @Test
  void completedSpanIsExported() {
    SpanExport export = new SpanExport();
    Map<String, String> config = config("spanExport", "true");
    config.put("spanExportBatchSize", "1");
    config.put("spanExportIntervalMs", "60000");
    assertTrue(export.start(config));
    try {
Tracer.startSpan();
      Tracer.endSpan("query", null);

      assertEquals(1, export.exporter().flush());
      assertEquals(1, export.exporter().getExportedCount());
    } finally {
      export.stop();
    }
  }

  private static Map<String, String> config(String key, String value) {
    Map<String, String> config = new HashMap<>();
    config.put(key, value);
    return config;
  }

  private static PluginContext context(Map<String, String> config) {
    return new com.github.cc11001100.weavergirl.core.plugin.DefaultPluginContext(
        new com.github.cc11001100.weavergirl.core.registry.DefaultInterceptorRegistry(),
        config,
        SpanExportPlugin.NAME);
  }
}
