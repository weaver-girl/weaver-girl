package com.github.cc11001100.weavergirl.runtime;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.github.cc11001100.weavergirl.api.interceptor.Interceptor;
import com.github.cc11001100.weavergirl.api.interceptor.InterceptorDefinition;
import com.github.cc11001100.weavergirl.api.matcher.ClassMatcher;
import com.github.cc11001100.weavergirl.api.matcher.MethodMatcher;
import com.github.cc11001100.weavergirl.api.plugin.PluginContext;
import com.github.cc11001100.weavergirl.api.pointcut.Pointcut;
import com.github.cc11001100.weavergirl.api.registry.InterceptorRegistry;
import com.github.cc11001100.weavergirl.core.InterceptorHolder;
import java.io.BufferedReader;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.net.HttpURLConnection;
import java.net.ServerSocket;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.Collections;
import java.util.HashMap;
import java.util.Map;
import org.junit.jupiter.api.Test;

/** The health capability switches on only when {@code healthPort} is set, and then answers probes. */
class HealthPluginTest {

  @Test
  void disabledWithoutPort() {
    assertFalse(new HealthPlugin().isEnabled(context(Collections.emptyMap())));
  }

  @Test
  void enabledWhenPortSet() {
    assertTrue(new HealthPlugin().isEnabled(context(config("healthPort", "0"))));
  }

  @Test
  void invalidPortDoesNotThrow() {
    HealthPlugin plugin = new HealthPlugin();
    PluginContext context = context(config("healthPort", "not-a-port"));

    assertTrue(plugin.isEnabled(context));
    plugin.init(context);
    plugin.destroy();
  }

  @Test
  void probesAnswerOnceThePortIsSet() throws Exception {
    ServerSocket free = new ServerSocket(0);
    int port = free.getLocalPort();
    free.close();

    InterceptorRegistry registry = registry();
    registry.register(
        new InterceptorDefinition(
            "test-health",
            new Pointcut(ClassMatcher.byName("Test"), MethodMatcher.any()),
            new Interceptor() {}));
    InterceptorHolder.setRegistry(registry);

    HealthPlugin plugin = new HealthPlugin();
    try {
      plugin.init(context(config("healthPort", Integer.toString(port))));

      HttpURLConnection health = get("http://localhost:" + port + "/health");
      assertEquals(200, health.getResponseCode());
      String body = read(health.getInputStream());
      assertTrue(body.contains("\"status\":\"UP\""), body);
      assertTrue(body.contains("\"agent\":\"weaver-girl\""), body);
      assertTrue(body.contains("\"uptimeSeconds\""), body);

      HttpURLConnection ready = get("http://localhost:" + port + "/ready");
      assertEquals(200, ready.getResponseCode());
      String readyBody = read(ready.getInputStream());
      assertTrue(readyBody.contains("\"status\":\"READY\""), readyBody);
      assertTrue(readyBody.contains("\"interceptorCount\":1"), readyBody);
    } finally {
      plugin.destroy();
      InterceptorHolder.setRegistry(null);
    }
  }

  @Test
  void notReadyBeforeAnythingIsRegistered() throws Exception {
    ServerSocket free = new ServerSocket(0);
    int port = free.getLocalPort();
    free.close();

    InterceptorHolder.setRegistry(registry());
    HealthPlugin plugin = new HealthPlugin();
    try {
      plugin.init(context(config("healthPort", Integer.toString(port))));

      HttpURLConnection ready = get("http://localhost:" + port + "/ready");
      assertEquals(503, ready.getResponseCode());
      String body = read(ready.getErrorStream());
      assertTrue(body.contains("\"status\":\"NOT_READY\""), body);
    } finally {
      plugin.destroy();
      InterceptorHolder.setRegistry(null);
    }
  }

  private static HttpURLConnection get(String url) throws Exception {
    HttpURLConnection conn = (HttpURLConnection) new URL(url).openConnection();
    conn.setConnectTimeout(2000);
    conn.setReadTimeout(2000);
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
        registry(), config, HealthPlugin.NAME);
  }

  private static InterceptorRegistry registry() {
    return new com.github.cc11001100.weavergirl.core.registry.DefaultInterceptorRegistry();
  }
}
