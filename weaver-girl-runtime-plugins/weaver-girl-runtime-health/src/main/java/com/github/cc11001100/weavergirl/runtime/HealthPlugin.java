package com.github.cc11001100.weavergirl.runtime;

import com.github.cc11001100.weavergirl.api.observability.HealthCheckResult;
import com.github.cc11001100.weavergirl.api.observability.HealthChecks;
import com.github.cc11001100.weavergirl.api.observability.HealthReport;
import com.github.cc11001100.weavergirl.api.plugin.PluginContext;
import com.github.cc11001100.weavergirl.api.plugin.WeaverPlugin;
import com.github.cc11001100.weavergirl.api.registry.InterceptorRegistry;
import com.github.cc11001100.weavergirl.core.InterceptorHolder;
import com.github.cc11001100.weavergirl.core.status.AgentStatus;
import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Serves the liveness and readiness probes on {@code healthPort}. Absent that config the plugin does
 * nothing, so shipping the jar costs nothing until the port is set.
 *
 * <p>{@code /health} answers while the agent is up and includes component checks. {@code /ready}
 * answers 200 once an interceptor is registered and 503 before that, which is the signal that
 * instrumentation has actually taken effect. {@code /health/checks} answers 200 while the checks are
 * UP or DEGRADED and 503 when any component is DOWN, for deployments that want a dependency-aware
 * probe. Liveness never depends on a plugin check, so a broken backend cannot restart the process.
 */
public class HealthPlugin implements WeaverPlugin {

  private static final Logger log = LoggerFactory.getLogger(HealthPlugin.class);

  static final String NAME = "health";

  private HttpServer server;

  @Override
  public String name() {
    return NAME;
  }

  @Override
  public boolean isEnabled(PluginContext context) {
    return context.getConfig("healthPort") != null;
  }

  @Override
  public void init(PluginContext context) {
    // init() runs before isEnabled(). With no healthPort there is no endpoint to serve.
    if (!isEnabled(context)) {
      return;
    }
    int port = context.getConfigInt("healthPort", -1);
    if (port < 0) {
      log.warn("Invalid healthPort value: {}, expected integer", context.getConfig("healthPort"));
      return;
    }
    try {
      server = start(port);
      log.info("Health check endpoint enabled on port {}", port);
    } catch (IOException e) {
      log.warn("Failed to start health check server: {}", e.getMessage());
    }
  }

  @Override
  public void registerInterceptors(InterceptorRegistry registry) {
    // probes only
  }

  @Override
  public void destroy() {
    if (server != null) {
      server.stop(0);
      server = null;
    }
  }

  /** Bind the probes to {@code port}. {@code /ready} counts the interceptors registered so far. */
  private static HttpServer start(int port) throws IOException {
    HttpServer server = HttpServer.create(new InetSocketAddress(port), 0);
    server.createContext(
        "/health",
        exchange -> {
          long uptime = AgentStatus.getInstance().getUptimeSeconds();
          HealthReport report = HealthChecks.checkAll();
          respond(
              exchange,
              200,
              "{\"status\":\"UP\",\"agent\":\"weaver-girl\",\"uptimeSeconds\":"
                  + uptime
                  + ",\"checks\":"
                  + reportJson(report)
                  + "}");
        });
    server.createContext(
        "/health/checks",
        exchange -> {
          HealthReport report = HealthChecks.checkAll();
          int status = report.getOverallStatus() == HealthCheckResult.Status.DOWN ? 503 : 200;
          respond(exchange, status, reportJson(report));
        });
    server.createContext(
        "/ready",
        exchange -> {
          int registered = registeredInterceptors();
          if (registered > 0) {
            respond(exchange, 200, "{\"status\":\"READY\",\"interceptorCount\":" + registered + "}");
          } else {
            respond(exchange, 503, "{\"status\":\"NOT_READY\",\"interceptorCount\":0}");
          }
        });
    server.setExecutor(null);
    server.start();
    return server;
  }

  /**
   * The live registry count, falling back to the status snapshot. The snapshot is only refreshed on
   * some paths, so it lags a plugin that registered after startup.
   */
  private static int registeredInterceptors() {
    com.github.cc11001100.weavergirl.api.registry.InterceptorRegistry registry =
        InterceptorHolder.getRegistry();
    if (registry != null) {
      return registry.getAllDefinitions().size();
    }
    return AgentStatus.getInstance().getRegisteredInterceptorCount();
  }

  /** Render a report as JSON: overall status, timing, and one object per component. */
  private static String reportJson(HealthReport report) {
    StringBuilder json = new StringBuilder();
    json.append("{\"status\":\"")
        .append(report.getOverallStatus())
        .append("\",\"durationMs\":")
        .append(report.getDurationMs())
        .append(",\"components\":{");
    boolean first = true;
    for (Map.Entry<String, HealthCheckResult> entry : report.getComponents().entrySet()) {
      if (!first) {
        json.append(",");
      }
      json.append("\"").append(escapeJson(entry.getKey())).append("\":{\"status\":\"")
          .append(entry.getValue().getStatus()).append("\",\"details\":{");
      boolean firstDetail = true;
      for (Map.Entry<String, String> detail : entry.getValue().getDetails().entrySet()) {
        if (!firstDetail) {
          json.append(",");
        }
        json.append("\"")
            .append(escapeJson(detail.getKey()))
            .append("\":\"")
            .append(escapeJson(detail.getValue()))
            .append("\"");
        firstDetail = false;
      }
      json.append("}}");
      first = false;
    }
    return json.append("}}").toString();
  }

  /** Escape the characters that would break the hand-built JSON. */
  private static String escapeJson(String value) {
    if (value == null) {
      return "";
    }
    return value
        .replace("\\", "\\\\")
        .replace("\"", "\\\"")
        .replace("\n", "\\n")
        .replace("\r", "\\r")
        .replace("\t", "\\t");
  }

  private static void respond(
      com.sun.net.httpserver.HttpExchange exchange, int status, String body) throws IOException {
    byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
    exchange.getResponseHeaders().set("Content-Type", "application/json");
    exchange.sendResponseHeaders(status, bytes.length);
    OutputStream out = exchange.getResponseBody();
    out.write(bytes);
    out.close();
  }
}
