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
    com.github.cc11001100.weavergirl.api.observability.MetricSources.clear();
    com.github.cc11001100.weavergirl.api.observability.HealthChecks.clear();
    com.github.cc11001100.weavergirl.api.status.AgentStatusHolder.set(null);
    com.github.cc11001100.weavergirl.api.plugin.PluginHealthRegistry.clear();
    com.github.cc11001100.weavergirl.api.observability.SpanSinks.clear();
    com.github.cc11001100.weavergirl.api.tracing.SpanProcessors.clear();
    com.github.cc11001100.weavergirl.api.sampling.SamplingStrategyRegistry.clear();
    com.github.cc11001100.weavergirl.api.tracing.Tracer.setCurrentSpan(null);
    com.github.cc11001100.weavergirl.api.plugin.PluginManagerHolder.setInstance(null);
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
  void renderMetrics_rendersPluginHealth() {
    com.github.cc11001100.weavergirl.api.plugin.PluginHealthRegistry.reportHealthy("prometheus");
    com.github.cc11001100.weavergirl.api.plugin.PluginHealthRegistry.reportDegraded(
        "jdbc", "collector unreachable");

    String metrics = exporter.renderMetrics();

    assertTrue(
        metrics.contains("weavergirl_plugin_health{plugin=\"prometheus\",status=\"healthy\"} 1"),
        metrics);
    assertTrue(
        metrics.contains("weavergirl_plugin_health{plugin=\"jdbc\",status=\"degraded\"} 1"),
        metrics);
    assertTrue(metrics.contains("# TYPE weavergirl_plugin_health gauge"), metrics);
    assertTrue(
        metrics.contains("weavergirl_plugin_health_timestamp_seconds{plugin=\"jdbc\"} "), metrics);
  }

  @Test
  void renderMetrics_rendersSpanSinkStats() {
    com.github.cc11001100.weavergirl.api.observability.SpanSinks.register(
        new com.github.cc11001100.weavergirl.api.observability.SpanSink() {
          @Override
          public String name() {
            return "my-apm";
          }

          @Override
          public void onSpanEnd(com.github.cc11001100.weavergirl.api.observability.SpanRecord span) {
            throw new IllegalStateException("backend down");
          }
        });
    com.github.cc11001100.weavergirl.api.tracing.Tracer.startSpan("op");
    com.github.cc11001100.weavergirl.api.tracing.Tracer.endSpan(null, null);

    String metrics = exporter.renderMetrics();

    assertTrue(
        metrics.contains("weavergirl_span_sink_deliveries_total{sink=\"my-apm\"} 1"), metrics);
    assertTrue(metrics.contains("weavergirl_span_sink_errors_total{sink=\"my-apm\"} 1"), metrics);
  }

  @Test
  void renderMetrics_rendersSpanProcessorStats() {
    com.github.cc11001100.weavergirl.api.tracing.SpanProcessors.register(
        new com.github.cc11001100.weavergirl.api.tracing.SpanProcessor() {
          @Override
          public String name() {
            return "drop-health";
          }

          @Override
          public com.github.cc11001100.weavergirl.api.tracing.SpanContext process(
              com.github.cc11001100.weavergirl.api.tracing.SpanContext span) {
            return null;
          }
        });

    com.github.cc11001100.weavergirl.api.tracing.Tracer.startSpan("GET /health");
    com.github.cc11001100.weavergirl.api.tracing.Tracer.endSpan(null, null);

    String metrics = exporter.renderMetrics();

    assertTrue(
        metrics.contains("weavergirl_span_processor_dropped_total{processor=\"drop-health\"} 1"),
        metrics);
    assertTrue(
        metrics.contains("weavergirl_span_processor_processed_total{processor=\"drop-health\"} 0"),
        metrics);
    assertTrue(
        metrics.contains("weavergirl_span_processor_errors_total{processor=\"drop-health\"} 0"),
        metrics);
    assertTrue(
        metrics.contains("# TYPE weavergirl_span_processor_dropped_total counter"), metrics);
  }

  @Test
  void renderMetrics_rendersSamplingStrategyDecisions() {
    com.github.cc11001100.weavergirl.api.sampling.SamplingStrategyRegistry.register(
        new com.github.cc11001100.weavergirl.api.sampling.SamplingStrategy() {
          @Override
          public String name() {
            return "errors-only";
          }

          @Override
          public boolean shouldSample() {
            return false;
          }
        });
    com.github.cc11001100.weavergirl.api.sampling.SamplingStrategyRegistry.setActive("errors-only");
    com.github.cc11001100.weavergirl.api.sampling.SamplingStrategyRegistry.decide();

    String metrics = exporter.renderMetrics();

    assertTrue(
        metrics.contains(
            "weavergirl_sampling_decisions_total{strategy=\"errors-only\",decision=\"dropped\"} 1"),
        metrics);
    assertTrue(
        metrics.contains(
            "weavergirl_sampling_decisions_total{strategy=\"errors-only\",decision=\"kept\"} 0"),
        metrics);
    assertTrue(
        metrics.contains("weavergirl_sampling_strategy_errors_total{strategy=\"errors-only\"} 0"),
        metrics);
  }

  @Test
  void renderMetrics_rendersPluginHealthChecks() {
    com.github.cc11001100.weavergirl.api.observability.HealthChecks.register(
        new com.github.cc11001100.weavergirl.api.observability.HealthCheck() {
          @Override
          public String name() {
            return "orders-db";
          }

          @Override
          public com.github.cc11001100.weavergirl.api.observability.HealthCheckResult check() {
            return com.github.cc11001100.weavergirl.api.observability.HealthCheckResult.down(
                "pool exhausted");
          }
        });
    com.github.cc11001100.weavergirl.api.observability.HealthChecks.register(
        new com.github.cc11001100.weavergirl.api.observability.HealthCheck() {
          @Override
          public String name() {
            return "cache";
          }

          @Override
          public com.github.cc11001100.weavergirl.api.observability.HealthCheckResult check() {
            return com.github.cc11001100.weavergirl.api.observability.HealthCheckResult.up();
          }
        });

    String metrics = exporter.renderMetrics();

    assertTrue(metrics.contains("weavergirl_health_overall{status=\"down\"} 1"), metrics);
    assertTrue(
        metrics.contains(
            "weavergirl_health_check{component=\"orders-db\",status=\"down\"} 1"),
        metrics);
    assertTrue(
        metrics.contains("weavergirl_health_check{component=\"cache\",status=\"up\"} 1"), metrics);
    assertTrue(
        metrics.contains("weavergirl_health_check_timestamp_seconds{component=\"orders-db\"} "),
        metrics);
  }

  @Test
  void renderMetrics_rendersPluginLifecycleState() {
    java.util.List<com.github.cc11001100.weavergirl.api.plugin.PluginInfo> info =
        new java.util.ArrayList<>();
    info.add(
        new com.github.cc11001100.weavergirl.api.plugin.PluginInfo(
            "servlet",
            com.github.cc11001100.weavergirl.api.plugin.PluginState.ACTIVE,
            "1.0",
            3,
            0L,
            0L));
    info.add(
        new com.github.cc11001100.weavergirl.api.plugin.PluginInfo(
            "jdbc",
            com.github.cc11001100.weavergirl.api.plugin.PluginState.DISABLED,
            null,
            0,
            0L,
            0L));
    final java.util.List<com.github.cc11001100.weavergirl.api.plugin.PluginInfo> all = info;
    com.github.cc11001100.weavergirl.api.plugin.PluginManagerHolder.setInstance(
        new com.github.cc11001100.weavergirl.api.plugin.PluginManager() {
          @Override
          public boolean disablePlugin(String pluginName) {
            return false;
          }

          @Override
          public boolean enablePlugin(String pluginName) {
            return false;
          }

          @Override
          public boolean unloadPlugin(String pluginName) {
            return false;
          }

          @Override
          public java.util.Optional<com.github.cc11001100.weavergirl.api.plugin.PluginInfo>
              getPluginInfo(String pluginName) {
            return java.util.Optional.empty();
          }

          @Override
          public java.util.List<com.github.cc11001100.weavergirl.api.plugin.PluginInfo>
              getAllPluginInfo() {
            return all;
          }

          @Override
          public boolean isPluginActive(String pluginName) {
            return false;
          }

          @Override
          public int getActivePluginCount() {
            return 1;
          }
        });

    String metrics = exporter.renderMetrics();

    assertTrue(
        metrics.contains("weavergirl_plugin_state{plugin=\"servlet\",state=\"active\"} 1"),
        metrics);
    assertTrue(
        metrics.contains("weavergirl_plugin_state{plugin=\"jdbc\",state=\"disabled\"} 1"),
        metrics);
    assertTrue(metrics.contains("weavergirl_plugin_interceptors{plugin=\"servlet\"} 3"), metrics);
    assertTrue(metrics.contains("weavergirl_plugin_interceptors{plugin=\"jdbc\"} 0"), metrics);
  }

  @Test
  void renderMetrics_omitsPluginStateWhenNoManagerIsInstalled() {
    com.github.cc11001100.weavergirl.api.plugin.PluginManagerHolder.setInstance(null);
    String metrics = exporter.renderMetrics();
    assertFalse(metrics.contains("weavergirl_plugin_state"), metrics);
  }

  @Test
  void renderMetrics_omitsSpanSinkMetricsWhenNoSinkIsRegistered() {
    String metrics = exporter.renderMetrics();
    assertFalse(metrics.contains("weavergirl_span_sink"), metrics);
  }

  @Test
  void renderMetrics_omitsPluginHealthWhenNothingReported() {
    String metrics = exporter.renderMetrics();
    assertFalse(metrics.contains("weavergirl_plugin_health"), metrics);
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
  void renderMetrics_rendersPluginMetricSources() {
    com.github.cc11001100.weavergirl.api.observability.MetricSources.register(
        new com.github.cc11001100.weavergirl.api.observability.MetricSource() {
          @Override
          public String name() {
            return "job-queue";
          }

          @Override
          public void collect(
              com.github.cc11001100.weavergirl.api.observability.MetricCollector collector) {
            java.util.Map<String, String> labels = new java.util.LinkedHashMap<>();
            labels.put("queue", "mail");
            collector.counter("jobs_processed_total", "Jobs finished, by queue", labels, 12);
            collector.gauge("jobs_pending", "Jobs waiting", 3);
          }
        });

    String metrics = exporter.renderMetrics();

    assertTrue(metrics.contains("# TYPE jobs_processed_total counter"), metrics);
    assertTrue(metrics.contains("# HELP jobs_processed_total Jobs finished, by queue"), metrics);
    assertTrue(metrics.contains("jobs_processed_total{queue=\"mail\"} 12"), metrics);
    assertTrue(metrics.contains("# TYPE jobs_pending gauge"), metrics);
    assertTrue(metrics.contains("# HELP jobs_pending Jobs waiting"), metrics);
    assertTrue(metrics.contains("jobs_pending 3"), metrics);
  }

  @Test
  void renderMetrics_rendersPluginHistograms() {
    register(
        "latency",
        collector -> {
          java.util.Map<String, String> labels = new java.util.LinkedHashMap<>();
          labels.put("route", "/orders");
          collector.histogram(
              "request_duration_seconds",
              "Request latency",
              labels,
              new double[] {0.01, 0.1},
              new long[] {2, 5},
              0.42);
        });

    String metrics = exporter.renderMetrics();

    assertTrue(metrics.contains("# TYPE request_duration_seconds histogram"), metrics);
    assertTrue(
        metrics.contains(
            "request_duration_seconds_bucket{route=\"/orders\",le=\"0.01\"} 2"),
        metrics);
    assertTrue(
        metrics.contains(
            "request_duration_seconds_bucket{route=\"/orders\",le=\"0.1\"} 5"),
        metrics);
    assertTrue(
        metrics.contains(
            "request_duration_seconds_bucket{route=\"/orders\",le=\"+Inf\"} 5"),
        metrics);
    assertTrue(metrics.contains("request_duration_seconds_sum{route=\"/orders\"} 0.42"), metrics);
    assertTrue(metrics.contains("request_duration_seconds_count{route=\"/orders\"} 5"), metrics);
  }

  @Test
  void renderMetrics_ignoresMalformedHistograms() {
    register(
        "bad",
        collector -> {
          // Length mismatch.
          collector.histogram("bad_hist_a", "bad", new double[] {1, 2}, new long[] {1}, 3);
          // Bounds not ascending.
          collector.histogram("bad_hist_b", "bad", new double[] {2, 1}, new long[] {1, 2}, 3);
          // Counts decreasing.
          collector.histogram("bad_hist_c", "bad", new double[] {1, 2}, new long[] {5, 2}, 3);
        });

    String metrics = exporter.renderMetrics();

    assertFalse(metrics.contains("bad_hist_a"), metrics);
    assertFalse(metrics.contains("bad_hist_b"), metrics);
    assertFalse(metrics.contains("bad_hist_c"), metrics);
  }

  @Test
  void renderMetrics_histogramOwnsTheLeLabel() {
    register(
        "le-clash",
        collector -> {
          java.util.Map<String, String> labels = new java.util.LinkedHashMap<>();
          labels.put("le", "user-supplied");
          collector.histogram(
              "hist_le", "clash", labels, new double[] {1}, new long[] {1}, 1);
        });

    String metrics = exporter.renderMetrics();

    assertFalse(metrics.contains("user-supplied"), metrics);
    assertTrue(metrics.contains("hist_le_bucket{le=\"1\"} 1"), metrics);
    assertTrue(metrics.contains("hist_le_bucket{le=\"+Inf\"} 1"), metrics);
  }

  @Test
  void renderMetrics_sanitizesPluginMetricNamesAndLabelNames() {
    register(
        "odd-names",
        collector -> {
          java.util.Map<String, String> labels = new java.util.HashMap<>();
          labels.put("9bad-label", "v");
          collector.gauge("my.app:requests", "Requests", labels, 1);
        });

    String metrics = exporter.renderMetrics();

    assertTrue(metrics.contains("my_app:requests{_bad_label=\"v\"} 1"), metrics);
    assertFalse(metrics.contains("my.app:requests"));
  }

  @Test
  void renderMetrics_skipsPluginSeriesInTheAgentNamespace() {
    register(
        "intruder",
        collector -> {
          collector.gauge("weavergirl_custom_total", "Reserved", 1);
          collector.gauge("plugin_custom_total", "Fine", 2);
        });

    String metrics = exporter.renderMetrics();

    assertFalse(metrics.contains("weavergirl_custom_total"), metrics);
    assertTrue(metrics.contains("plugin_custom_total 2"), metrics);
  }

  @Test
  void renderMetrics_isolatesAThrowingMetricSource() {
    register(
        "broken",
        collector -> {
          throw new IllegalStateException("metadata unavailable");
        });
    register("healthy", collector -> collector.gauge("healthy_metric", "Still collected", 5));

    String first = exporter.renderMetrics();
    String second = exporter.renderMetrics();

    assertTrue(first.contains("healthy_metric 5"), first);
    assertTrue(
        first.contains("weavergirl_metric_source_errors_total{source=\"broken\"} 1"), first);
    assertTrue(
        second.contains("weavergirl_metric_source_errors_total{source=\"broken\"} 2"), second);
  }

  @Test
  void renderMetrics_keepsTheFirstTypeWhenTwoSourcesClaimTheSameName() {
    register("a-first", collector -> collector.gauge("conflict_metric", "gauge", 1));
    register("b-second", collector -> collector.counter("conflict_metric", "counter", 2));

    String metrics = exporter.renderMetrics();

    assertTrue(metrics.contains("# TYPE conflict_metric gauge"), metrics);
    assertTrue(metrics.contains("conflict_metric 1"), metrics);
    assertFalse(metrics.contains("conflict_metric 2"), metrics);
  }

  @Test
  void renderMetrics_rendersPerInterceptorMetricsFromTheAgentView() {
    final com.github.cc11001100.weavergirl.api.status.InterceptorMetricsView view =
        new com.github.cc11001100.weavergirl.api.status.InterceptorMetricsView() {
          @Override
          public long getInvocations() {
            return 10;
          }

          @Override
          public long getErrors() {
            return 2;
          }

          @Override
          public long getTotalNanos() {
            return 5_000_000_000L;
          }

          @Override
          public long getMaxNanos() {
            return 2_000_000_000L;
          }

          @Override
          public long getSlowCalls() {
            return 1;
          }

          @Override
          public double getAverageNanos() {
            return 500_000_000.0;
          }

          @Override
          public double estimatePercentile(double percentile) {
            return 0;
          }

          @Override
          public long[] getLatencyBucketBoundsNanos() {
            return new long[] {1_000_000_000L, 10_000_000_000L, Long.MAX_VALUE};
          }

          @Override
          public long[] getLatencyBucketCounts() {
            return new long[] {6, 3, 1};
          }
        };
    com.github.cc11001100.weavergirl.api.status.AgentStatusHolder.set(
        new com.github.cc11001100.weavergirl.api.status.AgentStatusView() {
          @Override
          public long getTransformationCount() {
            return 0;
          }

          @Override
          public long getTransformationErrorCount() {
            return 0;
          }

          @Override
          public long getInterceptorInvocationCount() {
            return 10;
          }

          @Override
          public long getInterceptorErrorCount() {
            return 2;
          }

          @Override
          public int getRegisteredInterceptorCount() {
            return 1;
          }

          @Override
          public java.util.Map<String, ? extends com.github.cc11001100.weavergirl.api.status.InterceptorMetricsView>
              getInterceptorMetrics() {
            return java.util.Collections.singletonMap("jdbc.query", view);
          }
        });

    String metrics = exporter.renderMetrics();

    assertTrue(
        metrics.contains("weavergirl_interceptor_invocations_total{interceptor=\"jdbc.query\"} 10"),
        metrics);
    assertTrue(
        metrics.contains("weavergirl_interceptor_errors_total{interceptor=\"jdbc.query\"} 2"),
        metrics);
    assertTrue(
        metrics.contains("weavergirl_interceptor_slow_calls_total{interceptor=\"jdbc.query\"} 1"),
        metrics);
    assertTrue(
        metrics.contains(
            "weavergirl_interceptor_duration_seconds_total{interceptor=\"jdbc.query\"} 5"),
        metrics);
    assertTrue(
        metrics.contains(
            "# TYPE weavergirl_interceptor_duration_seconds histogram"),
        metrics);
    // Counts are non-cumulative and rendered as cumulative buckets, +Inf inclusive.
    assertTrue(
        metrics.contains(
            "weavergirl_interceptor_duration_seconds_bucket{interceptor=\"jdbc.query\",le=\"1\"}"
                + " 6"),
        metrics);
    assertTrue(
        metrics.contains(
            "weavergirl_interceptor_duration_seconds_bucket{interceptor=\"jdbc.query\",le=\"10\"}"
                + " 9"),
        metrics);
    assertTrue(
        metrics.contains(
            "weavergirl_interceptor_duration_seconds_bucket{interceptor=\"jdbc.query\",le=\"+Inf\"}"
                + " 10"),
        metrics);
    assertTrue(
        metrics.contains(
            "weavergirl_interceptor_duration_seconds_count{interceptor=\"jdbc.query\"} 10"),
        metrics);
  }

  @Test
  void renderMetrics_omitsTheInterceptorHistogramWhenNoViewKeepsBuckets() {
    com.github.cc11001100.weavergirl.api.status.InterceptorMetricsView view =
        new com.github.cc11001100.weavergirl.api.status.InterceptorMetricsView() {
          @Override
          public long getInvocations() {
            return 1;
          }

          @Override
          public long getErrors() {
            return 0;
          }

          @Override
          public long getTotalNanos() {
            return 0;
          }

          @Override
          public long getMaxNanos() {
            return 0;
          }

          @Override
          public long getSlowCalls() {
            return 0;
          }

          @Override
          public double getAverageNanos() {
            return 0;
          }

          @Override
          public double estimatePercentile(double percentile) {
            return 0;
          }
        };
    com.github.cc11001100.weavergirl.api.status.AgentStatusHolder.set(
        new com.github.cc11001100.weavergirl.api.status.AgentStatusView() {
          @Override
          public long getTransformationCount() {
            return 0;
          }

          @Override
          public long getTransformationErrorCount() {
            return 0;
          }

          @Override
          public long getInterceptorInvocationCount() {
            return 1;
          }

          @Override
          public long getInterceptorErrorCount() {
            return 0;
          }

          @Override
          public int getRegisteredInterceptorCount() {
            return 1;
          }

          @Override
          public java.util.Map<String, ? extends com.github.cc11001100.weavergirl.api.status.InterceptorMetricsView>
              getInterceptorMetrics() {
            return java.util.Collections.singletonMap("no-histogram", view);
          }
        });

    String metrics = exporter.renderMetrics();

    assertTrue(metrics.contains("weavergirl_interceptor_invocations_total{interceptor=\"no-histogram\"} 1"), metrics);
    assertFalse(metrics.contains("weavergirl_interceptor_duration_seconds_bucket"), metrics);
  }

  private static void register(
      String name,
      java.util.function.Consumer<com.github.cc11001100.weavergirl.api.observability.MetricCollector>
          body) {
    com.github.cc11001100.weavergirl.api.observability.MetricSources.register(
        new com.github.cc11001100.weavergirl.api.observability.MetricSource() {
          @Override
          public String name() {
            return name;
          }

          @Override
          public void collect(
              com.github.cc11001100.weavergirl.api.observability.MetricCollector collector) {
            body.accept(collector);
          }
        });
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
