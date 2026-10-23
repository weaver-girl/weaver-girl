package com.github.cc11001100.weavergirl.runtime.metrics;

import com.github.cc11001100.weavergirl.api.event.InterceptorEvent;
import com.github.cc11001100.weavergirl.api.event.InterceptorEventListener;
import com.github.cc11001100.weavergirl.api.metrics.MetricRegistry;
import com.github.cc11001100.weavergirl.api.metrics.MetricSnapshot;
import com.github.cc11001100.weavergirl.api.observability.MetricCollector;
import com.github.cc11001100.weavergirl.api.observability.MetricSource;
import com.github.cc11001100.weavergirl.api.observability.MetricSources;
import com.github.cc11001100.weavergirl.api.observability.SpanSinkStats;
import com.github.cc11001100.weavergirl.api.observability.SpanSinks;
import com.github.cc11001100.weavergirl.api.plugin.PluginHealth;
import com.github.cc11001100.weavergirl.api.plugin.PluginHealthRegistry;
import com.github.cc11001100.weavergirl.api.status.AgentStatusHolder;
import com.github.cc11001100.weavergirl.api.status.AgentStatusView;
import com.github.cc11001100.weavergirl.api.status.InterceptorMetricsView;
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
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Built-in Prometheus metrics exporter. Listens to InterceptorEvents and exposes metrics at a
 * /metrics HTTP endpoint in Prometheus text exposition format.
 *
 * <p>Enable with agent argument: {@code metricsPort=9400}
 *
 * <p>Exposed metrics fall into these groups, all rendered on every scrape:
 *
 * <ul>
 *   <li>Interception events: {@code weavergirl_events_total} (every event by plugin/type, the
 *       denominator for ratios), {@code weavergirl_slow_operations_total}, {@code
 *       weavergirl_error_operations_total} (counters by plugin/type), {@code
 *       weavergirl_operation_duration_ms_sum}/{@code _count} (cumulative duration by plugin) and
 *       {@code weavergirl_iast_findings_total} (IAST findings by sink kind)
 *   <li>Per-interceptor metrics from {@link AgentStatusHolder}: invocations, errors, slow calls,
 *       total callback time and a latency histogram, one series per definition
 *   <li>Agent self-metrics, read from {@link AgentStatusHolder}: transformations, transformation
 *       errors, interceptor invocations, interceptor errors and registered interceptor count
 *   <li>Plugin health from {@link PluginHealthRegistry}: one sample per plugin that reported,
 *       carrying the current status in a label
 *   <li>JVM metrics from {@code java.lang.management}: heap/non-heap memory, threads, classes, GC
 *       count/time and uptime
 *   <li>Business metrics from {@link MetricRegistry}: one series per window snapshot, carrying
 *       count/sum/avg/min/max and p50/p90/p95/p99 as {@code weavergirl_metric_*} gauges with the
 *       original metric name and labels attached
 *   <li>Plugin metrics from {@link MetricSources}: each registered source is collected on this
 *       scrape and its gauges/counters are rendered under the source's own names, with failures
 *       counted in {@code weavergirl_metric_source_errors_total}
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

  /** Scrapes in which a plugin's {@link MetricSource#collect} threw, by source name. */
  private final ConcurrentMap<String, AtomicLong> metricSourceErrors =
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

    appendInterceptorMetrics(sb);
    appendAgentMetrics(sb);
    appendPluginHealthMetrics(sb);
    appendSpanSinkMetrics(sb);
    appendJvmMetrics(sb);
    appendRegistryMetrics(sb);
    appendMetricSources(sb);

    return sb.toString();
  }

  /**
   * Per-interceptor counters from {@link AgentStatusView}: how often each woven hook point ran, how
   * often its callback threw, how much time it spent and how many calls were slow.
   *
   * <p>The number of series is bounded by the number of registered definitions, so this is safe to
   * render on every scrape. A view that keeps no histogram contributes only the counters.
   */
  private void appendInterceptorMetrics(StringBuilder sb) {
    AgentStatusView status;
    try {
      status = AgentStatusHolder.get();
    } catch (RuntimeException e) {
      return;
    }
    if (status == null) {
      return;
    }
    Map<String, ? extends InterceptorMetricsView> metrics;
    try {
      metrics = status.getInterceptorMetrics();
    } catch (RuntimeException e) {
      return;
    }
    if (metrics == null || metrics.isEmpty()) {
      return;
    }

    List<String> names = new ArrayList<String>(metrics.keySet());
    Collections.sort(names);

    sb.append(
        "# HELP weavergirl_interceptor_invocations_total Interceptor callbacks invoked, by"
            + " definition\n");
    sb.append("# TYPE weavergirl_interceptor_invocations_total counter\n");
    for (String name : names) {
      InterceptorMetricsView view = metrics.get(name);
      if (view == null) {
        continue;
      }
      sb.append("weavergirl_interceptor_invocations_total{interceptor=\"")
          .append(escape(name))
          .append("\"} ")
          .append(view.getInvocations())
          .append("\n");
    }

    sb.append(
        "# HELP weavergirl_interceptor_errors_total Interceptor callbacks that threw, by"
            + " definition\n");
    sb.append("# TYPE weavergirl_interceptor_errors_total counter\n");
    for (String name : names) {
      InterceptorMetricsView view = metrics.get(name);
      if (view == null) {
        continue;
      }
      sb.append("weavergirl_interceptor_errors_total{interceptor=\"")
          .append(escape(name))
          .append("\"} ")
          .append(view.getErrors())
          .append("\n");
    }

    sb.append(
        "# HELP weavergirl_interceptor_slow_calls_total Interceptor callbacks above the slow-hook"
            + " threshold\n");
    sb.append("# TYPE weavergirl_interceptor_slow_calls_total counter\n");
    for (String name : names) {
      InterceptorMetricsView view = metrics.get(name);
      if (view == null) {
        continue;
      }
      sb.append("weavergirl_interceptor_slow_calls_total{interceptor=\"")
          .append(escape(name))
          .append("\"} ")
          .append(view.getSlowCalls())
          .append("\n");
    }

    sb.append(
        "# HELP weavergirl_interceptor_duration_seconds_total Time spent inside interceptor"
            + " callbacks\n");
    sb.append("# TYPE weavergirl_interceptor_duration_seconds_total counter\n");
    for (String name : names) {
      InterceptorMetricsView view = metrics.get(name);
      if (view == null) {
        continue;
      }
      sb.append("weavergirl_interceptor_duration_seconds_total{interceptor=\"")
          .append(escape(name))
          .append("\"} ")
          .append(formatNumber(view.getTotalNanos() / 1_000_000_000.0))
          .append("\n");
    }

    if (hasLatencyHistogram(metrics)) {
      sb.append(
          "# HELP weavergirl_interceptor_duration_seconds Callback latency distribution, by"
              + " definition\n");
      sb.append("# TYPE weavergirl_interceptor_duration_seconds histogram\n");
      for (String name : names) {
        InterceptorMetricsView view = metrics.get(name);
        if (view == null) {
          continue;
        }
        appendLatencyHistogram(sb, name, view);
      }
    }
    sb.append("\n");
  }

  private static boolean hasLatencyHistogram(
      Map<String, ? extends InterceptorMetricsView> metrics) {
    for (InterceptorMetricsView view : metrics.values()) {
      if (view != null && view.getLatencyBucketBoundsNanos().length > 0) {
        return true;
      }
    }
    return false;
  }

  private static void appendLatencyHistogram(
      StringBuilder sb, String name, InterceptorMetricsView view) {
    long[] bounds = view.getLatencyBucketBoundsNanos();
    long[] counts = view.getLatencyBucketCounts();
    if (bounds.length == 0 || bounds.length != counts.length) {
      return;
    }
    long cumulative = 0;
    for (int i = 0; i < bounds.length; i++) {
      cumulative += counts[i];
      String le =
          bounds[i] == Long.MAX_VALUE
              ? "+Inf"
              : formatNumber(bounds[i] / 1_000_000_000.0);
      sb.append("weavergirl_interceptor_duration_seconds_bucket{interceptor=\"")
          .append(escape(name))
          .append("\",le=\"")
          .append(le)
          .append("\"} ")
          .append(cumulative)
          .append("\n");
    }
    sb.append("weavergirl_interceptor_duration_seconds_sum{interceptor=\"")
        .append(escape(name))
        .append("\"} ")
        .append(formatNumber(view.getTotalNanos() / 1_000_000_000.0))
        .append("\n");
    sb.append("weavergirl_interceptor_duration_seconds_count{interceptor=\"")
        .append(escape(name))
        .append("\"} ")
        .append(view.getInvocations())
        .append("\n");
  }

  /**
   * Metrics plugins contribute through {@link MetricSources}, read on this scrape.
   *
   * <p>Each source is collected in isolation; one that throws is counted in {@code
   * weavergirl_metric_source_errors_total} and the rest still render. Names in the {@code
   * weavergirl_} namespace belong to the agent and are skipped, and a name claimed by an earlier
   * source wins over a later duplicate. Output is sorted so a scrape is stable.
   */
  private void appendMetricSources(StringBuilder sb) {
    List<MetricSource> sources;
    try {
      sources = MetricSources.getSources();
    } catch (RuntimeException e) {
      return;
    }
    if (sources.isEmpty() && metricSourceErrors.isEmpty()) {
      return;
    }

    List<MetricSource> ordered = new ArrayList<MetricSource>(sources);
    Collections.sort(
        ordered,
        new Comparator<MetricSource>() {
          @Override
          public int compare(MetricSource a, MetricSource b) {
            return sourceName(a).compareTo(sourceName(b));
          }
        });

    Map<String, MetricFamily> families = new LinkedHashMap<String, MetricFamily>();
    for (MetricSource source : ordered) {
      try {
        source.collect(new RecordingCollector(families));
      } catch (RuntimeException e) {
        String name = sourceName(source);
        AtomicLong errors = metricSourceErrors.get(name);
        if (errors == null) {
          AtomicLong created = new AtomicLong();
          AtomicLong existing = metricSourceErrors.putIfAbsent(name, created);
          errors = existing != null ? existing : created;
        }
        errors.incrementAndGet();
      }
    }

    for (MetricFamily family : families.values()) {
      sb.append("# HELP ").append(family.name).append(" ").append(escapeHelp(family.help)).append("\n");
      sb.append("# TYPE ").append(family.name).append(" ").append(family.type).append("\n");
      List<String> labelBlocks = new ArrayList<String>(family.series.keySet());
      Collections.sort(labelBlocks);
      for (String labelBlock : labelBlocks) {
        sb.append(family.name).append(labelBlock).append(" ")
            .append(formatNumber(family.series.get(labelBlock))).append("\n");
      }
      sb.append("\n");
    }

    if (!metricSourceErrors.isEmpty()) {
      sb.append(
          "# HELP weavergirl_metric_source_errors_total MetricSource collections that threw\n");
      sb.append("# TYPE weavergirl_metric_source_errors_total counter\n");
      List<String> errorNames = new ArrayList<String>(metricSourceErrors.keySet());
      Collections.sort(errorNames);
      for (String name : errorNames) {
        sb.append("weavergirl_metric_source_errors_total{source=\"")
            .append(escape(name))
            .append("\"} ")
            .append(metricSourceErrors.get(name).get())
            .append("\n");
      }
      sb.append("\n");
    }
  }

  private static String sourceName(MetricSource source) {
    String name = source.name();
    return name == null || name.trim().isEmpty() ? "unknown" : name.trim();
  }

  /** One metric family collected from plugin sources during this scrape. */
  private static final class MetricFamily {
    final String name;
    final String type;
    final Map<String, Double> series = new LinkedHashMap<String, Double>();
    String help = "";

    MetricFamily(String name, String help, String type) {
      this.name = name;
      this.type = type;
      if (help != null) {
        this.help = help;
      }
    }
  }

  /** Collects one source's samples into families, enforcing name and label rules. */
  private static final class RecordingCollector implements MetricCollector {
    private final Map<String, MetricFamily> families;

    RecordingCollector(Map<String, MetricFamily> families) {
      this.families = families;
    }

    @Override
    public void gauge(String name, String help, double value) {
      record(name, help, "gauge", Collections.<String, String>emptyMap(), value);
    }

    @Override
    public void gauge(
        String name, String help, Map<String, String> labels, double value) {
      record(name, help, "gauge", labels, value);
    }

    @Override
    public void counter(String name, String help, double value) {
      record(name, help, "counter", Collections.<String, String>emptyMap(), value);
    }

    @Override
    public void counter(
        String name, String help, Map<String, String> labels, double value) {
      record(name, help, "counter", labels, value);
    }

    private void record(
        String name, String help, String type, Map<String, String> labels, double value) {
      String metricName = sanitizeMetricName(name);
      if (metricName == null || metricName.startsWith("weavergirl_")) {
        return;
      }
      MetricFamily family = families.get(metricName);
      if (family == null) {
        family = new MetricFamily(metricName, help, type);
        families.put(metricName, family);
      } else if (!family.type.equals(type)) {
        // Same name, different type: keep the first and drop the conflict rather than emit an
        // exposition Prometheus will reject.
        return;
      } else if (family.help.isEmpty() && help != null && !help.isEmpty()) {
        family.help = help;
      }
      String labelBlock = renderLabelBlock(labels);
      if (!family.series.containsKey(labelBlock)) {
        family.series.put(labelBlock, value);
      }
    }
  }

  /**
   * Prometheus metric-name grammar: {@code [a-zA-Z_:][a-zA-Z0-9_:]*}. Invalid characters become
   * {@code _}; a null or blank name is dropped.
   */
  static String sanitizeMetricName(String name) {
    if (name == null) {
      return null;
    }
    String trimmed = name.trim();
    if (trimmed.isEmpty()) {
      return null;
    }
    StringBuilder out = new StringBuilder(trimmed.length());
    for (int i = 0; i < trimmed.length(); i++) {
      char c = trimmed.charAt(i);
      boolean valid =
          (c >= 'a' && c <= 'z')
              || (c >= 'A' && c <= 'Z')
              || c == '_'
              || c == ':'
              || (i > 0 && c >= '0' && c <= '9');
      out.append(valid ? c : '_');
    }
    return out.toString();
  }

  /** Render a sorted, escaped label block such as {@code {route="/a",status="200"}}. */
  private static String renderLabelBlock(Map<String, String> labels) {
    if (labels == null || labels.isEmpty()) {
      return "";
    }
    TreeMap<String, String> sorted = new TreeMap<String, String>();
    for (Map.Entry<String, String> entry : labels.entrySet()) {
      if (entry.getKey() == null || entry.getValue() == null) {
        continue;
      }
      String labelName = sanitizeLabelName(entry.getKey());
      if ("__name__".equals(labelName)) {
        continue;
      }
      sorted.put(labelName, entry.getValue());
    }
    if (sorted.isEmpty()) {
      return "";
    }
    StringBuilder block = new StringBuilder("{");
    boolean first = true;
    for (Map.Entry<String, String> entry : sorted.entrySet()) {
      if (!first) {
        block.append(",");
      }
      block.append(entry.getKey()).append("=\"")
          .append(escapeLabelValue(entry.getValue())).append("\"");
      first = false;
    }
    return block.append("}").toString();
  }

  /** Escape a label value: backslash, double quote and newline. */
  private static String escapeLabelValue(String value) {
    if (value == null) {
      return "";
    }
    return value.replace("\\", "\\\\").replace("\"", "\\\"").replace("\n", "\\n");
  }

  /** Escape HELP text: backslash and newline. */
  private static String escapeHelp(String help) {
    if (help == null) {
      return "";
    }
    return help.replace("\\", "\\\\").replace("\n", "\\n");
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

  /**
   * Plugin-reported health from {@link PluginHealthRegistry}.
   *
   * <p>Every sample is 1 and the current status is a label, so an alert reads
   * {@code weavergirl_plugin_health{status!="healthy"} == 1}. Plugins that never report are simply
   * absent; a fake "unknown" series would be worse than no series.
   */
  private void appendPluginHealthMetrics(StringBuilder sb) {
    Map<String, PluginHealth> health;
    try {
      health = PluginHealthRegistry.getAllHealth();
    } catch (RuntimeException e) {
      return;
    }
    if (health == null || health.isEmpty()) {
      return;
    }

    List<String> names = new ArrayList<String>(health.keySet());
    Collections.sort(names);

    sb.append("# HELP weavergirl_plugin_health Plugin health as last reported, 1 per status\n");
    sb.append("# TYPE weavergirl_plugin_health gauge\n");
    for (String name : names) {
      PluginHealth plugin = health.get(name);
      if (plugin == null) {
        continue;
      }
      String status =
          plugin.getStatus() == null
              ? "unknown"
              : plugin.getStatus().name().toLowerCase(java.util.Locale.ROOT);
      sb.append("weavergirl_plugin_health{plugin=\"")
          .append(escape(name))
          .append("\",status=\"")
          .append(status)
          .append("\"} 1\n");
    }

    sb.append(
        "# HELP weavergirl_plugin_health_timestamp_seconds Unix time of the last health report\n");
    sb.append("# TYPE weavergirl_plugin_health_timestamp_seconds gauge\n");
    for (String name : names) {
      PluginHealth plugin = health.get(name);
      if (plugin == null) {
        continue;
      }
      sb.append("weavergirl_plugin_health_timestamp_seconds{plugin=\"")
          .append(escape(name))
          .append("\"} ")
          .append(formatNumber(plugin.getCheckTimestamp() / 1000.0))
          .append("\n");
    }
    sb.append("\n");
  }

  /**
   * Delivery counters for plugin-contributed {@link
   * com.github.cc11001100.weavergirl.api.observability.SpanSink}s. A sink's failures are invisible
   * from the application side (they are isolated and rate-limit logged), so this is how a failing
   * user backend shows up.
   */
  private void appendSpanSinkMetrics(StringBuilder sb) {
    Map<String, SpanSinkStats> stats;
    try {
      stats = SpanSinks.getSinkStats();
    } catch (RuntimeException e) {
      return;
    }
    if (stats == null || stats.isEmpty()) {
      return;
    }

    sb.append("# HELP weavergirl_span_sink_deliveries_total Spans handed to a SpanSink\n");
    sb.append("# TYPE weavergirl_span_sink_deliveries_total counter\n");
    for (Map.Entry<String, SpanSinkStats> entry : stats.entrySet()) {
      sb.append("weavergirl_span_sink_deliveries_total{sink=\"")
          .append(escape(entry.getKey()))
          .append("\"} ")
          .append(entry.getValue().getDeliveries())
          .append("\n");
    }

    sb.append("# HELP weavergirl_span_sink_errors_total SpanSink callbacks that threw\n");
    sb.append("# TYPE weavergirl_span_sink_errors_total counter\n");
    for (Map.Entry<String, SpanSinkStats> entry : stats.entrySet()) {
      sb.append("weavergirl_span_sink_errors_total{sink=\"")
          .append(escape(entry.getKey()))
          .append("\"} ")
          .append(entry.getValue().getFailures())
          .append("\n");
    }
    sb.append("\n");
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

  private static String escape(String s) {
    if (s == null) return "";
    return s.replace("\\", "\\\\").replace("\"", "\\\"").replace("\n", "\\n");
  }
}
