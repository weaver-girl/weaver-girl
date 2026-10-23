package com.github.cc11001100.weavergirl.runtime.metrics;

import static org.junit.jupiter.api.Assertions.*;

import com.github.cc11001100.weavergirl.api.event.InterceptorEvent;
import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.net.HttpURLConnection;
import java.net.URL;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class PrometheusExporterTest {

  private PrometheusExporter exporter;

  @BeforeEach
  void setUp() {
    exporter = new PrometheusExporter();
  }

  @AfterEach
  void tearDown() {
    exporter.stop();
  }

  @Test
  void onEvent_slowQuery_incrementsSlowCounter() {
    InterceptorEvent event =
        InterceptorEvent.builder()
            .type("slow-query")
            .plugin("jdbc")
            .className("PgStatement")
            .methodName("execute")
            .durationMs(2500)
            .build();

    exporter.onEvent(event);

    String metrics = exporter.renderMetrics();
    assertTrue(
        metrics.contains(
            "weavergirl_slow_operations_total{plugin=\"jdbc\",type=\"slow-query\"} 1"));
  }

  @Test
  void onEvent_error_incrementsErrorCounter() {
    InterceptorEvent event =
        InterceptorEvent.builder()
            .type("jdbc-error")
            .plugin("jdbc")
            .className("PgStatement")
            .methodName("execute")
            .attribute("error", "connection refused")
            .build();

    exporter.onEvent(event);

    String metrics = exporter.renderMetrics();
    assertTrue(metrics.contains("weavergirl_error_operations_total{plugin=\"jdbc\"} 1"));
  }

  @Test
  void onEvent_withDuration_tracksDurationMetrics() {
    InterceptorEvent event =
        InterceptorEvent.builder().type("slow-query").plugin("jdbc").durationMs(1500).build();

    exporter.onEvent(event);

    String metrics = exporter.renderMetrics();
    assertTrue(metrics.contains("weavergirl_operation_duration_ms_sum{plugin=\"jdbc\"} 1500"));
    assertTrue(metrics.contains("weavergirl_operation_duration_ms_count{plugin=\"jdbc\"} 1"));
  }

  @Test
  void onEvent_everyEventCountsTowardsThePluginTypeTotal() {
    exporter.onEvent(
        InterceptorEvent.builder().type("request").plugin("servlet").durationMs(10).build());
    exporter.onEvent(
        InterceptorEvent.builder().type("request").plugin("servlet").durationMs(20).build());
    exporter.onEvent(InterceptorEvent.builder().type("jdbc-error").plugin("jdbc").build());

    String metrics = exporter.renderMetrics();
    assertTrue(
        metrics.contains(
            "weavergirl_events_total{plugin=\"servlet\",type=\"request\"} 2"));
    assertTrue(
        metrics.contains("weavergirl_events_total{plugin=\"jdbc\",type=\"jdbc-error\"} 1"));
    assertTrue(metrics.contains("# TYPE weavergirl_events_total counter"));
  }

  @Test
  void onEvent_multipleEvents_accumulate() {
    exporter.onEvent(
        InterceptorEvent.builder().type("slow-query").plugin("jdbc").durationMs(1000).build());
    exporter.onEvent(
        InterceptorEvent.builder().type("slow-query").plugin("jdbc").durationMs(2000).build());
    exporter.onEvent(
        InterceptorEvent.builder().type("slow-request").plugin("servlet").durationMs(500).build());

    String metrics = exporter.renderMetrics();
    assertTrue(
        metrics.contains(
            "weavergirl_slow_operations_total{plugin=\"jdbc\",type=\"slow-query\"} 2"));
    assertTrue(
        metrics.contains(
            "weavergirl_slow_operations_total{plugin=\"servlet\",type=\"slow-request\"} 1"));
    assertTrue(metrics.contains("weavergirl_operation_duration_ms_sum{plugin=\"jdbc\"} 3000"));
    assertTrue(metrics.contains("weavergirl_operation_duration_ms_count{plugin=\"jdbc\"} 2"));
  }

  @Test
  void onEvent_nonSlowNonError_ignoredByCounters() {
    InterceptorEvent event =
        InterceptorEvent.builder()
            .type("request")
            .plugin("servlet")
            .className("FrameworkServlet")
            .methodName("service")
            .durationMs(50)
            .build();

    exporter.onEvent(event);

    String metrics = exporter.renderMetrics();
    // No slow counter for non-slow events
    assertFalse(metrics.contains("weavergirl_slow_operations_total{plugin=\"servlet\""));
    // But duration is still tracked
    assertTrue(metrics.contains("weavergirl_operation_duration_ms_sum{plugin=\"servlet\"} 50"));
  }

  @Test
  void renderMetrics_includesHelpAndTypeHeaders() {
    exporter.onEvent(
        InterceptorEvent.builder().type("slow-query").plugin("jdbc").durationMs(1000).build());

    String metrics = exporter.renderMetrics();
    assertTrue(metrics.contains("# HELP weavergirl_slow_operations_total"));
    assertTrue(metrics.contains("# TYPE weavergirl_slow_operations_total counter"));
    assertTrue(metrics.contains("# HELP weavergirl_error_operations_total"));
    assertTrue(metrics.contains("# TYPE weavergirl_error_operations_total counter"));
    assertTrue(metrics.contains("# HELP weavergirl_operation_duration_ms_sum"));
    assertTrue(metrics.contains("# HELP weavergirl_operation_duration_ms_count"));
  }

  @Test
  void startHttpServer_servesMetricsEndpoint() throws Exception {
    // Use a high port to avoid conflicts
    exporter.start(19400);

    URL url = new URL("http://localhost:19400/metrics");
    HttpURLConnection conn = (HttpURLConnection) url.openConnection();
    conn.setRequestMethod("GET");

    assertEquals(200, conn.getResponseCode());
    assertEquals("text/plain; version=0.0.4; charset=utf-8", conn.getHeaderField("Content-Type"));

    BufferedReader reader = new BufferedReader(new InputStreamReader(conn.getInputStream()));
    StringBuilder body = new StringBuilder();
    String line;
    while ((line = reader.readLine()) != null) {
      body.append(line).append("\n");
    }
    reader.close();

    assertTrue(body.toString().contains("# HELP weavergirl"));
  }

  @Test
  void stop_doesNotThrow() throws Exception {
    exporter.start(19401);
    assertDoesNotThrow(() -> exporter.stop());
  }

  @Test
  void renderMetrics_includesJvmMetricsWithoutAnyTraffic() {
    String metrics = exporter.renderMetrics();

    assertTrue(metrics.contains("# TYPE weavergirl_jvm_memory_used_bytes gauge"));
    assertTrue(metrics.contains("weavergirl_jvm_memory_used_bytes{area=\"heap\"} "));
    assertTrue(metrics.contains("weavergirl_jvm_memory_used_bytes{area=\"nonheap\"} "));
    assertTrue(metrics.contains("# TYPE weavergirl_jvm_threads_current gauge"));
    assertTrue(metrics.contains("# TYPE weavergirl_jvm_classes_loaded gauge"));
    assertTrue(metrics.contains("# TYPE weavergirl_jvm_uptime_seconds gauge"));
    assertTrue(metrics.contains("# TYPE weavergirl_jvm_gc_collection_count_total counter"));
  }

  @Test
  void renderMetrics_exportsAgentSelfMetricsWhenTheEngineIsRunning() {
    com.github.cc11001100.weavergirl.api.status.AgentStatusHolder.set(
        new com.github.cc11001100.weavergirl.api.status.AgentStatusView() {
          @Override
          public long getTransformationCount() {
            return 42;
          }

          @Override
          public long getTransformationErrorCount() {
            return 3;
          }

          @Override
          public long getInterceptorInvocationCount() {
            return 1000;
          }

          @Override
          public long getInterceptorErrorCount() {
            return 7;
          }

          @Override
          public int getRegisteredInterceptorCount() {
            return 12;
          }
        });
    try {
      String metrics = exporter.renderMetrics();

      assertTrue(metrics.contains("weavergirl_agent_transformations_total 42"));
      assertTrue(metrics.contains("weavergirl_agent_transformation_errors_total 3"));
      assertTrue(metrics.contains("weavergirl_agent_interceptor_invocations_total 1000"));
      assertTrue(metrics.contains("weavergirl_agent_interceptor_errors_total 7"));
      assertTrue(metrics.contains("weavergirl_agent_registered_interceptors 12"));
      assertTrue(
          metrics.contains("# TYPE weavergirl_agent_registered_interceptors gauge"));
    } finally {
      com.github.cc11001100.weavergirl.api.status.AgentStatusHolder.set(null);
    }
  }

  @Test
  void renderMetrics_omitsAgentSelfMetricsWhenTheEngineIsNotRunning() {
    com.github.cc11001100.weavergirl.api.status.AgentStatusHolder.set(null);

    String metrics = exporter.renderMetrics();
    assertFalse(metrics.contains("weavergirl_agent_transformations_total"));
  }

  @Test
  void renderMetrics_exportsMetricRegistryWindows() {
    java.util.Map<String, String> labels = new java.util.LinkedHashMap<>();
    labels.put("route", "/users");
    com.github.cc11001100.weavergirl.api.metrics.MetricRegistry.record(
        "wg.test.request.duration_ms", labels, 100);
    com.github.cc11001100.weavergirl.api.metrics.MetricRegistry.record(
        "wg.test.request.duration_ms", labels, 300);
    try {
      String metrics = exporter.renderMetrics();

      assertTrue(
          metrics.contains(
              "weavergirl_metric_count{metric=\"wg.test.request.duration_ms\",route=\"/users\"} 2"),
          metrics);
      assertTrue(metrics.contains("weavergirl_metric_sum{metric=\"wg.test.request.duration_ms\""));
      assertTrue(metrics.contains("weavergirl_metric_avg{metric=\"wg.test.request.duration_ms\""));
      assertTrue(metrics.contains("weavergirl_metric_min{metric=\"wg.test.request.duration_ms\""));
      assertTrue(metrics.contains("weavergirl_metric_max{metric=\"wg.test.request.duration_ms\""));
      assertTrue(metrics.contains("weavergirl_metric_p50{metric=\"wg.test.request.duration_ms\""));
      assertTrue(metrics.contains("weavergirl_metric_p90{metric=\"wg.test.request.duration_ms\""));
      assertTrue(metrics.contains("weavergirl_metric_p95{metric=\"wg.test.request.duration_ms\""));
      assertTrue(metrics.contains("weavergirl_metric_p99{metric=\"wg.test.request.duration_ms\""));
      assertTrue(
          metrics.contains(
              "weavergirl_metric_avg{metric=\"wg.test.request.duration_ms\",route=\"/users\"} 200"));
      assertTrue(metrics.contains("# TYPE weavergirl_metric_p99 gauge"));
    } finally {
      for (String key :
          com.github.cc11001100.weavergirl.api.metrics.MetricRegistry.getMetricsByName(
              "wg.test.request.duration_ms")) {
        com.github.cc11001100.weavergirl.api.metrics.MetricRegistry.remove(key);
      }
    }
  }

  @Test
  void renderMetrics_skipsRegistryMetricsWithNoSamplesInTheWindow() {
    com.github.cc11001100.weavergirl.api.metrics.MetricRegistry.getOrCreate(
        "wg.test.empty.metric", 1000);
    try {
      String metrics = exporter.renderMetrics();
      assertFalse(metrics.contains("metric=\"wg.test.empty.metric\""));
      // A registry with nothing to say must not emit HELP/TYPE lines either.
      assertFalse(metrics.contains("# TYPE weavergirl_metric_count gauge"));
    } finally {
      com.github.cc11001100.weavergirl.api.metrics.MetricRegistry.remove("wg.test.empty.metric");
    }
  }

  @Test
  void renderMetrics_sanitizesRegistryLabelNames() {
    java.util.Map<String, String> labels = new java.util.LinkedHashMap<>();
    labels.put("9bad-name", "v");
    com.github.cc11001100.weavergirl.api.metrics.MetricRegistry.record("wg.test.labels", labels, 1);
    try {
      String metrics = exporter.renderMetrics();
      assertTrue(metrics.contains("_bad_name=\"v\""), metrics);
      assertFalse(metrics.contains("9bad-name"));
    } finally {
      for (String key :
          com.github.cc11001100.weavergirl.api.metrics.MetricRegistry.getMetricsByName(
              "wg.test.labels")) {
        com.github.cc11001100.weavergirl.api.metrics.MetricRegistry.remove(key);
      }
    }
  }

  @Test
  void formatNumber_rendersIntegersAndFractionsWithoutExponentNoise() {
    assertEquals("42", PrometheusExporter.formatNumber(42.0));
    assertEquals("0.5", PrometheusExporter.formatNumber(0.5));
    assertEquals("NaN", PrometheusExporter.formatNumber(Double.NaN));
    assertEquals("+Inf", PrometheusExporter.formatNumber(Double.POSITIVE_INFINITY));
  }

  @Test
  void specialCharactersInPluginName_escaped() {
    InterceptorEvent event =
        InterceptorEvent.builder()
            .type("slow-query")
            .plugin("jdbc\"injection")
            .durationMs(1000)
            .build();

    exporter.onEvent(event);

    String metrics = exporter.renderMetrics();
    assertTrue(metrics.contains("plugin=\"jdbc\\\"injection\""));
  }
}
