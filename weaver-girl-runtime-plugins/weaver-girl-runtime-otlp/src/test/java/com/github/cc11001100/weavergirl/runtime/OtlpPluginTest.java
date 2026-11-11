package com.github.cc11001100.weavergirl.runtime;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.github.cc11001100.weavergirl.api.event.InterceptorEvent;
import com.github.cc11001100.weavergirl.api.event.InterceptorEventPublisher;
import com.github.cc11001100.weavergirl.api.plugin.PluginContext;
import com.github.cc11001100.weavergirl.api.registry.InterceptorRegistry;
import java.nio.charset.StandardCharsets;
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
  void logsEndpointWinsOverTheLegacyEndpoint() {
    OtlpPlugin plugin = new OtlpPlugin();
    Map<String, String> config = config("otlpEndpoint", "http://collector:4318/v1/traces");
    config.put("otlpLogsEndpoint", "http://collector:4318/v1/logs");

    assertTrue(plugin.isEnabled(context(config)));
    assertEquals(
        "http://collector:4318/v1/logs", OtlpPlugin.resolveLogsEndpoint(context(config)));
  }

  /** Spans own otlpEndpoint; posting log records to /v1/traces is rejected everywhere. */
  @Test
  void aTracesEndpointDoesNotTurnOnLogExport() {
    OtlpPlugin plugin = new OtlpPlugin();
    PluginContext ctx = context(config("otlpEndpoint", "http://collector:4318/v1/traces"));

    assertFalse(plugin.isEnabled(ctx));
    assertNull(OtlpPlugin.resolveLogsEndpoint(ctx));
  }

  @Test
  void blankLogsEndpointFallsBackToTheLegacyOne() {
    Map<String, String> config = config("otlpEndpoint", "http://collector:4318/v1/logs");
    config.put("otlpLogsEndpoint", "   ");

    assertEquals(
        "http://collector:4318/v1/logs", OtlpPlugin.resolveLogsEndpoint(context(config)));
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

  @Test
  void postsEventsToTheConfiguredEndpoint() throws Exception {
    java.util.concurrent.atomic.AtomicReference<String> received =
        new java.util.concurrent.atomic.AtomicReference<>();
    java.util.concurrent.CountDownLatch delivered = new java.util.concurrent.CountDownLatch(1);
    com.sun.net.httpserver.HttpServer server =
        com.sun.net.httpserver.HttpServer.create(new java.net.InetSocketAddress(0), 0);
    server.createContext(
        "/v1/logs",
        exchange -> {
          java.io.ByteArrayOutputStream out = new java.io.ByteArrayOutputStream();
          java.io.InputStream in = exchange.getRequestBody();
          byte[] buffer = new byte[256];
          int n;
          while ((n = in.read(buffer)) != -1) {
            out.write(buffer, 0, n);
          }
          received.set(new String(out.toByteArray(), StandardCharsets.UTF_8));
          exchange.sendResponseHeaders(200, -1);
          exchange.close();
          delivered.countDown();
        });
    server.start();
    int port = server.getAddress().getPort();

    OtlpPlugin plugin = new OtlpPlugin();
    try {
      plugin.init(
          context(config("otlpLogsEndpoint", "http://127.0.0.1:" + port + "/v1/logs")));
      InterceptorEventPublisher.getInstance()
          .publish(
              InterceptorEvent.builder()
                  .type("slow-query")
                  .plugin("jdbc")
                  .className("PgStatement")
                  .methodName("execute")
                  .timestamp(1L)
                  .durationMs(5L)
                  .build());

      assertTrue(delivered.await(5, java.util.concurrent.TimeUnit.SECONDS), "event not posted");
      assertTrue(received.get().contains("\"key\":\"weavergirl.plugin\""), received.get());
      assertTrue(received.get().contains("jdbc slow-query PgStatement.execute 5ms"), received.get());
    } finally {
      plugin.destroy();
      server.stop(0);
    }
  }

  @Test
  void deadEndpointNeverThrowsIntoThePublisher() {
    OtlpPlugin plugin = new OtlpPlugin();
    try {
      plugin.init(context(config("otlpLogsEndpoint", "http://127.0.0.1:1/v1/logs")));
      InterceptorEventPublisher.getInstance()
          .publish(InterceptorEvent.builder().type("x").plugin("p").timestamp(0L).build());
    } finally {
      // shutdown waits for the failed post, so the warning path runs before the test ends.
      plugin.destroy();
    }
  }

  @Test
  void nullFieldsAndControlCharactersAreEscaped() {
    InterceptorEvent event =
        InterceptorEvent.builder().type(null).methodName("line\nbreak").timestamp(0L).build();

    String request = OtlpEventListener.toRequest(event);

    assertTrue(request.contains("\"severityText\":\"INFO\""), request);
    assertTrue(request.contains("line\\nbreak"), request);
    assertTrue(request.contains("\"value\":{\"stringValue\":\"\"}"), request);
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
