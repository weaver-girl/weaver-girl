package com.github.cc11001100.weavergirl.api.tracing;

import static org.junit.jupiter.api.Assertions.*;

import java.util.*;
import org.junit.jupiter.api.*;

/** Tests for distributed tracing (P53). */
class TracerTest {

  @AfterEach
  void tearDown() {
    Tracer.clearCurrentSpan();
  }

  // ===== SpanContext =====

  @Test
  void spanContext_builder() {
    SpanContext span =
        SpanContext.builder()
            .traceId("trace123")
            .spanId("span456")
            .parentSpanId("parent789")
            .sampled(true)
            .baggage("userId", "user-1")
            .build();

    assertEquals("trace123", span.getTraceId());
    assertEquals("span456", span.getSpanId());
    assertEquals("parent789", span.getParentSpanId());
    assertTrue(span.isSampled());
    assertFalse(span.isRoot());
    assertEquals("user-1", span.getBaggageItem("userId"));
  }

  @Test
  void spanContext_rootSpan() {
    SpanContext span = SpanContext.builder().traceId("t1").spanId("s1").build();
    assertTrue(span.isRoot());
    assertNull(span.getParentSpanId());
  }

  @Test
  void spanContext_childSpan() {
    SpanContext parent = SpanContext.builder().traceId("t1").spanId("s1").build();
    SpanContext child = parent.newChild("s2");

    assertEquals("t1", child.getTraceId());
    assertEquals("s2", child.getSpanId());
    assertEquals("s1", child.getParentSpanId());
  }

  @Test
  void spanContext_childInheritsBaggage() {
    SpanContext parent =
        SpanContext.builder().traceId("t1").spanId("s1").baggage("key", "value").build();
    SpanContext child = parent.newChild("s2");
    assertEquals("value", child.getBaggageItem("key"));
  }

  @Test
  void spanContext_baggageIsImmutable() {
    SpanContext span = SpanContext.builder().traceId("t1").spanId("s1").baggage("k", "v").build();
    assertThrows(UnsupportedOperationException.class, () -> span.getBaggage().put("new", "val"));
  }

  @Test
  void spanContext_rejectsMissingTraceId() {
    assertThrows(IllegalArgumentException.class, () -> SpanContext.builder().spanId("s1").build());
  }

  // ===== Tracer: current span =====

  @Test
  void startSpan_createsRootSpan() {
    SpanContext span = Tracer.startSpan();
    assertNotNull(span);
    assertNotNull(span.getTraceId());
    assertNotNull(span.getSpanId());
    assertTrue(span.isRoot());
    assertSame(span, Tracer.getCurrentSpan());
  }

  @Test
  void startChildSpan_createsChild() {
    SpanContext parent = Tracer.startSpan();
    SpanContext child = Tracer.startChildSpan();

    assertEquals(parent.getTraceId(), child.getTraceId());
    assertEquals(parent.getSpanId(), child.getParentSpanId());
    assertSame(child, Tracer.getCurrentSpan());
  }

  @Test
  void clearCurrentSpan_removesSpan() {
    Tracer.startSpan();
    assertTrue(Tracer.hasCurrentSpan());
    Tracer.clearCurrentSpan();
    assertFalse(Tracer.hasCurrentSpan());
  }

  // ===== Cross-thread propagation =====

  @Test
  void captureAndRestore_propagatesAcrossThreads() throws Exception {
    SpanContext span = Tracer.startSpan();
    TracingSnapshot snapshot = Tracer.capture();

    Thread t =
        new Thread(
            () -> {
              assertNull(Tracer.getCurrentSpan());
              Tracer.restore(snapshot);
              assertEquals(span.getTraceId(), Tracer.getCurrentSpan().getTraceId());
            });
    t.start();
    t.join(5000);
  }

  @Test
  void tracingSnapshot_nullSpan() {
    TracingSnapshot snap = new TracingSnapshot(null);
    assertFalse(snap.hasSpan());
    assertNull(snap.getSpanContext());
  }

  // ===== HTTP header propagation =====

  @Test
  void injectAndExtract_roundTrip() {
    SpanContext original =
        SpanContext.builder()
            .traceId("trace-abc")
            .spanId("span-def")
            .parentSpanId("parent-ghi")
            .sampled(true)
            .baggage("tenantId", "tenant-1")
            .build();

    Map<String, String> headers = new HashMap<>();
    Tracer.inject(original, headers);

    // Verify headers are set
    assertEquals("trace-abc", headers.get("X-Trace-Id"));
    assertEquals("span-def", headers.get("X-Span-Id"));
    assertEquals("parent-ghi", headers.get("X-Parent-Span-Id"));
    assertEquals("true", headers.get("X-Sampled"));
    assertEquals("tenant-1", headers.get("X-Baggage-tenantId"));

    // Extract and verify
    SpanContext extracted = Tracer.extract(headers);
    assertNotNull(extracted);
    assertEquals("trace-abc", extracted.getTraceId());
    assertEquals("span-def", extracted.getSpanId());
    assertEquals("parent-ghi", extracted.getParentSpanId());
    assertTrue(extracted.isSampled());
    assertEquals("tenant-1", extracted.getBaggageItem("tenantId"));
  }

  @Test
  void extract_noHeaders_returnsNull() {
    assertNull(Tracer.extract(null));
    assertNull(Tracer.extract(new HashMap<>()));
  }

  @Test
  void inject_nullSpan_noOp() {
    Map<String, String> headers = new HashMap<>();
    assertDoesNotThrow(() -> Tracer.inject(null, headers));
    assertTrue(headers.isEmpty());
  }

  // ===== Baggage =====

  @Test
  void setAndGetBaggage() {
    Tracer.startSpan();
    Tracer.setBaggage("requestId", "req-123");
    assertEquals("req-123", Tracer.getBaggage("requestId"));
  }

  @Test
  void getBaggage_noSpan_returnsNull() {
    assertNull(Tracer.getBaggage("key"));
  }

  // ===== W3C trace context =====

  @Test
  void startSpan_generatesW3cShapedIds() {
    SpanContext span = Tracer.startSpan("GET /users");
    assertTrue(span.getTraceId().matches("[0-9a-f]{32}"), span.getTraceId());
    assertTrue(span.getSpanId().matches("[0-9a-f]{16}"), span.getSpanId());
    assertEquals("GET /users", span.getOperationName());
  }

  @Test
  void startChildSpan_getsItsOwnW3cSpanIdAndKeepsTheTraceId() {
    SpanContext parent = Tracer.startSpan("parent");
    SpanContext child = Tracer.startChildSpan("child");

    assertEquals(parent.getTraceId(), child.getTraceId());
    assertEquals(parent.getSpanId(), child.getParentSpanId());
    assertTrue(child.getSpanId().matches("[0-9a-f]{16}"));
    assertNotEquals(parent.getSpanId(), child.getSpanId());
    assertEquals("child", child.getOperationName());
  }

  @Test
  void inject_writesTraceparentForW3cIds() {
    SpanContext span = Tracer.startSpan();
    Map<String, String> headers = new HashMap<>();
    Tracer.inject(span, headers);

    assertEquals(
        "00-" + span.getTraceId() + "-" + span.getSpanId() + "-01",
        headers.get(Tracer.TRACEPARENT_HEADER));
  }

  @Test
  void traceparent_roundTripPreservesIdsAndSampledFlag() {
    SpanContext span = Tracer.startSpan();
    Map<String, String> headers = new HashMap<>();
    Tracer.inject(span, headers);

    SpanContext extracted = Tracer.extract(headers);
    assertNotNull(extracted);
    assertEquals(span.getTraceId(), extracted.getTraceId());
    assertEquals(span.getSpanId(), extracted.getSpanId());
    assertTrue(extracted.isSampled());
  }

  @Test
  void extract_prefersTraceparentOverLegacyHeaders() {
    Map<String, String> headers = new HashMap<>();
    headers.put("X-Trace-Id", "legacy-trace");
    headers.put("X-Span-Id", "legacy-span");
    headers.put(
        "traceparent", "00-4bf92f3577b34da6a3ce929d0e0e4736-00f067aa0ba902b7-00");

    SpanContext extracted = Tracer.extract(headers);
    assertNotNull(extracted);
    assertEquals("4bf92f3577b34da6a3ce929d0e0e4736", extracted.getTraceId());
    assertEquals("00f067aa0ba902b7", extracted.getSpanId());
    assertFalse(extracted.isSampled());
  }

  @Test
  void extract_invalidTraceparentFallsBackToLegacyHeaders() {
    Map<String, String> headers = new HashMap<>();
    headers.put("traceparent", "00-not-hex-00");
    headers.put("X-Trace-Id", "legacy-trace");
    headers.put("X-Span-Id", "legacy-span");

    SpanContext extracted = Tracer.extract(headers);
    assertNotNull(extracted);
    assertEquals("legacy-trace", extracted.getTraceId());
  }

  @Test
  void extract_acceptsFutureTraceparentVersionWithExtraFields() {
    Map<String, String> headers = new HashMap<>();
    headers.put(
        "traceparent",
        "01-4bf92f3577b34da6a3ce929d0e0e4736-00f067aa0ba902b7-01-extra");

    SpanContext extracted = Tracer.extract(headers);
    assertNotNull(extracted);
    assertEquals("4bf92f3577b34da6a3ce929d0e0e4736", extracted.getTraceId());
  }

  @Test
  void inject_skipsTraceparentForNonW3cIdsButKeepsLegacyHeaders() {
    SpanContext handBuilt = SpanContext.builder().traceId("trace-abc").spanId("span-def").build();
    Map<String, String> headers = new HashMap<>();
    Tracer.inject(handBuilt, headers);

    assertFalse(headers.containsKey(Tracer.TRACEPARENT_HEADER));
    assertEquals("trace-abc", headers.get("X-Trace-Id"));
  }

  @Test
  void w3cBaggage_roundTrip() {
    Tracer.startSpan();
    Tracer.setBaggage("tenant", "acme corp, inc");
    Map<String, String> headers = new HashMap<>();
    Tracer.inject(Tracer.getCurrentSpan(), headers);

    SpanContext extracted = Tracer.extract(headers);
    assertNotNull(extracted);
    assertEquals("acme corp, inc", extracted.getBaggageItem("tenant"));
  }

  // ===== Span attributes =====

  @Test
  void attributes_areCarriedIntoTheCompletedSpan() {
    final List<SpanContext> completed = new ArrayList<>();
    SpanCompletionListener listener = (span, durationMs) -> completed.add(span);
    Tracer.addCompletionListener(listener);
    try {
      Tracer.startSpan("GET /orders");
      Tracer.setAttribute("http.method", "GET");
      Tracer.setAttribute("http.status_code", "200");
      Tracer.setAttribute("http.method", "POST");

      SpanContext finished = Tracer.endSpan(null, "OK");

      assertNotNull(finished);
      assertEquals("POST", finished.getAttribute("http.method"));
      assertEquals("200", finished.getAttribute("http.status_code"));
      assertEquals(1, completed.size());
      assertEquals("GET /orders", completed.get(0).getOperationName());
      assertEquals("POST", completed.get(0).getAttribute("http.method"));
    } finally {
      Tracer.removeCompletionListener(listener);
    }
  }

  @Test
  void setAttribute_nullValueRemovesIt() {
    Tracer.startSpan();
    Tracer.setAttribute("db.system", "postgresql");
    assertNotNull(Tracer.getAttribute("db.system"));
    Tracer.setAttribute("db.system", null);
    assertNull(Tracer.getAttribute("db.system"));
  }

  @Test
  void setBaggage_keepsAttributesSetEarlier() {
    Tracer.startSpan("GET /orders");
    Tracer.setAttribute("http.method", "GET");
    Tracer.setBaggage("tenant", "acme");

    SpanContext finished = Tracer.endSpan(null, "OK");

    assertNotNull(finished);
    assertEquals("acme", finished.getBaggageItem("tenant"));
    assertEquals("GET", finished.getAttribute("http.method"), "baggage must not drop attributes");
  }

  @Test
  void recordException_marksTheSpanAsError() {
    Tracer.startSpan("charge");
    Tracer.recordException(new IllegalStateException("gateway timeout"));

    SpanContext finished = Tracer.endSpan(null, null);
    assertNotNull(finished);
    assertEquals("true", finished.getAttribute("error"));
    assertEquals("java.lang.IllegalStateException", finished.getAttribute("error.type"));
    assertEquals("gateway timeout", finished.getAttribute("error.message"));
    assertEquals("ERROR", finished.getErrorStatus());
  }

  @Test
  void recordException_withoutASpanIsANoOp() {
    assertDoesNotThrow(() -> Tracer.recordException(new RuntimeException("ignored")));
  }

  @Test
  void setCompletionListenerReplacesEarlierListenersAndIgnoresNull() {
    final List<String> seen = new ArrayList<String>();
    SpanCompletionListener first =
        new SpanCompletionListener() {
          @Override
          public void onSpanComplete(SpanContext span, long durationMs) {
            seen.add("first");
          }
        };
    SpanCompletionListener second =
        new SpanCompletionListener() {
          @Override
          public void onSpanComplete(SpanContext span, long durationMs) {
            seen.add("second");
          }
        };
    try {
      Tracer.addCompletionListener(first);
      Tracer.setCompletionListener(second);
      Tracer.startSpan("named");
      Tracer.endSpan("", "");
      assertEquals(Collections.singletonList("second"), seen);

      seen.clear();
      Tracer.setCompletionListener(null);
      Tracer.startSpan("named");
      assertEquals("named", Tracer.endSpan(null, null).getOperationName());
      assertTrue(seen.isEmpty());
    } finally {
      Tracer.setCompletionListener(null);
    }
  }

  @Test
  void anUnnamedChildFallsBackToARootAndHeaderLookupIsCaseInsensitive() {
    SpanContext child = Tracer.startChildSpan("orphan");
    assertNull(child.getParentSpanId());

    Tracer.startSpan("checkout");
    Tracer.setBaggage("user", "a b");
    SpanContext span = Tracer.getCurrentSpan();
    Map<String, String> headers = new HashMap<String, String>();
    Tracer.inject(span, headers);

    Map<String, String> mixed = new HashMap<String, String>();
    mixed.put("TraceParent", headers.get(Tracer.TRACEPARENT_HEADER));
    mixed.put("Baggage", headers.get(Tracer.BAGGAGE_HEADER));
    SpanContext extracted = Tracer.extract(mixed);

    assertNotNull(extracted);
    assertEquals(span.getTraceId(), extracted.getTraceId());
    assertEquals("a b", extracted.getBaggageItem("user"));
    assertTrue(span.toString().contains(span.getTraceId()));
    assertThrows(
        IllegalArgumentException.class,
        () -> SpanContext.builder().traceId(span.getTraceId()).spanId("").build());
  }

  @Test
  void aBrokenBaggageMemberIsSkippedInsteadOfFailingExtraction() {
    Map<String, String> headers = new HashMap<String, String>();
    headers.put(
        Tracer.TRACEPARENT_HEADER, "00-4bf92f3577b34da6a3ce929d0e0e4736-00f067aa0ba902b7-01");
    headers.put(Tracer.BAGGAGE_HEADER, "bad,%zz=kept,good=value");

    SpanContext extracted = Tracer.extract(headers);

    assertNotNull(extracted);
    assertEquals("value", extracted.getBaggageItem("good"));
    assertEquals("kept", extracted.getBaggageItem("%zz"));
    assertNull(extracted.getBaggageItem("bad"));
  }
}
