package com.github.cc11001100.weavergirl.core.metrics;

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
