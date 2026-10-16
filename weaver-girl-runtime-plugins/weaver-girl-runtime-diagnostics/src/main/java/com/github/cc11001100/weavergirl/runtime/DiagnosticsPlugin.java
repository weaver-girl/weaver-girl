package com.github.cc11001100.weavergirl.runtime;

import com.github.cc11001100.weavergirl.api.plugin.PluginContext;
import com.github.cc11001100.weavergirl.api.plugin.WeaverPlugin;
import com.github.cc11001100.weavergirl.api.registry.InterceptorRegistry;
import com.github.cc11001100.weavergirl.core.InterceptorHolder;
import com.github.cc11001100.weavergirl.core.circuit.InterceptorCircuitBreaker;
import com.github.cc11001100.weavergirl.core.status.AgentStatus;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Serves the operator-facing diagnostics on {@code diagnosticsPort}: {@code /stats} with the live counters,
 * the per-hook latency and the circuit breakers, {@code /metrics} in Prometheus text, and {@code POST
 * /agent/interception} to flip the global kill-switch. Absent that config the plugin does nothing.
 *
 * <p>Everything it reports comes from {@link AgentStatus} and {@link InterceptorHolder}. The class-loader
 * names are resolved through this plugin's own loader, whose parent is the agent loader, so a class the agent
 * isolated still shows the loader that isolated it.
 */
public class DiagnosticsPlugin implements WeaverPlugin {

  private static final Logger log = LoggerFactory.getLogger(DiagnosticsPlugin.class);

  static final String NAME = "diagnostics";

  private HttpServer server;

  @Override
  public String name() {
    return NAME;
  }

  @Override
  public boolean isEnabled(PluginContext context) {
    return context.getConfig("diagnosticsPort") != null;
  }

  @Override
  public void init(PluginContext context) {
    // init() runs before isEnabled(). With no diagnosticsPort there is no endpoint to serve.
    if (!isEnabled(context)) {
      return;
    }
    int port = context.getConfigInt("diagnosticsPort", -1);
    if (port < 0) {
      log.warn(
          "Invalid diagnosticsPort value: {}, expected integer", context.getConfig("diagnosticsPort"));
      return;
    }
    try {
      server = start(port);
      log.info("Diagnostics endpoint enabled on port {}", port);
    } catch (IOException e) {
      log.warn("Failed to start diagnostics server: {}", e.getMessage());
    }
  }

  @Override
  public void registerInterceptors(InterceptorRegistry registry) {
    // endpoints only
  }

  @Override
  public void destroy() {
    if (server != null) {
      server.stop(0);
      server = null;
    }
  }

  private static HttpServer start(int port) throws IOException {
    HttpServer server = HttpServer.create(new InetSocketAddress(port), 0);
    server.createContext("/agent/interception", DiagnosticsPlugin::interception);
    server.createContext("/stats", DiagnosticsPlugin::stats);
    server.createContext("/metrics", DiagnosticsPlugin::metrics);
    server.setExecutor(null);
    server.start();
    return server;
  }

  /** {@code POST /agent/interception {"enabled":true|false}} flips the global kill-switch. */
  private static void interception(HttpExchange exchange) throws IOException {
    try {
      if (!"POST".equalsIgnoreCase(exchange.getRequestMethod())) {
        exchange.sendResponseHeaders(405, -1);
        return;
      }
      ByteArrayOutputStream bos = new ByteArrayOutputStream();
      byte[] buf = new byte[256];
      int n;
      InputStream in = exchange.getRequestBody();
      while ((n = in.read(buf)) != -1) {
        bos.write(buf, 0, n);
      }
      String body = new String(bos.toByteArray(), StandardCharsets.UTF_8);
      boolean desired = body.contains("\"enabled\":true") || body.contains("\"enabled\": true");
      InterceptorHolder.setInterceptionEnabled(desired, "rest:/agent/interception");
      respond(exchange, 200, "application/json", "{\"enabled\":" + desired + "}");
    } catch (RuntimeException e) {
      exchange.sendResponseHeaders(500, 0);
      exchange.getResponseBody().close();
    }
  }

  /** Live counters, the top hooks by time, the circuit breakers, and where ByteBuddy was loaded. */
  private static void stats(HttpExchange exchange) throws IOException {
    try {
      AgentStatus status = AgentStatus.getInstance();
      StringBuilder sb = new StringBuilder(512);
      sb.append("{\"status\":\"UP\",\"uptimeSeconds\":")
          .append(status.getUptimeSeconds())
          .append(",\"transformationCount\":")
          .append(status.getTransformationCount())
          .append(",\"transformationErrorCount\":")
          .append(status.getTransformationErrorCount())
          .append(",\"interceptorInvocationCount\":")
          .append(status.getInterceptorInvocationCount())
          .append(",\"interceptorErrorCount\":")
          .append(status.getInterceptorErrorCount())
          .append(",\"registeredInterceptorCount\":")
          .append(registeredInterceptors(status))
          .append(",\"activePluginCount\":")
          .append(status.getActivePluginCount())
          .append(",\"byteBuddyClassLoader\":\"")
          .append(firstLoadedLoaderName("shaded.net.bytebuddy.ByteBuddy", "net.bytebuddy.ByteBuddy"))
          .append("\"")
          .append(",\"agentBodyClassLoader\":\"")
          .append(loaderName("com.github.cc11001100.weavergirl.agent.WeaverGirlAgent"))
          .append("\"")
          .append(",\"topHooks\":")
          .append(topHooks(status.getInterceptorMetrics(), 10))
          .append(",\"circuitBreakers\":")
          .append(breakers(InterceptorHolder.getBreakerSnapshots()))
          .append("}");
      respond(exchange, 200, "application/json", sb.toString());
    } catch (RuntimeException e) {
      exchange.sendResponseHeaders(500, 0);
      exchange.getResponseBody().close();
    }
  }

  /** Prometheus text over the same counters, plus one histogram per hook. */
  private static void metrics(HttpExchange exchange) throws IOException {
    try {
      AgentStatus status = AgentStatus.getInstance();
      StringBuilder sb = new StringBuilder(2048);
      gauge(sb, "weavergirl_uptime_seconds", "Agent uptime in seconds", status.getUptimeSeconds());
      counter(
          sb,
          "weavergirl_transformations_total",
          "Total class transformations",
          status.getTransformationCount());
      counter(
          sb,
          "weavergirl_transformation_errors_total",
          "Transformation errors",
          status.getTransformationErrorCount());
      counter(
          sb,
          "weavergirl_interceptor_invocations_total",
          "Total interceptor invocations",
          status.getInterceptorInvocationCount());
      counter(
          sb,
          "weavergirl_interceptor_errors_total",
          "Interceptor errors",
          status.getInterceptorErrorCount());
      gauge(
          sb,
          "weavergirl_registered_interceptors",
          "Number of registered interceptor definitions",
          registeredInterceptors(status));
      gauge(
          sb,
          "weavergirl_active_plugins",
          "Number of active plugins",
          status.getActivePluginCount());
      for (Map.Entry<String, AgentStatus.InterceptorMetrics> entry :
          status.getInterceptorMetrics().entrySet()) {
        sb.append(entry.getValue().renderInterceptorHistogramPrometheus(escapeMetric(entry.getKey())));
      }
      respond(exchange, 200, "text/plain; version=0.0.4; charset=utf-8", sb.toString());
    } catch (RuntimeException e) {
      exchange.sendResponseHeaders(500, 0);
      exchange.getResponseBody().close();
    }
  }

  private static void counter(StringBuilder sb, String name, String help, long value) {
    sb.append("# HELP ").append(name).append(' ').append(help).append('\n');
    sb.append("# TYPE ").append(name).append(" counter\n");
    sb.append(name).append(' ').append(value).append("\n\n");
  }

  private static void gauge(StringBuilder sb, String name, String help, long value) {
    sb.append("# HELP ").append(name).append(' ').append(help).append('\n');
    sb.append("# TYPE ").append(name).append(" gauge\n");
    sb.append(name).append(' ').append(value).append("\n\n");
  }

  /**
   * The live registry count, falling back to the status snapshot, which is only refreshed on some paths and
   * lags a plugin that registered after startup.
   */
  private static int registeredInterceptors(AgentStatus status) {
    InterceptorRegistry registry = InterceptorHolder.getRegistry();
    if (registry != null) {
      return registry.getAllDefinitions().size();
    }
    return status.getRegisteredInterceptorCount();
  }

  /**
   * Simple name of the loader that defines {@code className}, resolved through this plugin's loader. Its
   * parent is the agent loader, so this sees the loader the agent itself would. {@code "bootstrap"} for a JDK
   * class, {@code "unloaded"} when the class cannot be found.
   */
  private static String loaderName(String className) {
    try {
      Class<?> type = Class.forName(className, false, DiagnosticsPlugin.class.getClassLoader());
      ClassLoader loader = type.getClassLoader();
      if (loader == null) {
        return "bootstrap";
      }
      String simple = loader.getClass().getSimpleName();
      return simple.isEmpty() ? loader.getClass().getName() : simple;
    } catch (Throwable t) {
      return "unloaded";
    }
  }

  /** The loader of the first candidate that resolves, so the diagnostic works whether or not shaded. */
  private static String firstLoadedLoaderName(String... candidates) {
    for (String name : candidates) {
      String loader = loaderName(name);
      if (!"unloaded".equals(loader)) {
        return loader;
      }
    }
    return "unloaded";
  }

  /** The {@code limit} hooks that have cost the most time, with their latency figures in microseconds. */
  private static String topHooks(Map<String, AgentStatus.InterceptorMetrics> metrics, int limit) {
    List<Map.Entry<String, AgentStatus.InterceptorMetrics>> sorted = new ArrayList<>(metrics.entrySet());
    sorted.sort((a, b) -> Long.compare(b.getValue().getTotalNanos(), a.getValue().getTotalNanos()));
    int n = Math.min(limit, sorted.size());
    StringBuilder sb = new StringBuilder();
    sb.append('[');
    for (int i = 0; i < n; i++) {
      Map.Entry<String, AgentStatus.InterceptorMetrics> entry = sorted.get(i);
      AgentStatus.InterceptorMetrics metric = entry.getValue();
      if (i > 0) {
        sb.append(',');
      }
      sb.append("{\"name\":\"")
          .append(jsonEscape(entry.getKey()))
          .append('"')
          .append(",\"invocations\":")
          .append(metric.getInvocations())
          .append(",\"errors\":")
          .append(metric.getErrors())
          .append(",\"avgMicros\":")
          .append(round3(metric.getAverageNanos() / 1_000.0))
          .append(",\"p95Micros\":")
          .append(round3(metric.estimatePercentile(95.0) / 1_000.0))
          .append(",\"maxMicros\":")
          .append(round3(metric.getMaxNanos() / 1_000.0))
          .append(",\"slowCalls\":")
          .append(metric.getSlowCalls())
          .append('}');
    }
    sb.append(']');
    return sb.toString();
  }

  /** Every breaker: whether it is open, the consecutive counts, and how many times it has tripped. */
  private static String breakers(List<InterceptorCircuitBreaker.BreakerSnapshot> snapshots) {
    StringBuilder sb = new StringBuilder();
    sb.append('[');
    for (int i = 0; i < snapshots.size(); i++) {
      InterceptorCircuitBreaker.BreakerSnapshot snapshot = snapshots.get(i);
      if (i > 0) {
        sb.append(',');
      }
      sb.append("{\"name\":\"")
          .append(jsonEscape(snapshot.getName()))
          .append('"')
          .append(",\"open\":")
          .append(snapshot.isOpen())
          .append(",\"consecutiveFailures\":")
          .append(snapshot.getConsecutiveFailures())
          .append(",\"consecutiveSlow\":")
          .append(snapshot.getConsecutiveSlow())
          .append(",\"timesTripped\":")
          .append(snapshot.getTimesTripped())
          .append('}');
    }
    sb.append(']');
    return sb.toString();
  }

  private static String round3(double value) {
    return String.format(Locale.ROOT, "%.3f", value);
  }

  private static String jsonEscape(String value) {
    if (value.indexOf('"') < 0 && value.indexOf('\\') < 0) {
      return value;
    }
    StringBuilder sb = new StringBuilder(value.length() + 8);
    for (int i = 0; i < value.length(); i++) {
      char c = value.charAt(i);
      if (c == '"' || c == '\\') {
        sb.append('\\');
      }
      sb.append(c);
    }
    return sb.toString();
  }

  private static String escapeMetric(String value) {
    if (value == null) {
      return "";
    }
    return value.replace("\\", "\\\\").replace("\"", "\\\"");
  }

  private static void respond(HttpExchange exchange, int status, String contentType, String body)
      throws IOException {
    byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
    exchange.getResponseHeaders().set("Content-Type", contentType);
    exchange.sendResponseHeaders(status, bytes.length);
    OutputStream out = exchange.getResponseBody();
    out.write(bytes);
    out.close();
  }
}
