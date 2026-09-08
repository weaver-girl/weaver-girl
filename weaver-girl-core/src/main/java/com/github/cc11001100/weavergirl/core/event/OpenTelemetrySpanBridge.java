package com.github.cc11001100.weavergirl.core.event;

import com.github.cc11001100.weavergirl.api.event.InterceptorEvent;
import com.github.cc11001100.weavergirl.api.event.InterceptorEventListener;
import java.util.*;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Bridges InterceptorEvents to OpenTelemetry-compatible span format.
 *
 * <p>This listener converts intercepted method events into OTel span-like structures that can be
 * exported to any OTel-compatible backend. It does NOT depend on the OTel SDK — instead it produces
 * a lightweight span representation that can be consumed by exporters (e.g., via JSON to an OTel
 * Collector).
 *
 * <h3>Usage</h3>
 *
 * <pre>
 * // Enable via agent args: oTelExport=true
 * // Or programmatically:
 * OpenTelemetrySpanBridge bridge = new OpenTelemetrySpanBridge();
 * InterceptorEventPublisher.getInstance().addListener(bridge);
 * </pre>
 *
 * <h3>Span mapping</h3>
 *
 * <table>
 *   <tr><th>InterceptorEvent</th><th>OTel Span</th></tr>
 *   <tr><td>type</td><td>span name</td></tr>
 *   <tr><td>plugin</td><td>resource attribute (service.name)</td></tr>
 *   <tr><td>className + methodName</td><td>span attribute</td></tr>
 *   <tr><td>durationMs</td><td>span duration</td></tr>
 *   <tr><td>attributes</td><td>span attributes</td></tr>
 * </table>
 *
 * @since 1.0.0
 */
public class OpenTelemetrySpanBridge implements InterceptorEventListener {

  private final AtomicLong spanIdCounter = new AtomicLong(0);
  private final ConcurrentLinkedQueue<Map<String, Object>> recentSpans =
      new ConcurrentLinkedQueue<>();
  private final int maxRecentSpans;

  /** Trace ID for this agent instance (constant per JVM). */
  private final String traceId;

  public OpenTelemetrySpanBridge() {
    this(1000);
  }

  public OpenTelemetrySpanBridge(int maxRecentSpans) {
    this.maxRecentSpans = maxRecentSpans;
    this.traceId = generateTraceId();
  }

  @Override
  public void onEvent(InterceptorEvent event) {
    Map<String, Object> span = convertToSpan(event);
    recentSpans.add(span);

    // Bounded buffer: remove oldest if over limit
    while (recentSpans.size() > maxRecentSpans) {
      recentSpans.poll();
    }
  }

  /** Convert an InterceptorEvent to an OTel-compatible span map. */
  Map<String, Object> convertToSpan(InterceptorEvent event) {
    Map<String, Object> span = new LinkedHashMap<>();

    // OTel Span required fields
    span.put("traceId", traceId);
    span.put("spanId", generateSpanId());
    span.put("name", event.getType() != null ? event.getType() : "unknown");
    span.put("kind", "INTERNAL");
    span.put("startTimeUnixNano", toUnixNano(event.getTimestamp() - event.getDurationMs()));
    span.put("endTimeUnixNano", toUnixNano(event.getTimestamp()));

    // Status
    Map<String, Object> status = new LinkedHashMap<>();
    boolean isError = event.getType() != null && event.getType().endsWith("-error");
    status.put("code", isError ? "ERROR" : "OK");
    span.put("status", status);

    // Attributes
    Map<String, Object> attributes = new LinkedHashMap<>();
    attributes.put("weaver.plugin", event.getPlugin() != null ? event.getPlugin() : "unknown");
    attributes.put("weaver.class", event.getClassName() != null ? event.getClassName() : "unknown");
    attributes.put(
        "weaver.method", event.getMethodName() != null ? event.getMethodName() : "unknown");
    attributes.put("weaver.duration_ms", event.getDurationMs());

    // Copy event attributes
    if (event.getAttributes() != null) {
      for (Map.Entry<String, String> entry : event.getAttributes().entrySet()) {
        attributes.put("weaver.attr." + entry.getKey(), entry.getValue());
      }
    }
    span.put("attributes", attributes);

    return span;
  }

  /** Get all recent spans as OTel-compatible JSON maps. */
  public List<Map<String, Object>> getRecentSpans() {
    return new ArrayList<>(recentSpans);
  }

  /**
   * Render recent spans as OTel Export TraceServiceRequest JSON. Compatible with OTel Collector
   * HTTP receiver.
   */
  public String renderOtelJson() {
    StringBuilder sb = new StringBuilder();
    sb.append("{\"resourceSpans\":[{\"resource\":{\"attributes\":[");
    sb.append("{\"key\":\"service.name\",\"value\":{\"stringValue\":\"weaver-girl\"}}");
    sb.append("]},\"scopeSpans\":[{\"scope\":{\"name\":\"weaver-girl-agent\"},\"spans\":[");

    List<Map<String, Object>> spans = new ArrayList<>(recentSpans);
    for (int i = 0; i < spans.size(); i++) {
      if (i > 0) sb.append(",");
      appendSpanJson(sb, spans.get(i));
    }

    sb.append("]}]}]}");
    return sb.toString();
  }

  /** Clear buffered spans. */
  public void clear() {
    recentSpans.clear();
  }

  /** Get number of buffered spans. */
  public int size() {
    return recentSpans.size();
  }

  private void appendSpanJson(StringBuilder sb, Map<String, Object> span) {
    sb.append("{");
    sb.append("\"traceId\":\"").append(span.get("traceId")).append("\",");
    sb.append("\"spanId\":\"").append(span.get("spanId")).append("\",");
    sb.append("\"name\":\"").append(escapeJson(String.valueOf(span.get("name")))).append("\",");
    sb.append("\"kind\":").append(span.get("kind")).append(",");

    @SuppressWarnings("unchecked")
    Map<String, Object> status = (Map<String, Object>) span.get("status");
    sb.append("\"status\":{\"code\":\"").append(status.get("code")).append("\"},");

    @SuppressWarnings("unchecked")
    Map<String, Object> attrs = (Map<String, Object>) span.get("attributes");
    sb.append("\"attributes\":[");
    boolean first = true;
    for (Map.Entry<String, Object> e : attrs.entrySet()) {
      if (!first) sb.append(",");
      first = false;
      sb.append("{\"key\":\"").append(escapeJson(e.getKey())).append("\",");
      sb.append("\"value\":{\"stringValue\":\"")
          .append(escapeJson(String.valueOf(e.getValue())))
          .append("\"}}");
    }
    sb.append("]");
    sb.append("}");
  }

  private String generateTraceId() {
    return String.format("%032x", System.nanoTime() ^ (long) (Math.random() * Long.MAX_VALUE));
  }

  private String generateSpanId() {
    return String.format("%016x", spanIdCounter.incrementAndGet());
  }

  private long toUnixNano(long epochMillis) {
    return epochMillis * 1_000_000;
  }

  private String escapeJson(String s) {
    if (s == null) return "";
    return s.replace("\\", "\\\\").replace("\"", "\\\"").replace("\n", "\\n");
  }
}
