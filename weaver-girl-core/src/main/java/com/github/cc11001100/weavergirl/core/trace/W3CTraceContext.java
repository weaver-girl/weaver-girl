package com.github.cc11001100.weavergirl.core.trace;

import com.github.cc11001100.weavergirl.api.context.ThreadContext;
import java.util.*;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * W3C Trace Context propagation support.
 *
 * <p>Implements the W3C Trace Context specification (https://www.w3.org/TR/trace-context/) for
 * distributed tracing. Parses and stores {@code traceparent} and {@code tracestate} headers, and
 * provides methods to extract/inject them into HTTP-like carrier objects.
 *
 * <h3>traceparent format</h3>
 *
 * <pre>
 * version (2 hex) - traceId (32 hex) - spanId (16 hex) - traceFlags (2 hex)
 * Example: 00-4bf92f3577b34da6a3ce929d0e0e4736-00f067aa0ba902b7-01
 * </pre>
 *
 * <h3>Usage in plugins</h3>
 *
 * <pre>
 * // Extract from incoming request headers
 * Map&lt;String, String&gt; headers = ...; // e.g., HttpServletRequest headers
 * W3CTraceContext trace = W3CTraceContext.extractFromHeaders(headers);
 * trace.propagateToThreadContext();
 *
 * // Inject into outgoing request headers
 * Map&lt;String, String&gt; outgoingHeaders = new HashMap&lt;&gt;();
 * trace.injectIntoHeaders(outgoingHeaders);
 * </pre>
 *
 * @since 1.0.0
 */
public class W3CTraceContext {

  /** W3C traceparent header name. */
  public static final String TRACEPARENT_HEADER = "traceparent";

  /** W3C tracestate header name. */
  public static final String TRACESTATE_HEADER = "tracestate";

  private static final Pattern TRACEPARENT_PATTERN =
      Pattern.compile("^([0-9a-f]{2})-([0-9a-f]{32})-([0-9a-f]{16})-([0-9a-f]{2})$");

  // ThreadContext keys
  private static final String TC_TRACE_ID = "w3c.traceId";
  private static final String TC_SPAN_ID = "w3c.spanId";
  private static final String TC_TRACE_FLAGS = "w3c.traceFlags";
  private static final String TC_TRACESTATE = "w3c.tracestate";

  private final String traceId;
  private final String spanId;
  private final String traceFlags;
  private final Map<String, String> traceState;

  private W3CTraceContext(
      String traceId, String spanId, String traceFlags, Map<String, String> traceState) {
    this.traceId = traceId;
    this.spanId = spanId;
    this.traceFlags = traceFlags;
    this.traceState =
        traceState != null
            ? Collections.unmodifiableMap(new LinkedHashMap<>(traceState))
            : Collections.emptyMap();
  }

  /**
   * Create a new trace context with the given values.
   *
   * @param traceId 32 hex character trace ID
   * @param spanId 16 hex character span ID
   * @param traceFlags 2 hex character trace flags
   * @return a new W3CTraceContext
   */
  public static W3CTraceContext create(String traceId, String spanId, String traceFlags) {
    return new W3CTraceContext(traceId, spanId, traceFlags, null);
  }

  /**
   * Extract trace context from a map of headers (case-insensitive lookup).
   *
   * @param headers the headers map (e.g., HTTP request headers)
   * @return the extracted trace context, or null if not present
   */
  public static W3CTraceContext extractFromHeaders(Map<String, String> headers) {
    if (headers == null) return null;

    // Case-insensitive header lookup
    String traceparent = null;
    String tracestate = null;
    for (Map.Entry<String, String> entry : headers.entrySet()) {
      String key = entry.getKey();
      if (key == null) continue;
      String lowerKey = key.toLowerCase(Locale.ROOT);
      if (TRACEPARENT_HEADER.equals(lowerKey)) {
        traceparent = entry.getValue();
      } else if (TRACESTATE_HEADER.equals(lowerKey)) {
        tracestate = entry.getValue();
      }
    }

    if (traceparent == null) return null;

    Matcher m = TRACEPARENT_PATTERN.matcher(traceparent.trim());
    if (!m.matches()) return null;

    String version = m.group(1);
    if (!"00".equals(version)) return null; // Only version 00 is supported

    String traceId = m.group(2);
    String spanId = m.group(3);
    String traceFlags = m.group(4);

    Map<String, String> traceStateMap = null;
    if (tracestate != null && !tracestate.trim().isEmpty()) {
      traceStateMap = parseTraceState(tracestate);
    }

    return new W3CTraceContext(traceId, spanId, traceFlags, traceStateMap);
  }

  /**
   * Restore trace context from ThreadContext (previously propagated).
   *
   * @return the restored trace context, or null if not present
   */
  public static W3CTraceContext fromThreadContext() {
    String traceId = ThreadContext.get(TC_TRACE_ID);
    String spanId = ThreadContext.get(TC_SPAN_ID);
    if (traceId == null || spanId == null) return null;

    String traceFlags = ThreadContext.get(TC_TRACE_FLAGS);
    if (traceFlags == null) traceFlags = "01";

    @SuppressWarnings("unchecked")
    Map<String, String> traceState = ThreadContext.get(TC_TRACESTATE);

    return new W3CTraceContext(traceId, spanId, traceFlags, traceState);
  }

  /** Propagate this trace context into ThreadContext for cross-thread access. */
  public void propagateToThreadContext() {
    ThreadContext.put(TC_TRACE_ID, traceId);
    ThreadContext.put(TC_SPAN_ID, spanId);
    ThreadContext.put(TC_TRACE_FLAGS, traceFlags);
    if (!traceState.isEmpty()) {
      ThreadContext.put(TC_TRACESTATE, traceState);
    }
  }

  /**
   * Inject this trace context into a headers map.
   *
   * @param headers the headers map to inject into
   */
  public void injectIntoHeaders(Map<String, String> headers) {
    if (headers == null) return;
    headers.put(TRACEPARENT_HEADER, formatTraceparent());
    if (!traceState.isEmpty()) {
      headers.put(TRACESTATE_HEADER, formatTraceState());
    }
  }

  /**
   * Generate a child span ID for downstream calls.
   *
   * @return a new W3CTraceContext with a new span ID but same trace ID
   */
  public W3CTraceContext newChildSpan() {
    String childSpanId = generateSpanId();
    return new W3CTraceContext(traceId, childSpanId, traceFlags, traceState);
  }

  /** Returns the trace ID (32 hex characters). */
  public String getTraceId() {
    return traceId;
  }

  /** Returns the span ID (16 hex characters). */
  public String getSpanId() {
    return spanId;
  }

  /** Returns the trace flags (2 hex characters, e.g., "01" = sampled). */
  public String getTraceFlags() {
    return traceFlags;
  }

  /** Returns true if this span is sampled. */
  public boolean isSampled() {
    return "01".equals(traceFlags);
  }

  /** Returns the trace state map. */
  public Map<String, String> getTraceState() {
    return traceState;
  }

  /** Format as W3C traceparent header value. */
  public String formatTraceparent() {
    return "00-" + traceId + "-" + spanId + "-" + traceFlags;
  }

  private String formatTraceState() {
    if (traceState.isEmpty()) return "";
    StringBuilder sb = new StringBuilder();
    boolean first = true;
    for (Map.Entry<String, String> e : traceState.entrySet()) {
      if (!first) sb.append(",");
      first = false;
      sb.append(e.getKey()).append("=").append(e.getValue());
    }
    return sb.toString();
  }

  private static Map<String, String> parseTraceState(String tracestate) {
    Map<String, String> map = new LinkedHashMap<>();
    String[] entries = tracestate.split(",");
    for (String entry : entries) {
      int eq = entry.indexOf('=');
      if (eq > 0) {
        map.put(entry.substring(0, eq).trim(), entry.substring(eq + 1).trim());
      }
    }
    return map;
  }

  private static String generateSpanId() {
    return String.format("%016x", System.nanoTime());
  }

  @Override
  public String toString() {
    return "W3CTraceContext{" + formatTraceparent() + "}";
  }
}
