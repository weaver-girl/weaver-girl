package com.github.cc11001100.weavergirl.runtime.metrics;

import com.github.cc11001100.weavergirl.api.event.InterceptorEvent;
import com.github.cc11001100.weavergirl.api.event.InterceptorEventListener;
import com.github.cc11001100.weavergirl.api.metrics.MetricRegistry;
import com.github.cc11001100.weavergirl.api.metrics.MetricSnapshot;
import com.github.cc11001100.weavergirl.api.status.AgentStatusHolder;
import com.github.cc11001100.weavergirl.api.status.AgentStatusView;
import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.io.OutputStream;
import java.lang.management.ClassLoadingMXBean;
import java.lang.management.GarbageCollectorMXBean;
import java.lang.management.ManagementFactory;
import java.lang.management.MemoryMXBean;
import java.lang.management.MemoryUsage;
import java.lang.management.RuntimeMXBean;
import java.lang.management.ThreadMXBean;
import java.net.InetSocketAddress;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Built-in Prometheus metrics exporter. Listens to InterceptorEvents and exposes metrics at a
 * /metrics HTTP endpoint in Prometheus text exposition format.
 *
 * <p>Enable with agent argument: {@code metricsPort=9400}
 *
 * <p>Exposed metrics fall into three groups, all rendered on every scrape:
 *
 * <ul>
 *   <li>Interception events: {@code weavergirl_events_total} (every event by plugin/type, the
 *       denominator for ratios), {@code weavergirl_slow_operations_total}, {@code
 *       weavergirl_error_operations_total} (counters by plugin/type), {@code
 *       weavergirl_operation_duration_ms_sum}/{@code _count} (cumulative duration by plugin) and
 *       {@code weavergirl_iast_findings_total} (IAST findings by sink kind)
 *   <li>Agent self-metrics, read from {@link AgentStatusHolder}: transformations, transformation
 *       errors, interceptor invocations, interceptor errors and registered interceptor count
 *   <li>JVM metrics from {@code java.lang.management}: heap/non-heap memory, threads, classes, GC
 *       count/time and uptime
 *   <li>Business metrics from {@link MetricRegistry}: one series per window snapshot, carrying
 *       count/sum/avg/min/max and p50/p90/p95/p99 as {@code weavergirl_metric_*} gauges with the
 *       original metric name and labels attached
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
  private final ConcurrentMap<String, ConcurrentMap<String, AtomicLong>> eventCounters =
      new ConcurrentHashMap<String, ConcurrentMap<String, AtomicLong>>();
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

    // Track every event so dashboards can compute error/slow ratios against a denominator.
    increment(eventCounters, plugin, type);

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

  private void increment(
      ConcurrentMap<String, ConcurrentMap<String, AtomicLong>> counters,
      String plugin,
      String type) {
    ConcurrentMap<String, AtomicLong> typeMap = counters.get(plugin);
    if (typeMap == null) {
      typeMap = new ConcurrentHashMap<String, AtomicLong>();
      ConcurrentMap<String, AtomicLong> existing = counters.putIfAbsent(plugin, typeMap);
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

  /** Render all metrics in Prometheus text exposition format. */
  String renderMetrics() {
    StringBuilder sb = new StringBuilder();

    // All intercepted events, by plugin and event type.
    sb.append("# HELP weavergirl_events_total Interceptor events delivered, by plugin and type\n");
    sb.append("# TYPE weavergirl_events_total counter\n");
    for (ConcurrentMap.Entry<String, ConcurrentMap<String, AtomicLong>> pluginEntry :
        eventCounters.entrySet()) {
      for (ConcurrentMap.Entry<String, AtomicLong> typeEntry : pluginEntry.getValue().entrySet()) {
        sb.append("weavergirl_events_total{plugin=\"")
            .append(escape(pluginEntry.getKey()))
            .append("\",type=\"")
            .append(escape(typeEntry.getKey()))
            .append("\"} ")
            .append(typeEntry.getValue().get())
            .append("\n");
      }
    }
    sb.append("\n");

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

    appendAgentMetrics(sb);
    appendJvmMetrics(sb);
    appendRegistryMetrics(sb);

    return sb.toString();
  }

  /**
   * The agent's own counters, published by the engine through {@link AgentStatusHolder}.
   *
   * <p>Absent (no engine installed, e.g. when the plugin is used standalone) means no series, not
   * zero-valued ones — a scrape must never claim the agent is running when it isn't.
   */
  private void appendAgentMetrics(StringBuilder sb) {
    AgentStatusView status;
    try {
      status = AgentStatusHolder.get();
    } catch (RuntimeException e) {
      return;
    }
    if (status == null) {
      return;
    }
    try {
      sb.append("# HELP weavergirl_agent_transformations_total Classes transformed by the agent\n");
      sb.append("# TYPE weavergirl_agent_transformations_total counter\n");
      sb.append("weavergirl_agent_transformations_total ")
          .append(status.getTransformationCount())
          .append("\n");
      sb.append(
          "# HELP weavergirl_agent_transformation_errors_total Class transformations that failed\n");
      sb.append("# TYPE weavergirl_agent_transformation_errors_total counter\n");
      sb.append("weavergirl_agent_transformation_errors_total ")
          .append(status.getTransformationErrorCount())
          .append("\n");
      sb.append(
          "# HELP weavergirl_agent_interceptor_invocations_total Interceptor advice invocations\n");
      sb.append("# TYPE weavergirl_agent_interceptor_invocations_total counter\n");
      sb.append("weavergirl_agent_interceptor_invocations_total ")
          .append(status.getInterceptorInvocationCount())
          .append("\n");
      sb.append(
          "# HELP weavergirl_agent_interceptor_errors_total Interceptor advice invocations that"
              + " threw\n");
      sb.append("# TYPE weavergirl_agent_interceptor_errors_total counter\n");
      sb.append("weavergirl_agent_interceptor_errors_total ")
          .append(status.getInterceptorErrorCount())
          .append("\n");
      sb.append(
          "# HELP weavergirl_agent_registered_interceptors Registered interceptor definitions\n");
      sb.append("# TYPE weavergirl_agent_registered_interceptors gauge\n");
      sb.append("weavergirl_agent_registered_interceptors ")
          .append(status.getRegisteredInterceptorCount())
          .append("\n\n");
    } catch (RuntimeException e) {
      // A misbehaving status view must not take the whole scrape down.
    }
  }

  /** JVM gauges: memory, threads, classes, GC and uptime. */
  private void appendJvmMetrics(StringBuilder sb) {
    try {
      MemoryMXBean memory = ManagementFactory.getMemoryMXBean();
      sb.append("# HELP weavergirl_jvm_memory_used_bytes Bytes of JVM memory currently in use\n");
      sb.append("# TYPE weavergirl_jvm_memory_used_bytes gauge\n");
      sb.append(
          "# HELP weavergirl_jvm_memory_committed_bytes Bytes of JVM memory committed for use\n");
      sb.append("# TYPE weavergirl_jvm_memory_committed_bytes gauge\n");
      sb.append("# HELP weavergirl_jvm_memory_max_bytes Maximum bytes the JVM may use\n");
      sb.append("# TYPE weavergirl_jvm_memory_max_bytes gauge\n");
      appendMemoryUsage(sb, "heap", memory.getHeapMemoryUsage());
      appendMemoryUsage(sb, "nonheap", memory.getNonHeapMemoryUsage());

      ThreadMXBean threads = ManagementFactory.getThreadMXBean();
      sb.append("# HELP weavergirl_jvm_threads_current Live JVM threads\n");
      sb.append("# TYPE weavergirl_jvm_threads_current gauge\n");
      sb.append("weavergirl_jvm_threads_current ").append(threads.getThreadCount()).append("\n");
      sb.append("# HELP weavergirl_jvm_threads_peak Peak live JVM threads\n");
      sb.append("# TYPE weavergirl_jvm_threads_peak gauge\n");
      sb.append("weavergirl_jvm_threads_peak ").append(threads.getPeakThreadCount()).append("\n\n");

      ClassLoadingMXBean classes = ManagementFactory.getClassLoadingMXBean();
      sb.append("# HELP weavergirl_jvm_classes_loaded Classes currently loaded\n");
      sb.append("# TYPE weavergirl_jvm_classes_loaded gauge\n");
      sb.append("weavergirl_jvm_classes_loaded ")
          .append(classes.getLoadedClassCount())
          .append("\n\n");

      RuntimeMXBean runtime = ManagementFactory.getRuntimeMXBean();
      sb.append("# HELP weavergirl_jvm_uptime_seconds JVM uptime in seconds\n");
      sb.append("# TYPE weavergirl_jvm_uptime_seconds gauge\n");
      sb.append("weavergirl_jvm_uptime_seconds ")
          .append(formatNumber(runtime.getUptime() / 1000.0))
          .append("\n\n");

      sb.append("# HELP weavergirl_jvm_gc_collection_count_total Completed garbage collections\n");
      sb.append("# TYPE weavergirl_jvm_gc_collection_count_total counter\n");
      for (GarbageCollectorMXBean gc : ManagementFactory.getGarbageCollectorMXBeans()) {
        if (gc.getCollectionCount() < 0) {
          continue;
        }
        sb.append("weavergirl_jvm_gc_collection_count_total{gc=\"")
            .append(escape(gc.getName()))
            .append("\"} ")
            .append(gc.getCollectionCount())
            .append("\n");
      }
      sb.append(
          "# HELP weavergirl_jvm_gc_collection_time_seconds_total Time spent collecting garbage\n");
      sb.append("# TYPE weavergirl_jvm_gc_collection_time_seconds_total counter\n");
      for (GarbageCollectorMXBean gc : ManagementFactory.getGarbageCollectorMXBeans()) {
        if (gc.getCollectionTime() < 0) {
          continue;
        }
        sb.append("weavergirl_jvm_gc_collection_time_seconds_total{gc=\"")
            .append(escape(gc.getName()))
            .append("\"} ")
            .append(formatNumber(gc.getCollectionTime() / 1000.0))
            .append("\n");
      }
      sb.append("\n");
    } catch (RuntimeException e) {
      // ManagementFactory is best-effort: keep whatever was rendered before the failure.
    }
  }

  private void appendMemoryUsage(StringBuilder sb, String area, MemoryUsage usage) {
    if (usage == null) {
      return;
    }
    sb.append("weavergirl_jvm_memory_used_bytes{area=\"")
        .append(area)
        .append("\"} ")
        .append(usage.getUsed())
        .append("\n");
    sb.append("weavergirl_jvm_memory_committed_bytes{area=\"")
        .append(area)
        .append("\"} ")
        .append(usage.getCommitted())
        .append("\n");
    // -1 means the JVM does not define a maximum for this pool.
    if (usage.getMax() >= 0) {
      sb.append("weavergirl_jvm_memory_max_bytes{area=\"")
          .append(area)
          .append("\"} ")
          .append(usage.getMax())
          .append("\n");
    }
  }

  /**
   * Business metrics recorded through {@link MetricRegistry}, one series per snapshot.
   *
   * <p>The registry accepts arbitrary names such as {@code request.duration_ms}, which are not
   * valid Prometheus metric names, so the exported metric is always {@code weavergirl_metric_*} and
   * the original name travels as the {@code metric} label alongside the snapshot's labels.
   */
  private void appendRegistryMetrics(StringBuilder sb) {
    Map<String, MetricSnapshot> snapshots;
    try {
      snapshots = MetricRegistry.snapshotAll();
    } catch (RuntimeException e) {
      return;
    }
    boolean hasData = false;
    for (MetricSnapshot snapshot : snapshots.values()) {
      if (snapshot != null && snapshot.getCount() > 0) {
        hasData = true;
        break;
      }
    }
    if (!hasData) {
      // Idle windows (all samples evicted) produce no series rather than stale zeros.
      return;
    }

    String[] suffixes = {"count", "sum", "avg", "min", "max", "p50", "p90", "p95", "p99"};
    for (String suffix : suffixes) {
      sb.append("# HELP weavergirl_metric_")
          .append(suffix)
          .append(" Windowed value of a MetricRegistry business metric\n");
      sb.append("# TYPE weavergirl_metric_").append(suffix).append(" gauge\n");
      for (Map.Entry<String, MetricSnapshot> entry : snapshots.entrySet()) {
        MetricSnapshot snapshot = entry.getValue();
        if (snapshot == null || snapshot.getCount() <= 0) {
          continue;
        }
        sb.append("weavergirl_metric_")
            .append(suffix)
            .append("{")
            .append(registryLabels(entry.getKey(), snapshot))
            .append("} ")
            .append(formatNumber(valueFor(snapshot, suffix)))
            .append("\n");
      }
    }
    sb.append("\n");
  }

  private static double valueFor(MetricSnapshot snapshot, String suffix) {
    switch (suffix) {
      case "count":
        return snapshot.getCount();
      case "sum":
        return snapshot.getSum();
      case "avg":
        return snapshot.getAvg();
      case "min":
        return snapshot.getMin();
      case "max":
        return snapshot.getMax();
      case "p50":
        return snapshot.getPercentile(0.5);
      case "p90":
        return snapshot.getPercentile(0.9);
      case "p95":
        return snapshot.getPercentile(0.95);
      case "p99":
        return snapshot.getPercentile(0.99);
      default:
        return 0;
    }
  }

  /**
   * Build the label set for one registry snapshot: {@code metric="<base name>"} plus every label
   * the caller recorded, with label names sanitized to the Prometheus grammar.
   */
  private String registryLabels(String key, MetricSnapshot snapshot) {
    StringBuilder labels = new StringBuilder();
    labels.append("metric=\"").append(escape(baseMetricName(key))).append("\"");
    for (Map.Entry<String, String> label : snapshot.getLabels().entrySet()) {
      labels
          .append(",")
          .append(sanitizeLabelName(label.getKey()))
          .append("=\"")
          .append(escape(label.getValue()))
          .append("\"");
    }
    return labels.toString();
  }

  private static String baseMetricName(String key) {
    int brace = key.indexOf('{');
    return brace < 0 ? key : key.substring(0, brace);
  }

  private static String sanitizeLabelName(String name) {
    if (name == null || name.isEmpty()) {
      return "label";
    }
    StringBuilder out = new StringBuilder(name.length());
    for (int i = 0; i < name.length(); i++) {
      char c = name.charAt(i);
      boolean valid =
          (c >= 'a' && c <= 'z')
              || (c >= 'A' && c <= 'Z')
              || c == '_'
              || (i > 0 && c >= '0' && c <= '9');
      out.append(valid ? c : '_');
    }
    return out.toString();
  }

  /** Prometheus numbers: integers stay integral, fractions use plain decimal notation. */
  static String formatNumber(double value) {
    if (Double.isNaN(value)) {
      return "NaN";
    }
    if (Double.isInfinite(value)) {
      return value > 0 ? "+Inf" : "-Inf";
    }
    if (value == Math.rint(value) && Math.abs(value) < 1e15) {
      return Long.toString((long) value);
    }
    return Double.toString(value);
  }

  private String escape(String s) {
    if (s == null) return "";
    return s.replace("\\", "\\\\").replace("\"", "\\\"").replace("\n", "\\n");
  }
}
