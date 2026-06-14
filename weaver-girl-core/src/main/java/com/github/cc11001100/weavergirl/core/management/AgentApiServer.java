package com.github.cc11001100.weavergirl.core.management;

import com.github.cc11001100.weavergirl.api.alert.AlertEngine;
import com.github.cc11001100.weavergirl.core.alert.DefaultAlertEngine;
import com.github.cc11001100.weavergirl.api.metrics.MetricRegistry;
import com.github.cc11001100.weavergirl.api.plugin.PluginManager;
import com.github.cc11001100.weavergirl.api.topology.TopologyGraph;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.*;
import java.net.InetSocketAddress;
import java.util.*;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;

/**
 * Lightweight REST API for querying agent status and management.
 *
 * <p>Endpoints:</p>
 * <ul>
 *   <li>{@code GET /status} — agent status, plugins, interceptors</li>
 *   <li>{@code GET /plugins} — plugin list with state</li>
 *   <li>{@code GET /config} — current dynamic config</li>
 *   <li>{@code GET /topology} — service topology graph</li>
 *   <li>{@code GET /alerts} — alert history</li>
 *   <li>{@code GET /metrics} — metric snapshots</li>
 *   <li>{@code GET /diagnostics} — full diagnostics report</li>
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

    /**
     * Start the API server.
     */
    public void start() throws IOException {
        server = HttpServer.create(new InetSocketAddress(port), 0);

        server.createContext("/status", this::handleStatus);
        server.createContext("/plugins", this::handlePlugins);
        server.createContext("/topology", this::handleTopology);
        server.createContext("/alerts", this::handleAlerts);
        server.createContext("/metrics", this::handleMetrics);
        server.createContext("/diagnostics", this::handleDiagnostics);

        server.setExecutor(null); // default executor
        server.start();
        log.info("[AgentApiServer] REST API started on port {}", port);
    }

    /**
     * Stop the API server.
     */
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
        json.append("\"interceptorCount\":").append(monitor.getInterceptorDefinitionCount()).append(",");
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
        List<com.github.cc11001100.weavergirl.api.alert.AlertEvent> alerts = alertEngine != null ? alertEngine.getHistory()
                : AlertEngine.getHistory();
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
        for (java.util.Map.Entry<String, com.github.cc11001100.weavergirl.api.metrics.MetricSnapshot> e : snapshots.entrySet()) {
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
                    json.append("\"").append(esc(le.getKey())).append("\":\"").append(esc(le.getValue())).append("\"");
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
