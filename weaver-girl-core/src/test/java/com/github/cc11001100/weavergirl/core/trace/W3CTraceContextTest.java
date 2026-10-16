package com.github.cc11001100.weavergirl.core.trace;

import static org.junit.jupiter.api.Assertions.*;

import com.github.cc11001100.weavergirl.api.context.ThreadContext;
import java.util.HashMap;
import java.util.Map;
import org.junit.jupiter.api.*;

/** Tests for W3C Trace Context propagation. */
class W3CTraceContextTest {

  @AfterEach
  void tearDown() {
    ThreadContext.clear();
  }

  @Test
  void extractValidTraceparent() {
    Map<String, String> headers = new HashMap<>();
    headers.put("traceparent", "00-4bf92f3577b34da6a3ce929d0e0e4736-00f067aa0ba902b7-01");

    W3CTraceContext ctx = W3CTraceContext.extractFromHeaders(headers);

    assertNotNull(ctx);
    assertEquals("4bf92f3577b34da6a3ce929d0e0e4736", ctx.getTraceId());
    assertEquals("00f067aa0ba902b7", ctx.getSpanId());
    assertEquals("01", ctx.getTraceFlags());
    assertTrue(ctx.isSampled());
  }

  @Test
  void extractWithTracestate() {
    Map<String, String> headers = new HashMap<>();
    headers.put("traceparent", "00-4bf92f3577b34da6a3ce929d0e0e4736-00f067aa0ba902b7-01");
    headers.put("tracestate", "vendor1=value1,vendor2=value2");

    W3CTraceContext ctx = W3CTraceContext.extractFromHeaders(headers);

    assertNotNull(ctx);
    assertEquals(2, ctx.getTraceState().size());
    assertEquals("value1", ctx.getTraceState().get("vendor1"));
    assertEquals("value2", ctx.getTraceState().get("vendor2"));
  }

  @Test
  void extractNullHeaders() {
    assertNull(W3CTraceContext.extractFromHeaders(null));
  }

  @Test
  void extractMissingTraceparent() {
    Map<String, String> headers = new HashMap<>();
    headers.put("content-type", "application/json");

    assertNull(W3CTraceContext.extractFromHeaders(headers));
  }

  @Test
  void extractInvalidTraceparent() {
    Map<String, String> headers = new HashMap<>();
    headers.put("traceparent", "invalid-format");

    assertNull(W3CTraceContext.extractFromHeaders(headers));
  }

  @Test
  void extractUnsupportedVersion() {
    Map<String, String> headers = new HashMap<>();
    headers.put("traceparent", "01-4bf92f3577b34da6a3ce929d0e0e4736-00f067aa0ba902b7-01");

    assertNull(W3CTraceContext.extractFromHeaders(headers));
  }

  @Test
  void caseInsensitiveHeaderLookup() {
    Map<String, String> headers = new HashMap<>();
    headers.put("TraceParent", "00-4bf92f3577b34da6a3ce929d0e0e4736-00f067aa0ba902b7-01");

    W3CTraceContext ctx = W3CTraceContext.extractFromHeaders(headers);
    assertNotNull(ctx);
  }

  @Test
  void propagateToThreadContext() {
    W3CTraceContext ctx =
        W3CTraceContext.create("abc123def456abc123def456abc12345", "0123456789abcdef", "01");
    ctx.propagateToThreadContext();

    W3CTraceContext restored = W3CTraceContext.fromThreadContext();
    assertNotNull(restored);
    assertEquals("abc123def456abc123def456abc12345", restored.getTraceId());
    assertEquals("0123456789abcdef", restored.getSpanId());
  }

  @Test
  void injectIntoHeaders() {
    W3CTraceContext ctx =
        W3CTraceContext.create("4bf92f3577b34da6a3ce929d0e0e4736", "00f067aa0ba902b7", "01");

    Map<String, String> outgoing = new HashMap<>();
    ctx.injectIntoHeaders(outgoing);

    assertEquals(
        "00-4bf92f3577b34da6a3ce929d0e0e4736-00f067aa0ba902b7-01", outgoing.get("traceparent"));
  }

  @Test
  void newChildSpan() {
    W3CTraceContext parent =
        W3CTraceContext.create("4bf92f3577b34da6a3ce929d0e0e4736", "00f067aa0ba902b7", "01");

    W3CTraceContext child = parent.newChildSpan();

    assertEquals(parent.getTraceId(), child.getTraceId());
    assertNotEquals(parent.getSpanId(), child.getSpanId());
    assertEquals(parent.getTraceFlags(), child.getTraceFlags());
  }

  @Test
  void formatTraceparent() {
    W3CTraceContext ctx =
        W3CTraceContext.create("4bf92f3577b34da6a3ce929d0e0e4736", "00f067aa0ba902b7", "01");

    assertEquals(
        "00-4bf92f3577b34da6a3ce929d0e0e4736-00f067aa0ba902b7-01", ctx.formatTraceparent());
  }

  @Test
  void notSampledWhenFlagsZero() {
    W3CTraceContext ctx =
        W3CTraceContext.create("4bf92f3577b34da6a3ce929d0e0e4736", "00f067aa0ba902b7", "00");
    assertFalse(ctx.isSampled());
  }

  /** A header map with a null key is skipped rather than failing the lookup. */
  @Test
  void aNullHeaderKeyIsSkipped() {
    Map<String, String> headers = new HashMap<>();
    headers.put(null, "ignored");
    headers.put("traceparent", "00-4bf92f3577b34da6a3ce929d0e0e4736-00f067aa0ba902b7-01");

    assertNotNull(W3CTraceContext.extractFromHeaders(headers));
  }

  /** A tracestate that is blank, or holds an entry without an equals sign, adds no state. */
  @Test
  void aBlankOrMalformedTracestateAddsNothing() {
    Map<String, String> blank = new HashMap<>();
    blank.put("traceparent", "00-4bf92f3577b34da6a3ce929d0e0e4736-00f067aa0ba902b7-01");
    blank.put("tracestate", "   ");
    assertTrue(W3CTraceContext.extractFromHeaders(blank).getTraceState().isEmpty());

    Map<String, String> malformed = new HashMap<>();
    malformed.put("traceparent", "00-4bf92f3577b34da6a3ce929d0e0e4736-00f067aa0ba902b7-01");
    malformed.put("tracestate", "novalue,=missing,vendor=kept");
    Map<String, String> state = W3CTraceContext.extractFromHeaders(malformed).getTraceState();
    assertEquals(1, state.size());
    assertEquals("kept", state.get("vendor"));
  }

  /** Nothing was propagated, so nothing can be restored. */
  @Test
  void fromThreadContextWithoutATraceReturnsNull() {
    assertNull(W3CTraceContext.fromThreadContext());
  }

  /** A trace restored without flags defaults to sampled. */
  @Test
  void aRestoredTraceWithoutFlagsDefaultsToSampled() {
    ThreadContext.put("w3c.traceId", "4bf92f3577b34da6a3ce929d0e0e4736");
    ThreadContext.put("w3c.spanId", "00f067aa0ba902b7");

    W3CTraceContext restored = W3CTraceContext.fromThreadContext();

    assertEquals("01", restored.getTraceFlags());
    assertTrue(restored.isSampled());
  }

  /** Trace state round-trips through the thread context and into the outgoing headers. */
  @Test
  void traceStateRoundTripsThroughThreadContextAndHeaders() {
    Map<String, String> headers = new HashMap<>();
    headers.put("traceparent", "00-4bf92f3577b34da6a3ce929d0e0e4736-00f067aa0ba902b7-01");
    headers.put("tracestate", "vendor1=value1,vendor2=value2");
    W3CTraceContext ctx = W3CTraceContext.extractFromHeaders(headers);

    ctx.propagateToThreadContext();
    W3CTraceContext restored = W3CTraceContext.fromThreadContext();
    assertEquals("value1", restored.getTraceState().get("vendor1"));

    Map<String, String> outgoing = new HashMap<>();
    restored.injectIntoHeaders(outgoing);
    assertEquals("vendor1=value1,vendor2=value2", outgoing.get("tracestate"));
  }

  /** Injecting into no headers does nothing. */
  @Test
  void injectingIntoNullHeadersDoesNothing() {
    W3CTraceContext ctx =
        W3CTraceContext.create("4bf92f3577b34da6a3ce929d0e0e4736", "00f067aa0ba902b7", "01");
    assertDoesNotThrow(() -> ctx.injectIntoHeaders(null));
  }

  /** A child span keeps its parent's trace state. */
  @Test
  void aChildSpanKeepsItsParentsTraceState() {
    Map<String, String> headers = new HashMap<>();
    headers.put("traceparent", "00-4bf92f3577b34da6a3ce929d0e0e4736-00f067aa0ba902b7-01");
    headers.put("tracestate", "vendor=value");

    W3CTraceContext child = W3CTraceContext.extractFromHeaders(headers).newChildSpan();

    assertEquals("value", child.getTraceState().get("vendor"));
    assertTrue(child.toString().contains(child.getTraceId()));
  }
}
