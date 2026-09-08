package com.github.cc11001100.weavergirl.plugins.servlet;

import static org.junit.jupiter.api.Assertions.*;

import com.github.cc11001100.weavergirl.api.tracing.SpanContext;
import com.github.cc11001100.weavergirl.api.tracing.Tracer;
import java.util.HashMap;
import java.util.Map;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * Tests for cross-service HTTP trace context propagation in the servlet plugin.
 *
 * <p>These tests verify the Tracer inject/extract round-trip behavior used by the servlet
 * interceptor, without requiring real servlet objects.
 */
class ServletTracePropagationTest {

  @BeforeEach
  void setUp() {
    // Ensure no stale span from previous tests
    Tracer.clearCurrentSpan();
  }

  @AfterEach
  void tearDown() {
    Tracer.clearCurrentSpan();
  }

  @Test
  void tracerInject_shouldAddTraceHeaders() {
    SpanContext span =
        SpanContext.builder()
            .traceId("abc123trace")
            .spanId("def456span")
            .parentSpanId("parent789")
            .sampled(true)
            .build();

    Map<String, String> headers = new HashMap<>();
    Tracer.inject(span, headers);

    // Verify standard trace headers are present
    assertEquals("abc123trace", headers.get("X-Trace-Id"));
    assertEquals("def456span", headers.get("X-Span-Id"));
    assertEquals("parent789", headers.get("X-Parent-Span-Id"));
    assertEquals("true", headers.get("X-Sampled"));
  }

  @Test
  void tracerExtract_shouldParseIncomingHeaders() {
    Map<String, String> headers = new HashMap<>();
    headers.put("X-Trace-Id", "incoming-trace-xyz");
    headers.put("X-Span-Id", "incoming-span-abc");
    headers.put("X-Parent-Span-Id", "parent-of-incoming");
    headers.put("X-Sampled", "false");

    SpanContext extracted = Tracer.extract(headers);

    assertNotNull(extracted);
    assertEquals("incoming-trace-xyz", extracted.getTraceId());
    assertEquals("incoming-span-abc", extracted.getSpanId());
    assertEquals("parent-of-incoming", extracted.getParentSpanId());
    assertFalse(extracted.isSampled());
  }

  @Test
  void roundTrip_injectExtract_shouldPreserveTraceInfo() {
    // Create a span, inject into headers, then extract back
    SpanContext original =
        SpanContext.builder()
            .traceId("round-trip-trace-id")
            .spanId("round-trip-span-id")
            .parentSpanId("round-trip-parent")
            .sampled(true)
            .build();

    Map<String, String> headers = new HashMap<>();
    Tracer.inject(original, headers);

    SpanContext restored = Tracer.extract(headers);

    assertNotNull(restored);
    assertEquals(original.getTraceId(), restored.getTraceId());
    assertEquals(original.getSpanId(), restored.getSpanId());
    assertEquals(original.getParentSpanId(), restored.getParentSpanId());
    assertEquals(original.isSampled(), restored.isSampled());
  }

  @Test
  void tracerExtract_noHeaders_shouldReturnNull() {
    Map<String, String> headers = new HashMap<>();
    assertNull(Tracer.extract(headers));
  }

  @Test
  void tracerExtract_nullMap_shouldReturnNull() {
    assertNull(Tracer.extract(null));
  }

  @Test
  void tracerExtract_partialHeaders_shouldReturnNull() {
    // Only trace ID, no span ID — should return null
    Map<String, String> headers = new HashMap<>();
    headers.put("X-Trace-Id", "only-trace");
    assertNull(Tracer.extract(headers));
  }

  @Test
  void baggage_shouldPropagateViaHeaders() {
    // Create a span with baggage, inject, extract, verify baggage preserved
    SpanContext original =
        SpanContext.builder()
            .traceId("baggage-trace")
            .spanId("baggage-span")
            .baggage("userId", "user-42")
            .baggage("tenantId", "tenant-acme")
            .build();

    Map<String, String> headers = new HashMap<>();
    Tracer.inject(original, headers);

    // Verify baggage headers are present in the map
    assertEquals("user-42", headers.get("X-Baggage-userId"));
    assertEquals("tenant-acme", headers.get("X-Baggage-tenantId"));

    // Extract and verify baggage is preserved
    SpanContext restored = Tracer.extract(headers);

    assertNotNull(restored);
    assertEquals("user-42", restored.getBaggageItem("userId"));
    assertEquals("tenant-acme", restored.getBaggageItem("tenantId"));
    assertEquals(2, restored.getBaggage().size());
  }

  @Test
  void inject_nullSpan_shouldNotModifyHeaders() {
    Map<String, String> headers = new HashMap<>();
    headers.put("Existing", "value");
    Tracer.inject(null, headers);
    assertEquals(1, headers.size());
    assertEquals("value", headers.get("Existing"));
  }

  @Test
  void inject_nullHeaders_shouldNotThrow() {
    SpanContext span = SpanContext.builder().traceId("test").spanId("span").build();
    assertDoesNotThrow(() -> Tracer.inject(span, null));
  }

  @Test
  void extractHeadersFromRequest_nullRequest_returnsEmptyMap() {
    Map<String, String> headers = ServletPlugin.extractHeadersFromRequest(null);
    assertNotNull(headers);
    assertTrue(headers.isEmpty());
  }

  @Test
  void injectHeadersIntoResponse_nullResponse_doesNotThrow() {
    Map<String, String> headers = new HashMap<>();
    headers.put("X-Trace-Id", "test");
    assertDoesNotThrow(() -> ServletPlugin.injectHeadersIntoResponse(null, headers));
  }

  @Test
  void injectHeadersIntoResponse_nullHeaders_doesNotThrow() {
    assertDoesNotThrow(() -> ServletPlugin.injectHeadersIntoResponse(new Object(), null));
  }

  @Test
  void extractHeadersFromRequest_nonServletRequest_returnsEmptyMap() {
    // A plain object does not have getHeaderNames(), so returns empty
    Map<String, String> headers = ServletPlugin.extractHeadersFromRequest(new Object());
    assertNotNull(headers);
    assertTrue(headers.isEmpty());
  }

  @Test
  void injectHeadersIntoResponse_nonServletResponse_doesNotThrow() {
    Map<String, String> headers = new HashMap<>();
    headers.put("X-Trace-Id", "test");
    // A plain object does not have setHeader(), should not throw
    assertDoesNotThrow(() -> ServletPlugin.injectHeadersIntoResponse(new Object(), headers));
  }

  @Test
  void spanLifecycle_injectAfterExtract_shouldWork() {
    // Simulate the full servlet interceptor lifecycle:
    // 1. Incoming request with trace headers
    Map<String, String> incomingHeaders = new HashMap<>();
    incomingHeaders.put("X-Trace-Id", "lifecycle-trace");
    incomingHeaders.put("X-Span-Id", "incoming-span");
    incomingHeaders.put("X-Sampled", "true");

    SpanContext extracted = Tracer.extract(incomingHeaders);
    assertNotNull(extracted);

    // 2. Set as current span (continuing the trace)
    Tracer.setCurrentSpan(extracted);
    assertEquals("lifecycle-trace", Tracer.getCurrentSpan().getTraceId());

    // 3. Inject into response headers (simulating response)
    Map<String, String> outgoingHeaders = new HashMap<>();
    Tracer.inject(Tracer.getCurrentSpan(), outgoingHeaders);

    assertEquals("lifecycle-trace", outgoingHeaders.get("X-Trace-Id"));
    assertEquals("incoming-span", outgoingHeaders.get("X-Span-Id"));

    // 4. End span
    Tracer.endSpan("servlet", "OK");
    assertNull(Tracer.getCurrentSpan());
  }

  @Test
  void spanLifecycle_noIncomingHeaders_startsNewTrace() {
    // Simulate servlet interceptor with no incoming trace headers:
    // 1. No headers → extract returns null → start new span
    Map<String, String> incomingHeaders = new HashMap<>();
    SpanContext extracted = Tracer.extract(incomingHeaders);
    assertNull(extracted);

    // 2. Start a new root span
    SpanContext newSpan = Tracer.startSpan();
    assertNotNull(newSpan);
    assertNotNull(newSpan.getTraceId());
    assertNotNull(newSpan.getSpanId());
    assertNull(newSpan.getParentSpanId()); // root span has no parent
    assertTrue(newSpan.isRoot());

    // 3. Inject into response headers
    Map<String, String> outgoingHeaders = new HashMap<>();
    Tracer.inject(Tracer.getCurrentSpan(), outgoingHeaders);

    assertNotNull(outgoingHeaders.get("X-Trace-Id"));
    assertNotNull(outgoingHeaders.get("X-Span-Id"));

    // 4. End span
    Tracer.endSpan("servlet", "OK");
    assertNull(Tracer.getCurrentSpan());
  }
}
