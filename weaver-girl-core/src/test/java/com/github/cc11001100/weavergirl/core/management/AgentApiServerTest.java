package com.github.cc11001100.weavergirl.core.management;

import static org.junit.jupiter.api.Assertions.*;

import com.github.cc11001100.weavergirl.api.alert.AlertEngine;
import com.github.cc11001100.weavergirl.api.alert.AlertRule;
import com.github.cc11001100.weavergirl.api.event.InterceptorEvent;
import com.github.cc11001100.weavergirl.api.event.InterceptorEventPublisher;
import com.github.cc11001100.weavergirl.api.metrics.MetricRegistry;
import com.github.cc11001100.weavergirl.api.security.SecurityAuditLog;
import com.github.cc11001100.weavergirl.api.event.LifecycleEvents;
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
  void tracesEndpoint_rendersTheCurrentSpanAndThreadContext() {
    assertEquals(port, server.getPort());

    com.github.cc11001100.weavergirl.api.tracing.SpanContext span =
        com.github.cc11001100.weavergirl.api.tracing.SpanContext.builder()
            .traceId("trace\"with\\quote")
            .spanId("span-1")
            .parentSpanId("parent-1")
            .operationName("GET /items")
            .errorStatus("ERROR")
            .baggage("user", "ada")
            .build();
    java.util.Map<String, Object> context = new java.util.LinkedHashMap<>();
    context.put("requestId", "r-1");
    context.put("missing", null);
    com.github.cc11001100.weavergirl.api.context.ContextSnapshot snapshot =
        com.github.cc11001100.weavergirl.api.context.ContextSnapshot.fromThreadContext(context);

    String json = AgentApiServer.tracesJson(span, snapshot);

    assertTrue(json.contains("\"parentSpanId\":\"parent-1\""));
    assertTrue(json.contains("\"errorStatus\":\"ERROR\""));
    assertTrue(json.contains("\"baggageCount\":1"));
    assertTrue(json.contains("\"traceId\":\"trace\\\"with\\\\quote\""));
    assertTrue(json.contains("\"requestId\":\"r-1\""));
    assertTrue(json.contains("\"missing\":\"null\""));
    assertTrue(json.contains("\"hasSpan\":true"));
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
    // The publisher history is a JVM-wide bounded buffer other tests write concurrently, so
    // assert that this test's own event is present rather than an exact count.
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
    // Counts come from the shared publisher history, which other tests append to concurrently,
    // so check the events this test published instead of exact totals.
    assertTrue(response.contains("weaver-girl.lifecycle.registry"));
    assertTrue(response.contains("weaver-girl.lifecycle.plugin"));
    assertTrue(response.contains("test-reg"));
    assertTrue(response.contains("test-plugin"));
    assertTrue(response.contains("\"phase\":\"register\""));
    assertTrue(response.contains("\"phase\":\"enable\""));
    assertTrue(response.contains("\"success\":\"true\""));
  }

  /** With a plugin manager installed the endpoint lists each plugin, separated when there are two. */
  @Test
  void pluginsEndpoint_listsInstalledPlugins() throws Exception {
    com.github.cc11001100.weavergirl.api.plugin.PluginManager manager =
        new com.github.cc11001100.weavergirl.api.plugin.PluginManager() {
          public boolean disablePlugin(String name) {
            return false;
          }

          public boolean enablePlugin(String name) {
            return false;
          }

          public boolean unloadPlugin(String name) {
            return false;
          }

          public java.util.Optional<com.github.cc11001100.weavergirl.api.plugin.PluginInfo>
              getPluginInfo(String name) {
            return java.util.Optional.empty();
          }

          public java.util.List<com.github.cc11001100.weavergirl.api.plugin.PluginInfo>
              getAllPluginInfo() {
            return java.util.Arrays.asList(pluginInfo("alpha", 2), pluginInfo("beta", 0));
          }

          public boolean isPluginActive(String name) {
            return false;
          }

          public int getActivePluginCount() {
            return 2;
          }
        };
    com.github.cc11001100.weavergirl.api.plugin.PluginManagerHolder.setInstance(manager);
    try {
      String response = get("/plugins");
      assertTrue(response.contains("\"name\":\"alpha\""));
      assertTrue(response.contains("\"name\":\"beta\""));
      assertTrue(response.contains("\"activeCount\":2"));
    } finally {
      com.github.cc11001100.weavergirl.api.plugin.PluginManagerHolder.setInstance(null);
    }
  }

  /** Two alerts in the history need a separator between them. */
  @Test
  void alertsEndpoint_separatesMultipleAlerts() throws Exception {
    AlertEngine.addRule(
        AlertRule.builder().name("first").metric("m1").operator("gt").threshold(1).build());
    AlertEngine.addRule(
        AlertRule.builder().name("second").metric("m2").operator("gt").threshold(1).build());
    AlertEngine.evaluate("m1", 2);
    AlertEngine.evaluate("m2", 2);

    String response = get("/alerts");
    assertTrue(response.contains("first"));
    assertTrue(response.contains("second"));
    assertTrue(response.contains("},{"), "two alerts were not separated");
  }

  /** Allow and deny patterns are listed, with a separator once there is more than one. */
  @Test
  void securityPolicyEndpoint_listsPatterns() throws Exception {
    com.github.cc11001100.weavergirl.api.security.SecurityPolicy previous =
        SecurityAuditLog.getPolicy();
    SecurityAuditLog.setPolicy(
        com.github.cc11001100.weavergirl.api.security.SecurityPolicy.builder()
            .allowPattern("com.example.*")
            .allowPattern("com.other.*")
            .denyPattern("com.secret.*")
            .build());
    try {
      String response = get("/security/policy");
      assertTrue(response.contains("com.example.*"));
      assertTrue(response.contains("com.other.*"));
      assertTrue(response.contains("com.secret.*"));
    } finally {
      SecurityAuditLog.setPolicy(previous);
    }
  }

  private static com.github.cc11001100.weavergirl.api.plugin.PluginInfo pluginInfo(
      String name, int interceptors) {
    return new com.github.cc11001100.weavergirl.api.plugin.PluginInfo(
        name,
        com.github.cc11001100.weavergirl.api.plugin.PluginState.ACTIVE,
        "1.0",
        interceptors,
        System.currentTimeMillis(),
        System.currentTimeMillis());
  }

  @Test
  void securityPolicyEndpoint_returnsCurrentPolicy() throws Exception {
    String response = get("/security/policy");
    assertTrue(response.contains("\"defaultAllow\""));
    assertTrue(response.contains("\"auditAllInterceptions\""));
    assertTrue(response.contains("\"allowPatterns\""));
    assertTrue(response.contains("\"denyPatterns\""));
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
