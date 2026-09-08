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
}
