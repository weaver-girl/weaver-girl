package com.github.cc11001100.weavergirl.runtime;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.github.cc11001100.weavergirl.api.plugin.PluginContext;
import com.github.cc11001100.weavergirl.api.registry.InterceptorRegistry;
import com.github.cc11001100.weavergirl.core.InterceptorHolder;
import java.io.BufferedReader;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.ServerSocket;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.Collections;
import java.util.HashMap;
import java.util.Map;
import org.junit.jupiter.api.Test;

/** The diagnostics capability switches on only when {@code diagnosticsPort} is set. */
class DiagnosticsPluginTest {

  @Test
  void disabledWithoutPort() {
    assertFalse(new DiagnosticsPlugin().isEnabled(context(Collections.emptyMap())));
  }

  @Test
  void enabledWhenPortSet() {
    assertTrue(new DiagnosticsPlugin().isEnabled(context(config("diagnosticsPort", "0"))));
  }

  @Test
  void invalidPortDoesNotThrow() {
    DiagnosticsPlugin plugin = new DiagnosticsPlugin();
    PluginContext context = context(config("diagnosticsPort", "not-a-port"));

    assertTrue(plugin.isEnabled(context));
    plugin.init(context);
    plugin.destroy();
  }

  @Test
  void endpointsAnswerOnceThePortIsSet() throws Exception {
    ServerSocket free = new ServerSocket(0);
    int port = free.getLocalPort();
    free.close();

    boolean before = InterceptorHolder.isInterceptionEnabled();
    DiagnosticsPlugin plugin = new DiagnosticsPlugin();
    try {
      plugin.init(context(config("diagnosticsPort", Integer.toString(port))));

      HttpURLConnection stats = get("http://localhost:" + port + "/stats");
      assertEquals(200, stats.getResponseCode());
      String statsBody = read(stats.getInputStream());
      assertTrue(statsBody.contains("\"interceptorInvocationCount\""), statsBody);
      assertTrue(statsBody.contains("\"topHooks\""), statsBody);
      assertTrue(statsBody.contains("\"circuitBreakers\""), statsBody);
      assertTrue(statsBody.contains("\"byteBuddyClassLoader\""), statsBody);

      HttpURLConnection metrics = get("http://localhost:" + port + "/metrics");
      assertEquals(200, metrics.getResponseCode());
      String metricsBody = read(metrics.getInputStream());
      assertTrue(metricsBody.contains("weavergirl_uptime_seconds"), metricsBody);
      assertTrue(metricsBody.contains("weavergirl_interceptor_invocations_total"), metricsBody);

      HttpURLConnection toggle = post("http://localhost:" + port + "/agent/interception", "{\"enabled\":false}");
      assertEquals(200, toggle.getResponseCode());
      assertTrue(read(toggle.getInputStream()).contains("\"enabled\":false"));
      assertFalse(InterceptorHolder.isInterceptionEnabled());

      HttpURLConnection rejected = get("http://localhost:" + port + "/agent/interception");
      assertEquals(405, rejected.getResponseCode());
    } finally {
      plugin.destroy();
      InterceptorHolder.setInterceptionEnabled(before, "test");
    }
  }

  private static HttpURLConnection get(String url) throws Exception {
    HttpURLConnection conn = (HttpURLConnection) new URL(url).openConnection();
    conn.setConnectTimeout(2000);
    conn.setReadTimeout(2000);
    return conn;
  }

  private static HttpURLConnection post(String url, String body) throws Exception {
    HttpURLConnection conn = (HttpURLConnection) new URL(url).openConnection();
    conn.setConnectTimeout(2000);
    conn.setReadTimeout(2000);
    conn.setRequestMethod("POST");
    conn.setDoOutput(true);
    OutputStream out = conn.getOutputStream();
    out.write(body.getBytes(StandardCharsets.UTF_8));
    out.close();
    return conn;
  }

  private static String read(InputStream in) throws Exception {
    BufferedReader reader = new BufferedReader(new InputStreamReader(in, StandardCharsets.UTF_8));
    StringBuilder body = new StringBuilder();
    String line;
    while ((line = reader.readLine()) != null) {
      body.append(line);
    }
    reader.close();
    return body.toString();
  }

  private static Map<String, String> config(String key, String value) {
    Map<String, String> config = new HashMap<>();
    config.put(key, value);
    return config;
  }

  private static PluginContext context(Map<String, String> config) {
    return new com.github.cc11001100.weavergirl.core.plugin.DefaultPluginContext(
        registry(), config, DiagnosticsPlugin.NAME);
  }

  private static InterceptorRegistry registry() {
    return new com.github.cc11001100.weavergirl.core.registry.DefaultInterceptorRegistry();
  }
}
