package com.github.cc11001100.weavergirl.core.management;

import static org.junit.jupiter.api.Assertions.*;

import com.github.cc11001100.weavergirl.api.alert.AlertEngine;
import com.github.cc11001100.weavergirl.api.alert.AlertRule;
import com.github.cc11001100.weavergirl.api.event.InterceptorEvent;
import com.github.cc11001100.weavergirl.api.event.InterceptorEventPublisher;
import com.github.cc11001100.weavergirl.api.metrics.MetricRegistry;
import com.github.cc11001100.weavergirl.api.security.SecurityAuditLog;
import com.github.cc11001100.weavergirl.core.event.LifecycleEvents;
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
    SecurityAuditLog.clear();
    InterceptorEventPublisher.getInstance().clearHistory();
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
    SecurityAuditLog.clear();
    InterceptorEventPublisher.getInstance().clearHistory();
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

  @Test
  void tracesEndpoint_returnsJson() throws Exception {
    String response = get("/traces");
    assertTrue(response.contains("\"span\"") || response.contains("\"hasSpan\""));
    assertTrue(response.contains("\"threadContext\""));
  }

  @Test
  void contextPropagatorsEndpoint_returnsJson() throws Exception {
    String response = get("/context/propagators");
    assertTrue(response.contains("\"propagators\""));
    assertTrue(response.contains("\"count\""));
  }

  @Test
  void eventsEndpoint_returnsHistory() throws Exception {
    InterceptorEventPublisher.getInstance().publish(
        InterceptorEvent.builder()
            .type("test-event")
            .plugin("test")
            .className("com.example.Foo")
            .methodName("bar")
            .durationMs(12)
            .build());

    String response = get("/events");
    assertTrue(response.contains("\"events\""));
    assertTrue(response.contains("\"count\":1"));
    assertTrue(response.contains("test-event"));
    assertTrue(response.contains("com.example.Foo"));
    assertTrue(response.contains("bar"));
  }

  @Test
  void auditEndpoint_returnsRecords() throws Exception {
    SecurityAuditLog.recordInterception("com.example.Secret", "getPassword", false);

    String response = get("/audit");
    assertTrue(response.contains("\"records\""));
    assertTrue(response.contains("\"count\":1"));
    assertTrue(response.contains("INTERCEPT"));
    assertTrue(response.contains("com.example.Secret"));
    assertTrue(response.contains("getPassword"));
    assertTrue(response.contains("DENIED"));
  }

  @Test
  void lifecycleEndpoint_returnsRegistryAndPluginEvents() throws Exception {
    InterceptorEventPublisher.getInstance().publish(
        LifecycleEvents.registry("register", "test-reg", true, "ok"));
    InterceptorEventPublisher.getInstance().publish(
        LifecycleEvents.plugin("enable", "test-plugin", true, "enabled"));

    String response = get("/lifecycle");
    assertTrue(response.contains("\"events\""));
    assertTrue(response.contains("\"count\":2"));
    assertTrue(response.contains("\"registryCount\":1"));
    assertTrue(response.contains("\"pluginCount\":1"));
    assertTrue(response.contains("weaver-girl.lifecycle.registry"));
    assertTrue(response.contains("weaver-girl.lifecycle.plugin"));
    assertTrue(response.contains("test-reg"));
    assertTrue(response.contains("test-plugin"));
    assertTrue(response.contains("\"phase\":\"register\""));
    assertTrue(response.contains("\"phase\":\"enable\""));
    assertTrue(response.contains("\"success\":\"true\""));
  }

  @Test
  void errorsEndpoint_returnsCounters() throws Exception {
    String response = get("/errors");
    assertTrue(response.contains("\"transformationErrorCount\""));
    assertTrue(response.contains("\"interceptorErrorCount\""));
  }

  @Test
  void endToEnd_allEndpointsReturnPopulatedData() throws Exception {
    com.github.cc11001100.weavergirl.api.metrics.MetricRegistry.record(
        "api.latency", java.util.Collections.singletonMap("zone", "east"), 12.5);
    com.github.cc11001100.weavergirl.api.alert.AlertRule rule =
        com.github.cc11001100.weavergirl.api.alert.AlertRule.builder()
            .name("e2e-alert")
            .metric("api.latency")
            .operator("gt")
            .threshold(10)
            .severity("warn")
            .message("latency high")
            .build();
    com.github.cc11001100.weavergirl.api.alert.AlertEngine.addRule(rule);
    com.github.cc11001100.weavergirl.api.alert.AlertEngine.evaluate("api.latency", 20);
    com.github.cc11001100.weavergirl.api.topology.TopologyGraph.addNode(
        new com.github.cc11001100.weavergirl.api.topology.ServiceNode(
            "svc-a", "service", java.util.Collections.emptyMap()));
    com.github.cc11001100.weavergirl.api.topology.TopologyGraph.recordCall(
        "svc-a", "svc-b", "http", 5, false);

    String status = get("/status");
    assertTrue(status.contains("\"status\":\"UP\""));
    assertTrue(status.contains("\"interceptorCount\""));

    String plugins = get("/plugins");
    assertTrue(plugins.contains("\"plugins\""));
    assertTrue(plugins.contains("activeCount"));

    String topology = get("/topology");
    assertTrue(topology.contains("\"nodes\""));
    assertTrue(topology.contains("\"edges\""));
    assertTrue(topology.contains("svc-a"));

    String alerts = get("/alerts");
    assertTrue(alerts.contains("\"alerts\""));
    assertTrue(alerts.contains("e2e-alert"));

    String metrics = get("/metrics");
    assertTrue(metrics.contains("\"metrics\""));
    assertTrue(metrics.contains("api.latency"));

    String diagnostics = get("/diagnostics");
    assertTrue(diagnostics.contains("Diagnostics Report"));

    String traces = get("/traces");
    assertTrue(traces.contains("\"threadContext\"") || traces.contains("\"hasSpan\""));

    String propagators = get("/context/propagators");
    assertTrue(propagators.contains("\"propagators\""));
    assertTrue(propagators.contains("\"count\""));
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
