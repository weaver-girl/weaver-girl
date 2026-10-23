package com.github.cc11001100.weavergirl.api.tracing;

import java.io.UnsupportedEncodingException;
import java.net.URLDecoder;
import java.net.URLEncoder;
import java.util.*;
import java.util.concurrent.ThreadLocalRandom;

/**
 * Distributed tracing context holder and propagation utilities.
 *
 * <p>Manages the current span via ThreadLocal and provides utilities for:
 *
 * <ul>
 *   <li>Cross-thread span propagation
 *   <li>Cross-process HTTP header injection/extraction
 *   <li>Baggage propagation
 *   <li>Span attributes read by exporters
 * </ul>
 *
 * <h3>Wire format</h3>
 *
 * <p>{@link #inject} writes the W3C Trace Context {@code traceparent} header (and {@code baggage})
 * when the span carries standards-shaped IDs, which every span created by {@link #startSpan} does.
 * It also writes the legacy {@code X-Trace-Id} family so a peer that predates W3C support keeps
 * working. {@link #extract} prefers {@code traceparent} and falls back to the legacy headers. IDs
 * built by hand are not forced into the W3C format: an invalid {@code traceparent} would make the
 * whole trace unusable at the collector.
 *
 * @since 1.1.0
 */
public final class Tracer {

  private static final ThreadLocal<SpanContext> CURRENT_SPAN = new ThreadLocal<>();
  private static final CompositeSpanCompletionListener completionListeners =
      new CompositeSpanCompletionListener();
  private static final String TRACE_ID_HEADER = "X-Trace-Id";
  private static final String SPAN_ID_HEADER = "X-Span-Id";
  private static final String PARENT_SPAN_HEADER = "X-Parent-Span-Id";
  private static final String SAMPLED_HEADER = "X-Sampled";
  private static final String BAGGAGE_PREFIX = "X-Baggage-";

  /** W3C Trace Context header carrying version, trace ID, span ID and flags. */
  public static final String TRACEPARENT_HEADER = "traceparent";

  /** W3C Baggage header. */
  public static final String BAGGAGE_HEADER = "baggage";

  /**
   * Span attribute that tells exporters the OTLP span kind.
   *
   * <p>Values are {@code server}, {@code client}, {@code producer} or {@code consumer}; anything
   * else (and the absence of the attribute) maps to {@code INTERNAL}. Exporters translate the value
   * into the OTLP {@code kind} field and do not emit it as an attribute.
   */
  public static final String SPAN_KIND_ATTRIBUTE = "span.kind";

  private Tracer() {}

  /**
   * Set a single completion listener, replacing any previously set listeners. For adding multiple
   * listeners, use {@link #addCompletionListener} instead.
   */
  public static void setCompletionListener(SpanCompletionListener listener) {
    completionListeners.getListeners().forEach(completionListeners::removeListener);
    if (listener != null) {
      completionListeners.addListener(listener);
    }
  }

  /** Add a completion listener. Multiple listeners can be registered. */
  public static void addCompletionListener(SpanCompletionListener listener) {
    completionListeners.addListener(listener);
  }

  /** Remove a previously added completion listener. */
  public static void removeCompletionListener(SpanCompletionListener listener) {
    completionListeners.removeListener(listener);
  }

  // ===== Current span management =====

  /** Set the current span for this thread. */
  public static void setCurrentSpan(SpanContext span) {
    CURRENT_SPAN.set(span);
  }

  /** Get the current span for this thread. */
  public static SpanContext getCurrentSpan() {
    return CURRENT_SPAN.get();
  }

  /** Check if a span is active. */
  public static boolean hasCurrentSpan() {
    return CURRENT_SPAN.get() != null;
  }

  /** Clear the current span. Must be called in finally blocks. */
  public static void clearCurrentSpan() {
    CURRENT_SPAN.remove();
  }

  // ===== Span creation =====

  /** Start a new root span. */
  public static SpanContext startSpan() {
    return startSpan(null);
  }

  /**
   * Start a new root span with an operation name.
   *
   * <p>The name is already known to the caller at start time (for example {@code "GET /users/{id}"})
   * and is used when {@link #endSpan} is called without one.
   *
   * @param operationName logical operation name, or null
   * @return the new span, also installed as the current span
   */
  public static SpanContext startSpan(String operationName) {
    SpanContext span =
        SpanContext.builder()
            .traceId(generateTraceId())
            .spanId(generateSpanId())
            .operationName(operationName)
            .build();
    CURRENT_SPAN.set(span);
    return span;
  }

  /** Start a child span from the current span. */
  public static SpanContext startChildSpan() {
    return startChildSpan(null);
  }

  /**
   * Start a child of the current span with an operation name.
   *
   * <p>Falls back to a root span when no span is current.
   *
   * @param operationName logical operation name, or null
   * @return the new span, also installed as the current span
   */
  public static SpanContext startChildSpan(String operationName) {
    SpanContext parent = CURRENT_SPAN.get();
    if (parent == null) {
      return startSpan(operationName);
    }
    SpanContext child =
        SpanContext.builder()
            .traceId(parent.getTraceId())
            .spanId(generateSpanId())
            .parentSpanId(parent.getSpanId())
            .sampled(parent.isSampled())
            .baggage(parent.getBaggage())
            .operationName(operationName)
            .build();
    CURRENT_SPAN.set(child);
    return child;
  }

  /**
   * End the current span, run the {@link SpanProcessor} chain, notify listeners, and clear it.
   *
   * <p>Processors run before any listener, so a dropped span reaches neither the built-in exporter
   * nor plugin-registered {@code SpanSink}s.
   *
   * @param operationName the operation name for this span
   * @param status the span status (e.g., "OK", "ERROR")
   * @return the completed span as published (after processors), or null if no span was active or a
   *     processor dropped it
   */
  public static SpanContext endSpan(String operationName, String status) {
    SpanContext span = CURRENT_SPAN.get();
    if (span == null) return null;

    long durationMs = System.currentTimeMillis() - span.getStartTimeMs();

    SpanContext.Builder builder =
        SpanContext.builder()
            .traceId(span.getTraceId())
            .spanId(span.getSpanId())
            .parentSpanId(span.getParentSpanId())
            .sampled(span.isSampled())
            .baggage(span.getBaggage())
            .attributes(span.getAttributes())
            .startTimeMs(span.getStartTimeMs());
    if (operationName != null && !operationName.isEmpty()) {
      builder.operationName(operationName);
    } else if (span.getOperationName() != null && !span.getOperationName().isEmpty()) {
      builder.operationName(span.getOperationName());
    } else {
      builder.operationName("unknown");
    }
    if (status != null && !status.isEmpty()) {
      builder.errorStatus(status);
    } else if (span.getErrorStatus() != null && !span.getErrorStatus().isEmpty()) {
      builder.errorStatus(span.getErrorStatus());
    } else if ("true".equals(span.getAttribute("error"))) {
      // A plugin reported an exception through recordException() and did not pass a status itself.
      builder.errorStatus("ERROR");
    }

    SpanContext finalSpan = builder.build();
    CURRENT_SPAN.remove();

    // Governance runs first: one processor governs exporters, sinks and every other listener.
    SpanContext published = SpanProcessors.apply(finalSpan);
    if (published == null) {
      return null;
    }

    if (completionListeners.size() > 0) {
      completionListeners.onSpanComplete(published, durationMs);
    }

    return published;
  }

  // ===== Cross-thread propagation =====

  /** Capture current tracing state for cross-thread propagation. */
  public static TracingSnapshot capture() {
    return new TracingSnapshot(CURRENT_SPAN.get());
  }

  /** Restore tracing state in a new thread. */
  public static void restore(TracingSnapshot snapshot) {
    if (snapshot != null && snapshot.getSpanContext() != null) {
      CURRENT_SPAN.set(snapshot.getSpanContext());
    }
  }

  // ===== Cross-process HTTP header propagation =====

  /**
   * Inject span context into HTTP headers.
   *
   * @param span the span to inject
   * @param headers map to inject headers into
   */
  public static void inject(SpanContext span, Map<String, String> headers) {
    if (span == null || headers == null) return;

    // W3C Trace Context, understood by every OpenTelemetry SDK and collector.
    if (isValidTraceId(span.getTraceId()) && isValidSpanId(span.getSpanId())) {
      headers.put(
          TRACEPARENT_HEADER,
          "00-"
              + span.getTraceId()
              + "-"
              + span.getSpanId()
              + "-"
              + (span.isSampled() ? "01" : "00"));
    }

    // Legacy headers, so a peer that only knows the previous format keeps working.
    headers.put(TRACE_ID_HEADER, span.getTraceId());
    headers.put(SPAN_ID_HEADER, span.getSpanId());
    if (span.getParentSpanId() != null) {
      headers.put(PARENT_SPAN_HEADER, span.getParentSpanId());
    }
    headers.put(SAMPLED_HEADER, String.valueOf(span.isSampled()));

    if (!span.getBaggage().isEmpty()) {
      headers.put(BAGGAGE_HEADER, encodeBaggage(span.getBaggage()));
    }
    for (Map.Entry<String, String> entry : span.getBaggage().entrySet()) {
      headers.put(BAGGAGE_PREFIX + entry.getKey(), entry.getValue());
    }
  }

  /**
   * Extract span context from HTTP headers.
   *
   * @param headers the HTTP headers
   * @return extracted span context, or null if no trace headers present
   */
  public static SpanContext extract(Map<String, String> headers) {
    if (headers == null) return null;

    SpanContext.Builder builder = null;
    String traceparent = getHeader(headers, TRACEPARENT_HEADER);
    if (traceparent != null) {
      builder = fromTraceparent(traceparent);
    }

    if (builder == null) {
      String traceId = getHeader(headers, TRACE_ID_HEADER);
      String spanId = getHeader(headers, SPAN_ID_HEADER);
      if (traceId == null || spanId == null) return null;
      String sampled = getHeader(headers, SAMPLED_HEADER);
      builder =
          SpanContext.builder()
              .traceId(traceId)
              .spanId(spanId)
              .parentSpanId(getHeader(headers, PARENT_SPAN_HEADER))
              .sampled(sampled == null || Boolean.parseBoolean(sampled));
    }

    // Baggage: the W3C header first, then the per-key legacy headers (which win on a conflict).
    String baggage = getHeader(headers, BAGGAGE_HEADER);
    if (baggage != null) {
      decodeBaggage(baggage, builder);
    }
    for (Map.Entry<String, String> entry : headers.entrySet()) {
      String key = entry.getKey();
      if (key != null
          && key.length() > BAGGAGE_PREFIX.length()
          && key.regionMatches(true, 0, BAGGAGE_PREFIX, 0, BAGGAGE_PREFIX.length())) {
        builder.baggage(key.substring(BAGGAGE_PREFIX.length()), entry.getValue());
      }
    }

    return builder.build();
  }

  /**
   * Parse a {@code traceparent} value into a builder, or null when it is not usable.
   *
   * <p>Version {@code ff} is invalid. A version other than {@code 00} may carry extra fields, so
   * only the first four are read; that is the forward-compatibility rule in the specification.
   */
  private static SpanContext.Builder fromTraceparent(String traceparent) {
    String[] parts = traceparent.trim().split("-");
    if (parts.length < 4) return null;
    String version = parts[0];
    if (!isHex(version, 2) || "ff".equals(version)) return null;
    String traceId = parts[1];
    String spanId = parts[2];
    String flags = parts[3];
    if (!isValidTraceId(traceId) || !isValidSpanId(spanId) || !isHex(flags, 2)) return null;
    boolean sampled = (Integer.parseInt(flags, 16) & 0x01) != 0;
    return SpanContext.builder().traceId(traceId).spanId(spanId).sampled(sampled);
  }

  private static String getHeader(Map<String, String> headers, String name) {
    String direct = headers.get(name);
    if (direct != null) return direct;
    for (Map.Entry<String, String> entry : headers.entrySet()) {
      if (entry.getKey() != null && entry.getKey().equalsIgnoreCase(name)) {
        return entry.getValue();
      }
    }
    return null;
  }

  private static String encodeBaggage(Map<String, String> baggage) {
    StringBuilder sb = new StringBuilder();
    for (Map.Entry<String, String> entry : baggage.entrySet()) {
      if (sb.length() > 0) sb.append(',');
      sb.append(percentEncode(entry.getKey())).append('=').append(percentEncode(entry.getValue()));
    }
    return sb.toString();
  }

  private static void decodeBaggage(String header, SpanContext.Builder builder) {
    for (String member : header.split(",")) {
      int eq = member.indexOf('=');
      if (eq <= 0) continue;
      String key = percentDecode(member.substring(0, eq).trim());
      String value = percentDecode(member.substring(eq + 1).trim());
      if (!key.isEmpty()) {
        builder.baggage(key, value);
      }
    }
  }

  private static String percentEncode(String value) {
    try {
      // URLEncoder is form encoding: a space becomes '+', which baggage parsers would keep.
      return URLEncoder.encode(value, "UTF-8").replace("+", "%20");
    } catch (UnsupportedEncodingException e) {
      return value;
    }
  }

  private static String percentDecode(String value) {
    try {
      return URLDecoder.decode(value, "UTF-8");
    } catch (UnsupportedEncodingException | IllegalArgumentException e) {
      return value;
    }
  }

  // ===== Baggage =====

  /** Set a baggage item on the current span. */
  public static void setBaggage(String key, String value) {
    SpanContext current = CURRENT_SPAN.get();
    if (current == null) return;
    // Copy first: rebuilding the span by hand is how attributes were silently dropped here before.
    CURRENT_SPAN.set(copy(current).baggage(key, value).build());
  }

  /** Get a baggage item from the current span. */
  public static String getBaggage(String key) {
    SpanContext current = CURRENT_SPAN.get();
    return current != null ? current.getBaggageItem(key) : null;
  }

  // ===== Span attributes =====

  /**
   * Set an attribute on the current span.
   *
   * <p>Attributes travel with the completed span to every {@link SpanCompletionListener} and
   * exporter. Use the OpenTelemetry semantic-convention names where they exist. Setting an attribute
   * replaces a previous value with the same key; a null value removes it.
   *
   * @param key attribute name
   * @param value attribute value, or null to remove
   */
  public static void setAttribute(String key, String value) {
    SpanContext current = CURRENT_SPAN.get();
    if (current == null || key == null) return;
    SpanContext.Builder builder = copy(current);
    if (value == null) {
      builder.removeAttribute(key);
    } else {
      builder.attribute(key, value);
    }
    CURRENT_SPAN.set(builder.build());
  }

  /** Get an attribute of the current span, or null. */
  public static String getAttribute(String key) {
    SpanContext current = CURRENT_SPAN.get();
    return current != null ? current.getAttribute(key) : null;
  }

  /**
   * Record an exception on the current span.
   *
   * <p>Adds {@code error=true}, {@code error.type} and {@code error.message}. When the span ends
   * without an explicit status, the error attribute is what makes the exported status ERROR. The
   * throwable itself is not retained.
   *
   * @param failure the exception to record; null is ignored
   */
  public static void recordException(Throwable failure) {
    if (failure == null) return;
    SpanContext current = CURRENT_SPAN.get();
    if (current == null) return;
    Map<String, String> attributes = new LinkedHashMap<>(current.getAttributes());
    attributes.put("error", "true");
    attributes.put("error.type", failure.getClass().getName());
    if (failure.getMessage() != null) {
      attributes.put("error.message", failure.getMessage());
    }
    CURRENT_SPAN.set(copy(current).attributes(attributes).build());
  }

  private static SpanContext.Builder copy(SpanContext span) {
    return SpanContext.builder()
        .traceId(span.getTraceId())
        .spanId(span.getSpanId())
        .parentSpanId(span.getParentSpanId())
        .sampled(span.isSampled())
        .baggage(span.getBaggage())
        .startTimeMs(span.getStartTimeMs())
        .operationName(span.getOperationName())
        .errorStatus(span.getErrorStatus())
        .attributes(span.getAttributes());
  }

  // ===== ID generation =====

  /** 128-bit trace ID as 32 lowercase hex characters, never all zeros. */
  private static String generateTraceId() {
    long high;
    long low;
    do {
      high = ThreadLocalRandom.current().nextLong();
      low = ThreadLocalRandom.current().nextLong();
    } while (high == 0L && low == 0L);
    return String.format("%016x%016x", high, low);
  }

  /** 64-bit span ID as 16 lowercase hex characters, never all zeros. */
  private static String generateSpanId() {
    long value;
    do {
      value = ThreadLocalRandom.current().nextLong();
    } while (value == 0L);
    return String.format("%016x", value);
  }

  private static boolean isValidTraceId(String traceId) {
    return isHex(traceId, 32) && !isAllZeros(traceId);
  }

  private static boolean isValidSpanId(String spanId) {
    return isHex(spanId, 16) && !isAllZeros(spanId);
  }

  private static boolean isHex(String value, int length) {
    if (value == null || value.length() != length) return false;
    for (int i = 0; i < value.length(); i++) {
      char c = value.charAt(i);
      boolean hex =
          (c >= '0' && c <= '9') || (c >= 'a' && c <= 'f') || (c >= 'A' && c <= 'F');
      if (!hex) return false;
    }
    return true;
  }

  private static boolean isAllZeros(String value) {
    for (int i = 0; i < value.length(); i++) {
      if (value.charAt(i) != '0') return false;
    }
    return true;
  }
}
