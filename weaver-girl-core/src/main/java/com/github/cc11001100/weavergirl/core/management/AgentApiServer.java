package com.github.cc11001100.weavergirl.core.management;

import com.github.cc11001100.weavergirl.api.alert.AlertEngine;
import com.github.cc11001100.weavergirl.api.context.ContextPropagatorRegistry;
import com.github.cc11001100.weavergirl.api.context.ContextSnapshot;
import com.github.cc11001100.weavergirl.api.event.InterceptorEvent;
import com.github.cc11001100.weavergirl.api.event.InterceptorEventPublisher;
import com.github.cc11001100.weavergirl.api.metrics.MetricRegistry;
import com.github.cc11001100.weavergirl.api.plugin.PluginManager;
import com.github.cc11001100.weavergirl.api.security.SecurityAuditLog;
import com.github.cc11001100.weavergirl.api.security.SecurityPolicy;
import com.github.cc11001100.weavergirl.api.tracing.Tracer;
import com.github.cc11001100.weavergirl.core.alert.DefaultAlertEngine;
import com.github.cc11001100.weavergirl.core.status.AgentStatus;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import java.io.*;
import java.net.InetSocketAddress;
import java.util.*;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Lightweight REST API for querying agent status and management.
 *
 * <p>Endpoints:
 *
 * <ul>
 *   <li>{@code GET /status} — agent status, plugins, interceptors
 *   <li>{@code GET /plugins} — plugin list with state
 *   <li>{@code GET /config} — current dynamic config
 *   <li>{@code GET /topology} — service topology graph
 *   <li>{@code GET /alerts} — alert history
 *   <li>{@code GET /metrics} — metric snapshots
 *   <li>{@code GET /diagnostics} — full diagnostics report
 *   <li>{@code GET /errors} — transformation and interceptor error counters
 *   <li>{@code GET /traces} — current/live span and thread context snapshot
 *   <li>{@code GET /context/propagators} — registered cross-thread propagators
 *   <li>{@code GET /events} — recent interceptor event history
 *   <li>{@code GET /audit} — security audit log records
 *   <li>{@code GET /lifecycle} — recent lifecycle event history
 *   <li>{@code GET /security/policy} — current security policy summary
 * </ul>
 *
 * @since 1.2.0
 */
public class AgentApiServer {

  private static final Logger log = LoggerFactory.getLogger(AgentApiServer.class);

  private HttpServer server;
  private final int port;
  private final DefaultAlertEngine alertEngine;

  public AgentApiServer(int port) {
    this(port, null);
  }

  public AgentApiServer(int port, DefaultAlertEngine alertEngine) {
    this.port = port;
    this.alertEngine = alertEngine;
  }

  /** Start the API server. */
  public void start() throws IOException {
    server = HttpServer.create(new InetSocketAddress(port), 0);

    server.createContext("/status", this::handleStatus);
    server.createContext("/plugins", this::handlePlugins);
    server.createContext("/topology", this::handleTopology);
    server.createContext("/alerts", this::handleAlerts);
    server.createContext("/metrics", this::handleMetrics);
    server.createContext("/diagnostics", this::handleDiagnostics);
    server.createContext("/errors", this::handleErrors);
    server.createContext("/traces", this::handleTraces);
    server.createContext("/context/propagators", this::handleContextPropagators);
    server.createContext("/events", this::handleEvents);
    server.createContext("/audit", this::handleAudit);
    server.createContext("/lifecycle", this::handleLifecycle);
    server.createContext("/security/policy", this::handleSecurityPolicy);

    server.setExecutor(null); // default executor
    server.start();
    log.info("[AgentApiServer] REST API started on port {}", port);
  }

  /** Stop the API server. */
  public void stop() {
    if (server != null) {
      server.stop(1);
      log.info("[AgentApiServer] REST API stopped");
    }
  }

  public int getPort() {
    return port;
  }

  // ===== Handlers =====

  private void handleStatus(HttpExchange exchange) throws IOException {
    AgentMonitor monitor = AgentMonitor.getInstance();
    AgentDiagnostics diagnostics = AgentDiagnostics.getInstance();

    StringBuilder json = new StringBuilder();
    json.append("{");
    json.append("\"status\":\"UP\",");
    json.append("\"interceptorCount\":")
        .append(monitor.getInterceptorDefinitionCount())
        .append(",");
    json.append("\"pluginCount\":").append(monitor.getPluginCount()).append(",");
    json.append("\"transformedClasses\":").append(monitor.getTransformedClassCount()).append(",");
    json.append("\"totalIntercepts\":").append(monitor.getTotalInterceptCount()).append(",");
    json.append("\"faultCount\":").append(diagnostics.getFaults().size());
    json.append("}");

    sendJson(exchange, json.toString());
  }

  private void handlePlugins(HttpExchange exchange) throws IOException {
    PluginManager pm = PluginManager.getInstance();
    StringBuilder json = new StringBuilder("{\"plugins\":[");
    if (pm != null) {
      List<com.github.cc11001100.weavergirl.api.plugin.PluginInfo> plugins = pm.getAllPluginInfo();
      for (int i = 0; i < plugins.size(); i++) {
        if (i > 0) json.append(",");
        com.github.cc11001100.weavergirl.api.plugin.PluginInfo p = plugins.get(i);
        json.append("{");
        json.append("\"name\":\"").append(esc(p.getName())).append("\",");
        json.append("\"state\":\"").append(p.getState()).append("\",");
        json.append("\"interceptors\":").append(p.getInterceptorCount());
        json.append("}");
      }
    }
    json.append("],\"activeCount\":").append(pm != null ? pm.getActivePluginCount() : 0);
    json.append("}");
    sendJson(exchange, json.toString());
  }

  private void handleTopology(HttpExchange exchange) throws IOException {
    String json = com.github.cc11001100.weavergirl.core.topology.TopologyJsonExporter.export();
    sendJson(exchange, json);
  }

  private void handleAlerts(HttpExchange exchange) throws IOException {
    List<com.github.cc11001100.weavergirl.api.alert.AlertEvent> alerts =
        alertEngine != null ? alertEngine.getHistory() : AlertEngine.getHistory();
    StringBuilder json = new StringBuilder("{\"alerts\":[");
    for (int i = 0; i < alerts.size(); i++) {
      if (i > 0) json.append(",");
      com.github.cc11001100.weavergirl.api.alert.AlertEvent a = alerts.get(i);
      json.append("{");
      json.append("\"rule\":\"").append(esc(a.getRuleName())).append("\",");
      json.append("\"severity\":\"").append(esc(a.getSeverity())).append("\",");
      json.append("\"message\":\"").append(esc(a.getMessage())).append("\",");
      json.append("\"value\":").append(a.getActualValue()).append(",");
      json.append("\"threshold\":").append(a.getThreshold());
      json.append("}");
    }
    json.append("],\"count\":").append(alerts.size());
    json.append("}");
    sendJson(exchange, json.toString());
  }

  private void handleMetrics(HttpExchange exchange) throws IOException {
    java.util.Map<String, com.github.cc11001100.weavergirl.api.metrics.MetricSnapshot> snapshots =
        MetricRegistry.snapshotAll();
    StringBuilder json = new StringBuilder("{\"metrics\":{");
    boolean first = true;
    for (java.util.Map.Entry<String, com.github.cc11001100.weavergirl.api.metrics.MetricSnapshot>
        e : snapshots.entrySet()) {
      if (!first) json.append(",");
      first = false;
      com.github.cc11001100.weavergirl.api.metrics.MetricSnapshot s = e.getValue();
      json.append("\"").append(esc(e.getKey())).append("\":{");
      json.append("\"count\":").append(s.getCount()).append(",");
      json.append("\"sum\":").append(String.format("%.2f", s.getSum())).append(",");
      json.append("\"avg\":").append(String.format("%.2f", s.getAvg())).append(",");
      json.append("\"min\":").append(String.format("%.2f", s.getMin())).append(",");
      json.append("\"max\":").append(String.format("%.2f", s.getMax())).append(",");
      json.append("\"p50\":").append(String.format("%.2f", s.getPercentile(0.50))).append(",");
      json.append("\"p95\":").append(String.format("%.2f", s.getPercentile(0.95))).append(",");
      json.append("\"p99\":").append(String.format("%.2f", s.getPercentile(0.99))).append(",");
      json.append("\"ratePerSecond\":").append(String.format("%.2f", s.getRatePerSecond()));
      if (!s.getLabels().isEmpty()) {
        json.append(",\"labels\":{");
        boolean lf = true;
        for (java.util.Map.Entry<String, String> le : s.getLabels().entrySet()) {
          if (!lf) json.append(",");
          lf = false;
          json.append("\"")
              .append(esc(le.getKey()))
              .append("\":\"")
              .append(esc(le.getValue()))
              .append("\"");
        }
        json.append("}");
      }
      json.append("}");
    }
    json.append("}}");
    sendJson(exchange, json.toString());
  }

  private void handleDiagnostics(HttpExchange exchange) throws IOException {
    String report = AgentDiagnostics.getInstance().generateReport();
    sendText(exchange, report);
  }

  private void handleErrors(HttpExchange exchange) throws IOException {
    AgentStatus status = AgentStatus.getInstance();
    StringBuilder json = new StringBuilder();
    json.append("{");
    json.append("\"transformationErrorCount\":")
        .append(status.getTransformationErrorCount())
        .append(",");
    json.append("\"interceptorErrorCount\":")
        .append(status.getInterceptorErrorCount());
    json.append("}");
    sendJson(exchange, json.toString());
  }

  private void handleTraces(HttpExchange exchange) throws IOException {
    com.github.cc11001100.weavergirl.api.tracing.SpanContext span =
        com.github.cc11001100.weavergirl.api.tracing.Tracer.getCurrentSpan();
    ContextSnapshot snapshot = ContextSnapshot.capture();
    StringBuilder json = new StringBuilder();
    json.append("{");
    if (span != null) {
      json.append("\"span\":{");
      json.append("\"traceId\":\"").append(esc(span.getTraceId())).append("\",");
      json.append("\"spanId\":\"").append(esc(span.getSpanId())).append("\",");
      if (span.getParentSpanId() != null) {
        json.append("\"parentSpanId\":\"").append(esc(span.getParentSpanId())).append("\",");
      }
      json.append("\"operationName\":\"").append(esc(span.getOperationName())).append("\",");
      json.append("\"startTimeMs\":").append(span.getStartTimeMs()).append(",");
      json.append("\"sampled\":").append(span.isSampled()).append(",");
      json.append("\"root\":").append(span.isRoot()).append(",");
      if (span.getErrorStatus() != null) {
        json.append("\"errorStatus\":\"").append(esc(span.getErrorStatus())).append("\",");
      }
      json.append("\"baggageCount\":").append(span.getBaggage().size());
      json.append("},");
    }
    json.append("\"threadContext\":{");
    Map<String, Object> threadContext = snapshot.getThreadContext();
    boolean first = true;
    for (Map.Entry<String, Object> entry : threadContext.entrySet()) {
      if (!first) json.append(",");
      first = false;
      json.append("\"").append(esc(entry.getKey())).append("\":\"");
      json.append(esc(entry.getValue() != null ? entry.getValue().toString() : "null"));
      json.append("\"");
    }
    json.append("},");
    json.append("\"hasSpan\":").append(span != null).append(",");
    json.append("\"snapshotEmpty\":").append(snapshot.isEmpty());
    json.append("}");
    sendJson(exchange, json.toString());
  }

  private void handleContextPropagators(HttpExchange exchange) throws IOException {
    List<com.github.cc11001100.weavergirl.api.context.ContextPropagator> propagators =
        ContextPropagatorRegistry.getAll();
    StringBuilder json = new StringBuilder();
    json.append("{\"propagators\":[");
    for (int i = 0; i < propagators.size(); i++) {
      if (i > 0) json.append(",");
      com.github.cc11001100.weavergirl.api.context.ContextPropagator p = propagators.get(i);
      json.append("{");
      json.append("\"name\":\"").append(esc(p.name())).append("\",");
      json.append("\"priority\":").append(p.priority());
      json.append("}");
    }
    json.append("],\"count\":").append(propagators.size());
    json.append("}");
    sendJson(exchange, json.toString());
  }

  private void handleEvents(HttpExchange exchange) throws IOException {
    List<com.github.cc11001100.weavergirl.api.event.InterceptorEvent> events =
        com.github.cc11001100.weavergirl.api.event.InterceptorEventPublisher.getInstance().getHistory();
    StringBuilder json = new StringBuilder("{\"events\":[");
    for (int i = 0; i < events.size(); i++) {
      if (i > 0) json.append(",");
      com.github.cc11001100.weavergirl.api.event.InterceptorEvent e = events.get(i);
      json.append("{");
      json.append("\"type\":\"").append(esc(e.getType())).append("\",");
      json.append("\"plugin\":\"").append(esc(e.getPlugin())).append("\",");
      json.append("\"className\":\"").append(esc(e.getClassName())).append("\",");
      json.append("\"methodName\":\"").append(esc(e.getMethodName())).append("\",");
      json.append("\"timestamp\":").append(e.getTimestamp()).append(",");
      json.append("\"durationMs\":").append(e.getDurationMs());
      json.append("}");
    }
    json.append("],\"count\":").append(events.size());
    json.append("}");
    sendJson(exchange, json.toString());
  }

  private void handleAudit(HttpExchange exchange) throws IOException {
    List<com.github.cc11001100.weavergirl.api.security.AuditRecord> records =
        com.github.cc11001100.weavergirl.api.security.SecurityAuditLog.getRecords();
    StringBuilder json = new StringBuilder("{\"records\":[");
    for (int i = 0; i < records.size(); i++) {
      if (i > 0) json.append(",");
      com.github.cc11001100.weavergirl.api.security.AuditRecord r = records.get(i);
      json.append("{");
      json.append("\"operation\":\"").append(esc(r.getOperation())).append("\",");
      json.append("\"principal\":\"").append(esc(r.getPrincipal())).append("\",");
      json.append("\"target\":\"").append(esc(r.getTarget())).append("\",");
      json.append("\"result\":\"").append(esc(r.getResult())).append("\",");
      json.append("\"detail\":{");
      java.util.Map<String, String> detail = r.getDetails();
      boolean first = true;
      for (java.util.Map.Entry<String, String> entry : detail.entrySet()) {
        if (!first) json.append(",");
        first = false;
        json.append("\"").append(esc(entry.getKey())).append("\":\"");
        json.append(esc(entry.getValue())).append("\"");
      }
      json.append("}");
      json.append("}");
    }
    json.append("],\"count\":").append(records.size());
    json.append("}");
    sendJson(exchange, json.toString());
  }

  private void handleLifecycle(HttpExchange exchange) throws IOException {
    List<com.github.cc11001100.weavergirl.api.event.InterceptorEvent> events =
        com.github.cc11001100.weavergirl.api.event.InterceptorEventPublisher.getInstance().getHistory();
    List<com.github.cc11001100.weavergirl.api.event.InterceptorEvent> lifecycleEvents = new ArrayList<>();
    int registryCount = 0;
    int pluginCount = 0;
    for (com.github.cc11001100.weavergirl.api.event.InterceptorEvent e : events) {
      String type = e.getType();
      if ("weaver-girl.lifecycle.registry".equals(type)) {
        registryCount++;
        lifecycleEvents.add(e);
      } else if ("weaver-girl.lifecycle.plugin".equals(type)) {
        pluginCount++;
        lifecycleEvents.add(e);
      }
    }
    StringBuilder json = new StringBuilder("{\"events\":[");
    for (int i = 0; i < lifecycleEvents.size(); i++) {
      if (i > 0) json.append(",");
      com.github.cc11001100.weavergirl.api.event.InterceptorEvent e = lifecycleEvents.get(i);
      json.append("{");
      json.append("\"type\":\"").append(esc(e.getType())).append("\",");
      json.append("\"plugin\":\"").append(esc(e.getPlugin())).append("\",");
      json.append("\"className\":\"").append(esc(e.getClassName())).append("\",");
      json.append("\"methodName\":\"").append(esc(e.getMethodName())).append("\",");
      json.append("\"timestamp\":").append(e.getTimestamp()).append(",");
      json.append("\"durationMs\":").append(e.getDurationMs()).append(",");
      java.util.Map<String, String> attributes = e.getAttributes();
      json.append("\"phase\":\"").append(esc(attributes.get("phase"))).append("\",");
      json.append("\"success\":\"").append(esc(attributes.get("success"))).append("\",");
      json.append("\"detail\":\"").append(esc(attributes.get("detail"))).append("\"");
      json.append("}");
    }
    json.append("],\"count\":").append(lifecycleEvents.size());
    json.append(",\"registryCount\":").append(registryCount);
    json.append(",\"pluginCount\":").append(pluginCount);
    json.append("}");
    sendJson(exchange, json.toString());
  }

  private void handleSecurityPolicy(HttpExchange exchange) throws IOException {
    SecurityPolicy policy = SecurityAuditLog.getPolicy();
    StringBuilder json = new StringBuilder();
    json.append("{");
    json.append("\"defaultAllow\":").append(policy.isClassAllowed("*")).append(",");
    json.append("\"auditAllInterceptions\":").append(policy.shouldAuditAll()).append(",");
    json.append("\"allowPatterns\":[");
    List<String> allow = policy.getAllowPatterns();
    for (int i = 0; i < allow.size(); i++) {
      if (i > 0) json.append(",");
      json.append("\"").append(esc(allow.get(i))).append("\"");
    }
    json.append("],");
    json.append("\"denyPatterns\":[");
    List<String> deny = policy.getDenyPatterns();
    for (int i = 0; i < deny.size(); i++) {
      if (i > 0) json.append(",");
      json.append("\"").append(esc(deny.get(i))).append("\"");
    }
    json.append("]");
    json.append("}");
    sendJson(exchange, json.toString());
  }

  // ===== Helpers =====

  private void sendJson(HttpExchange exchange, String json) throws IOException {
    byte[] bytes = json.getBytes("UTF-8");
    exchange.getResponseHeaders().set("Content-Type", "application/json; charset=utf-8");
    exchange.sendResponseHeaders(200, bytes.length);
    try (OutputStream os = exchange.getResponseBody()) {
      os.write(bytes);
    }
  }

  private void sendText(HttpExchange exchange, String text) throws IOException {
    byte[] bytes = text.getBytes("UTF-8");
    exchange.getResponseHeaders().set("Content-Type", "text/plain; charset=utf-8");
    exchange.sendResponseHeaders(200, bytes.length);
    try (OutputStream os = exchange.getResponseBody()) {
      os.write(bytes);
    }
  }

  private String esc(String s) {
    if (s == null) return "";
    return s.replace("\\", "\\\\").replace("\"", "\\\"");
  }
}
