package com.github.cc11001100.weavergirl.core.management;

import static org.junit.jupiter.api.Assertions.*;

import com.github.cc11001100.weavergirl.api.alert.AlertEngine;
import com.github.cc11001100.weavergirl.api.alert.AlertRule;
import com.github.cc11001100.weavergirl.api.metrics.MetricRegistry;
import java.util.Map;
import java.io.*;
import java.net.*;
import org.junit.jupiter.api.*;

/** Tests for Agent REST API (P62). */
class AgentApiServerTest {

  private AgentApiServer server;
  private int port;

  @BeforeEach
  void setUp() throws Exception {
    AlertEngine.clear();
    MetricRegistry.clear();
    // Find free port
    try (ServerSocket ss = new ServerSocket(0)) {
      port = ss.getLocalPort();
    }
    server = new AgentApiServer(port);
    server.start();
  }

  @AfterEach
  void tearDown() {
    if (server != null) {
      server.stop();
    }
    AlertEngine.clear();
    MetricRegistry.clear();
  }

  @Test
  void statusEndpoint_returnsUp() throws Exception {
    String response = get("/status");
    assertTrue(response.contains("\"status\":\"UP\""));
    assertTrue(response.contains("interceptorCount"));
  }

  @Test
  void pluginsEndpoint_returnsJson() throws Exception {
    String response = get("/plugins");
    assertTrue(response.contains("\"plugins\""));
    assertTrue(response.contains("activeCount"));
  }

  @Test
  void topologyEndpoint_returnsJson() throws Exception {
    String response = get("/topology");
    assertTrue(response.contains("\"nodes\""));
    assertTrue(response.contains("\"edges\""));
    assertTrue(response.contains("\"nodeCount\""));
  }

  @Test
  void alertsEndpoint_returnsJson() throws Exception {
    String response = get("/alerts");
    assertTrue(response.contains("\"alerts\""));
    assertTrue(response.contains("\"count\""));
  }

  @Test
  void metricsEndpoint_returnsJson() throws Exception {
    String response = get("/metrics");
    assertTrue(response.contains("\"metrics\""));
  }

  @Test
  void metricsEndpoint_includesValuesAndLabels() throws Exception {
    MetricRegistry.record("api.metric", Map.of("zone", "east", "kind", "request"), 4.5);
    String response = get("/metrics");
    assertTrue(response.contains("api.metric{"));
    assertTrue(response.contains("\"count\":1"));
    assertTrue(response.contains("\"labels\""));
  }

  @Test
  void alertsEndpoint_includesTriggeredAlertAndEscapesText() throws Exception {
    AlertEngine.addRule(
        AlertRule.builder()
            .name("api-alert")
            .metric("api.metric")
            .operator("gt")
            .threshold(1)
            .severity("critical\"level")
            .message("too \"slow\"")
            .build());
    AlertEngine.evaluate("api.metric", 2);
    String response = get("/alerts");
    assertTrue(response.contains("api-alert"));
    assertTrue(response.contains("critical\\\"level"));
    assertTrue(response.contains("too \\\"slow\\\""));
  }

  @Test
  void diagnosticsEndpoint_returnsReport() throws Exception {
    String response = get("/diagnostics");
    assertTrue(response.contains("Diagnostics Report"));
  }

  // ===== Helper =====

  private String get(String path) throws Exception {
    URL url = new URL("http://localhost:" + port + path);
    HttpURLConnection conn = (HttpURLConnection) url.openConnection();
    conn.setRequestMethod("GET");
    conn.setConnectTimeout(2000);
    conn.setReadTimeout(2000);
    assertEquals(200, conn.getResponseCode());

    try (BufferedReader reader =
        new BufferedReader(new InputStreamReader(conn.getInputStream(), "UTF-8"))) {
      StringBuilder sb = new StringBuilder();
      String line;
      while ((line = reader.readLine()) != null) {
        sb.append(line);
      }
      return sb.toString();
    }
  }
}
