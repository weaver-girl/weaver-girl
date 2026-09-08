package com.github.cc11001100.weavergirl.agent;

import static org.junit.jupiter.api.Assertions.*;

import com.github.cc11001100.weavergirl.api.interceptor.Interceptor;
import com.github.cc11001100.weavergirl.api.interceptor.InterceptorDefinition;
import com.github.cc11001100.weavergirl.api.matcher.ClassMatcher;
import com.github.cc11001100.weavergirl.api.matcher.MethodMatcher;
import com.github.cc11001100.weavergirl.api.pointcut.Pointcut;
import com.github.cc11001100.weavergirl.core.WeaverGirl;
import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.net.HttpURLConnection;
import java.net.URL;
import org.junit.jupiter.api.Test;

/**
 * Tests for the health check endpoint. Uses reflection to invoke startHealthEndpoint since it's
 * private.
 */
class HealthEndpointTest {

  @Test
  void healthEndpointReturnsUp() throws Exception {
    // Find a free port
    java.net.ServerSocket ss = new java.net.ServerSocket(0);
    int port = ss.getLocalPort();
    ss.close();

    // Use reflection to call startHealthEndpoint
    java.lang.reflect.Method startMethod =
        WeaverGirlAgent.class.getDeclaredMethod("startHealthEndpoint", int.class);
    startMethod.setAccessible(true);

    // Set weaverGirlInstance with a registry that has interceptors
    java.lang.reflect.Field wgField = WeaverGirlAgent.class.getDeclaredField("weaverGirlInstance");
    wgField.setAccessible(true);

    WeaverGirl wg = WeaverGirl.create();
    // Register something so the registry is non-empty
    wg.getRegistry()
        .register(
            new InterceptorDefinition(
                "test-health",
                new Pointcut(ClassMatcher.byName("Test"), MethodMatcher.any()),
                new Interceptor() {},
                0));
    wgField.set(null, wg);

    try {
      startMethod.invoke(null, port);

      // Test /health endpoint
      URL healthUrl = new URL("http://localhost:" + port + "/health");
      HttpURLConnection conn = (HttpURLConnection) healthUrl.openConnection();
      conn.setConnectTimeout(2000);
      conn.setReadTimeout(2000);
      assertEquals(200, conn.getResponseCode());
      String body = readBody(conn);
      assertTrue(body.contains("\"status\":\"UP\""));
      assertTrue(body.contains("\"agent\":\"weaver-girl\""));
      assertTrue(body.contains("\"uptimeSeconds\""));
      conn.disconnect();

      // Test /ready endpoint
      URL readyUrl = new URL("http://localhost:" + port + "/ready");
      HttpURLConnection conn2 = (HttpURLConnection) readyUrl.openConnection();
      conn2.setConnectTimeout(2000);
      conn2.setReadTimeout(2000);
      assertEquals(200, conn2.getResponseCode());
      String readyBody = readBody(conn2);
      assertTrue(readyBody.contains("\"status\":\"READY\""));
      assertTrue(readyBody.contains("\"interceptorCount\":1"));
      conn2.disconnect();
    } finally {
      // Clean up
      java.lang.reflect.Field healthField = WeaverGirlAgent.class.getDeclaredField("healthServer");
      healthField.setAccessible(true);
      com.sun.net.httpserver.HttpServer server =
          (com.sun.net.httpserver.HttpServer) healthField.get(null);
      if (server != null) {
        server.stop(0);
      }
      wgField.set(null, null);
    }
  }

  private String readBody(HttpURLConnection conn) throws Exception {
    BufferedReader reader =
        new BufferedReader(new InputStreamReader(conn.getInputStream(), "UTF-8"));
    StringBuilder sb = new StringBuilder();
    String line;
    while ((line = reader.readLine()) != null) {
      sb.append(line);
    }
    reader.close();
    return sb.toString();
  }
}
