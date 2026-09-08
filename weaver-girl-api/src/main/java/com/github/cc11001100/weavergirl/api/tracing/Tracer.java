package com.github.cc11001100.weavergirl.api.tracing;

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
 * </ul>
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
    SpanContext span = SpanContext.builder().traceId(generateId()).spanId(generateId()).build();
    CURRENT_SPAN.set(span);
    return span;
  }

  /** Start a child span from the current span. */
  public static SpanContext startChildSpan() {
    SpanContext parent = CURRENT_SPAN.get();
    if (parent == null) {
      return startSpan();
    }
    SpanContext child = parent.newChild(generateId());
    CURRENT_SPAN.set(child);
    return child;
  }

  /**
   * End the current span, notify listener, and clear it.
   *
   * @param operationName the operation name for this span
   * @param status the span status (e.g., "OK", "ERROR")
   * @return the completed span context, or null if no span was active
   */
  public static SpanContext endSpan(String operationName, String status) {
    SpanContext span = CURRENT_SPAN.get();
    if (span == null) return null;

    long durationMs = System.currentTimeMillis() - span.getStartTimeMs();

    // Update operation name if provided
    SpanContext finalSpan = span;
    if (operationName != null && !operationName.isEmpty()) {
      finalSpan =
          SpanContext.builder()
              .traceId(span.getTraceId())
              .spanId(span.getSpanId())
              .parentSpanId(span.getParentSpanId())
              .sampled(span.isSampled())
              .baggage(span.getBaggage())
              .startTimeMs(span.getStartTimeMs())
              .operationName(operationName)
              .build();
    }

    CURRENT_SPAN.remove();

    if (completionListeners.size() > 0) {
      completionListeners.onSpanComplete(finalSpan, durationMs);
    }

    return finalSpan;
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
    headers.put(TRACE_ID_HEADER, span.getTraceId());
    headers.put(SPAN_ID_HEADER, span.getSpanId());
    if (span.getParentSpanId() != null) {
      headers.put(PARENT_SPAN_HEADER, span.getParentSpanId());
    }
    headers.put(SAMPLED_HEADER, String.valueOf(span.isSampled()));
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

    String traceId = headers.get(TRACE_ID_HEADER);
    String spanId = headers.get(SPAN_ID_HEADER);
    if (traceId == null || spanId == null) return null;

    SpanContext.Builder builder =
        SpanContext.builder()
            .traceId(traceId)
            .spanId(spanId)
            .parentSpanId(headers.get(PARENT_SPAN_HEADER))
            .sampled(Boolean.parseBoolean(headers.getOrDefault(SAMPLED_HEADER, "true")));

    // Extract baggage
    for (Map.Entry<String, String> entry : headers.entrySet()) {
      if (entry.getKey().startsWith(BAGGAGE_PREFIX)) {
        String key = entry.getKey().substring(BAGGAGE_PREFIX.length());
        builder.baggage(key, entry.getValue());
      }
    }

    return builder.build();
  }

  // ===== Baggage =====

  /** Set a baggage item on the current span. */
  public static void setBaggage(String key, String value) {
    SpanContext current = CURRENT_SPAN.get();
    if (current == null) return;
    SpanContext updated =
        SpanContext.builder()
            .traceId(current.getTraceId())
            .spanId(current.getSpanId())
            .parentSpanId(current.getParentSpanId())
            .sampled(current.isSampled())
            .baggage(current.getBaggage())
            .baggage(key, value)
            .build();
    CURRENT_SPAN.set(updated);
  }

  /** Get a baggage item from the current span. */
  public static String getBaggage(String key) {
    SpanContext current = CURRENT_SPAN.get();
    return current != null ? current.getBaggageItem(key) : null;
  }

  // ===== ID generation =====

  private static String generateId() {
    return Long.toHexString(ThreadLocalRandom.current().nextLong())
        + Long.toHexString(ThreadLocalRandom.current().nextLong());
  }
}
