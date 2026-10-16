package com.github.cc11001100.weavergirl.runtime;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.github.cc11001100.weavergirl.api.event.InterceptorEvent;
import com.github.cc11001100.weavergirl.api.event.InterceptorEventPublisher;
import com.github.cc11001100.weavergirl.api.plugin.PluginContext;
import com.github.cc11001100.weavergirl.api.registry.InterceptorRegistry;
import java.util.Collections;
import java.util.HashMap;
import java.util.Map;
import org.junit.jupiter.api.Test;

/** The otlp capability switches on only when {@code otlpEndpoint} is set. */
class OtlpPluginTest {

  @Test
  void disabledByDefault() {
    assertFalse(new OtlpPlugin().isEnabled(context(Collections.emptyMap())));
  }

  @Test
  void disabledWhenEndpointBlank() {
    assertFalse(new OtlpPlugin().isEnabled(context(config("otlpEndpoint", "  "))));
  }

  @Test
  void enabledWhenEndpointSet() {
    OtlpPlugin plugin = new OtlpPlugin();
    PluginContext ctx = context(config("otlpEndpoint", "http://127.0.0.1:9/v1/logs"));

    assertTrue(plugin.isEnabled(ctx));

    // init only subscribes a listener. Count before and after so the assertion does not depend on
    // whatever else the suite already registered on the global publisher.
    InterceptorEventPublisher publisher = InterceptorEventPublisher.getInstance();
    int before = publisher.getListenerCount();
    plugin.init(ctx);
    assertEquals(before + 1, publisher.getListenerCount());
    plugin.destroy();
    assertEquals(before, publisher.getListenerCount());
  }

  @Test
  void requestEncodesPluginTypeAndDuration() {
    InterceptorEvent event =
        InterceptorEvent.builder()
            .type("slow-query")
            .plugin("jdbc")
            .className("PgStatement")
            .methodName("execute")
            .timestamp(1000L)
            .durationMs(2500L)
            .attribute("sql", "select \"a\"")
            .build();

    String request = OtlpEventListener.toRequest(event);

    assertTrue(request.contains("\"severityText\":\"INFO\""));
    assertTrue(request.contains("\"timeUnixNano\":\"1000000000\""));
    assertTrue(request.contains("jdbc slow-query PgStatement.execute 2500ms"));
    assertTrue(request.contains("\"key\":\"weavergirl.plugin\""));
    assertTrue(request.contains("select \\\"a\\\""));
  }

  @Test
  void errorTypeMapsToErrorSeverity() {
    InterceptorEvent event =
        InterceptorEvent.builder().type("jdbc-error").plugin("jdbc").timestamp(0L).build();

    assertTrue(OtlpEventListener.toRequest(event).contains("\"severityText\":\"ERROR\""));
  }

  private static Map<String, String> config(String key, String value) {
    Map<String, String> config = new HashMap<>();
    config.put(key, value);
    return config;
  }

  private static PluginContext context(Map<String, String> config) {
    return new com.github.cc11001100.weavergirl.core.plugin.DefaultPluginContext(
        registry(), config, OtlpPlugin.NAME);
  }

  private static InterceptorRegistry registry() {
    return new com.github.cc11001100.weavergirl.core.registry.DefaultInterceptorRegistry();
  }
}
