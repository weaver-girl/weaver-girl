package com.github.cc11001100.weavergirl.core.metrics;

import com.github.cc11001100.weavergirl.api.event.InterceptorEvent;
import com.github.cc11001100.weavergirl.api.event.InterceptorEventListener;
import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Built-in Prometheus metrics exporter. Listens to InterceptorEvents and exposes metrics at a
 * /metrics HTTP endpoint in Prometheus text exposition format.
 *
 * <p>Enable with agent argument: {@code metricsPort=9400}
 *
 * <p>Exposed metrics:
 *
 * <ul>
 *   <li>{@code weavergirl_slow_operations_total} — counter of slow operations by plugin/type
 *   <li>{@code weavergirl_error_operations_total} — counter of errors by plugin
 *   <li>{@code weavergirl_operation_duration_ms_sum} — cumulative duration by plugin
 *   <li>{@code weavergirl_operation_duration_ms_count} — operation count by plugin
 *   <li>{@code weavergirl_iast_findings_total} — IAST slice findings by sink kind only
 * </ul>
 *
 * <p>Example output:
 *
 * <pre>
 * # HELP weavergirl_slow_operations_total Total number of slow operations detected
 * # TYPE weavergirl_slow_operations_total counter
 * weavergirl_slow_operations_total{plugin="jdbc",type="slow-query"} 5
 * weavergirl_slow_operations_total{plugin="servlet",type="slow-request"} 2
 * </pre>
 */
public class PrometheusExporter implements InterceptorEventListener {

  private static final String HELP_SLOW =
      "# HELP weavergirl_slow_operations_total Total number of slow operations detected";
  private static final String TYPE_SLOW = "# TYPE weavergirl_slow_operations_total counter";
  private static final String HELP_ERRORS =
      "# HELP weavergirl_error_operations_total Total number of error operations detected";
  private static final String TYPE_ERRORS = "# TYPE weavergirl_error_operations_total counter";
  private static final String HELP_DURATION_SUM =
      "# HELP weavergirl_operation_duration_ms_sum Cumulative operation duration in milliseconds";
  private static final String TYPE_DURATION_SUM =
      "# TYPE weavergirl_operation_duration_ms_sum counter";
  private static final String HELP_DURATION_COUNT =
      "# HELP weavergirl_operation_duration_ms_count Total number of operations tracked";
  private static final String TYPE_DURATION_COUNT =
      "# TYPE weavergirl_operation_duration_ms_count counter";
  private static final String HELP_IAST =
      "# HELP weavergirl_iast_findings_total IAST slice findings by sink kind";
  private static final String TYPE_IAST = "# TYPE weavergirl_iast_findings_total counter";

  // Key: plugin -> type -> counter
  private final ConcurrentMap<String, ConcurrentMap<String, AtomicLong>> slowCounters =
      new ConcurrentHashMap<String, ConcurrentMap<String, AtomicLong>>();
  private final ConcurrentMap<String, AtomicLong> errorCounters =
      new ConcurrentHashMap<String, AtomicLong>();
  private final ConcurrentMap<String, AtomicLong> durationSum =
      new ConcurrentHashMap<String, AtomicLong>();
  private final ConcurrentMap<String, AtomicLong> durationCount =
      new ConcurrentHashMap<String, AtomicLong>();
  private final ConcurrentMap<String, AtomicLong> iastFindings =
      new ConcurrentHashMap<String, AtomicLong>();

  private volatile HttpServer server;

  public PrometheusExporter() {}

  /**
   * Start the Prometheus metrics HTTP server.
   *
   * @param port the port to listen on
   * @throws IOException if the server cannot bind
   */
  public void start(int port) throws IOException {
    server = HttpServer.create(new InetSocketAddress(port), 0);
    server.createContext(
        "/metrics",
        exchange -> {
          String body = renderMetrics();
          byte[] bytes = body.getBytes("UTF-8");
          exchange
              .getResponseHeaders()
              .set("Content-Type", "text/plain; version=0.0.4; charset=utf-8");
          exchange.sendResponseHeaders(200, bytes.length);
          try (OutputStream os = exchange.getResponseBody()) {
            os.write(bytes);
          }
        });
    server.setExecutor(null);
    server.start();
  }

  /** Stop the metrics HTTP server. */
  public void stop() {
    if (server != null) {
      server.stop(0);
      server = null;
    }
  }

  @Override
  public void onEvent(InterceptorEvent event) {
    String plugin = event.getPlugin();
    if (plugin == null) plugin = "unknown";
    String type = event.getType();
    if (type == null) type = "unknown";

    // Track slow operations
    if (type.startsWith("slow-")) {
      ConcurrentMap<String, AtomicLong> typeMap = slowCounters.get(plugin);
      if (typeMap == null) {
        typeMap = new ConcurrentHashMap<String, AtomicLong>();
        ConcurrentMap<String, AtomicLong> existing = slowCounters.putIfAbsent(plugin, typeMap);
        if (existing != null) typeMap = existing;
      }
      AtomicLong counter = typeMap.get(type);
      if (counter == null) {
        counter = new AtomicLong(0);
        AtomicLong existing = typeMap.putIfAbsent(type, counter);
        if (existing != null) counter = existing;
      }
      counter.incrementAndGet();
    }

    // Track errors
    if (type.endsWith("-error")) {
      AtomicLong counter = errorCounters.get(plugin);
      if (counter == null) {
        counter = new AtomicLong(0);
        AtomicLong existing = errorCounters.putIfAbsent(plugin, counter);
        if (existing != null) counter = existing;
      }
      counter.incrementAndGet();
    }

    if ("iast-finding".equals(type)) {
      String sink = event.getAttributes().get("sink");
      if (sink == null || sink.isEmpty()) {
        sink = "unknown";
      }
      AtomicLong counter = iastFindings.get(sink);
      if (counter == null) {
        counter = new AtomicLong(0);
        AtomicLong existing = iastFindings.putIfAbsent(sink, counter);
        if (existing != null) {
          counter = existing;
        }
      }
      counter.incrementAndGet();
    }

    // Track duration
    long duration = event.getDurationMs();
    if (duration > 0) {
      AtomicLong sum = durationSum.get(plugin);
      if (sum == null) {
        sum = new AtomicLong(0);
        AtomicLong existing = durationSum.putIfAbsent(plugin, sum);
        if (existing != null) sum = existing;
      }
      sum.addAndGet(duration);

      AtomicLong count = durationCount.get(plugin);
      if (count == null) {
        count = new AtomicLong(0);
        AtomicLong existing = durationCount.putIfAbsent(plugin, count);
        if (existing != null) count = existing;
      }
      count.incrementAndGet();
    }
  }

  /** Render all metrics in Prometheus text exposition format. */
  String renderMetrics() {
    StringBuilder sb = new StringBuilder();

    // Slow operations
    sb.append(HELP_SLOW).append("\n");
    sb.append(TYPE_SLOW).append("\n");
    for (ConcurrentMap.Entry<String, ConcurrentMap<String, AtomicLong>> pluginEntry :
        slowCounters.entrySet()) {
      for (ConcurrentMap.Entry<String, AtomicLong> typeEntry : pluginEntry.getValue().entrySet()) {
        sb.append("weavergirl_slow_operations_total{plugin=\"")
            .append(escape(pluginEntry.getKey()))
            .append("\",type=\"")
            .append(escape(typeEntry.getKey()))
            .append("\"} ")
            .append(typeEntry.getValue().get())
            .append("\n");
      }
    }
    sb.append("\n");

    // Error operations
    sb.append(HELP_ERRORS).append("\n");
    sb.append(TYPE_ERRORS).append("\n");
    for (ConcurrentMap.Entry<String, AtomicLong> entry : errorCounters.entrySet()) {
      sb.append("weavergirl_error_operations_total{plugin=\"")
          .append(escape(entry.getKey()))
          .append("\"} ")
          .append(entry.getValue().get())
          .append("\n");
    }
    sb.append("\n");

    // Duration sum
    sb.append(HELP_DURATION_SUM).append("\n");
    sb.append(TYPE_DURATION_SUM).append("\n");
    for (ConcurrentMap.Entry<String, AtomicLong> entry : durationSum.entrySet()) {
      sb.append("weavergirl_operation_duration_ms_sum{plugin=\"")
          .append(escape(entry.getKey()))
          .append("\"} ")
          .append(entry.getValue().get())
          .append("\n");
    }
    sb.append("\n");

    // Duration count
    sb.append(HELP_DURATION_COUNT).append("\n");
    sb.append(TYPE_DURATION_COUNT).append("\n");
    for (ConcurrentMap.Entry<String, AtomicLong> entry : durationCount.entrySet()) {
      sb.append("weavergirl_operation_duration_ms_count{plugin=\"")
          .append(escape(entry.getKey()))
          .append("\"} ")
          .append(entry.getValue().get())
          .append("\n");
    }
    sb.append("\n");

    if (!iastFindings.isEmpty()) {
      sb.append(HELP_IAST).append("\n");
      sb.append(TYPE_IAST).append("\n");
      for (ConcurrentMap.Entry<String, AtomicLong> entry : iastFindings.entrySet()) {
        sb.append("weavergirl_iast_findings_total{sink=\"")
            .append(escape(entry.getKey()))
            .append("\"} ")
            .append(entry.getValue().get())
            .append("\n");
      }
      sb.append("\n");
    }

    return sb.toString();
  }

  private String escape(String s) {
    if (s == null) return "";
    return s.replace("\\", "\\\\").replace("\"", "\\\"").replace("\n", "\\n");
  }
}
