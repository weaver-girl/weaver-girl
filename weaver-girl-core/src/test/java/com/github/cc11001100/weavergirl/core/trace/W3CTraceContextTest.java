package com.github.cc11001100.weavergirl.core.trace;

import com.github.cc11001100.weavergirl.api.context.ThreadContext;
import org.junit.jupiter.api.*;

import java.util.HashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Tests for W3C Trace Context propagation.
 */
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
        W3CTraceContext ctx = W3CTraceContext.create(
                "abc123def456abc123def456abc12345", "0123456789abcdef", "01");
        ctx.propagateToThreadContext();

        W3CTraceContext restored = W3CTraceContext.fromThreadContext();
        assertNotNull(restored);
        assertEquals("abc123def456abc123def456abc12345", restored.getTraceId());
        assertEquals("0123456789abcdef", restored.getSpanId());
    }

    @Test
    void injectIntoHeaders() {
        W3CTraceContext ctx = W3CTraceContext.create(
                "4bf92f3577b34da6a3ce929d0e0e4736", "00f067aa0ba902b7", "01");

        Map<String, String> outgoing = new HashMap<>();
        ctx.injectIntoHeaders(outgoing);

        assertEquals("00-4bf92f3577b34da6a3ce929d0e0e4736-00f067aa0ba902b7-01",
                outgoing.get("traceparent"));
    }

    @Test
    void newChildSpan() {
        W3CTraceContext parent = W3CTraceContext.create(
                "4bf92f3577b34da6a3ce929d0e0e4736", "00f067aa0ba902b7", "01");

        W3CTraceContext child = parent.newChildSpan();

        assertEquals(parent.getTraceId(), child.getTraceId());
        assertNotEquals(parent.getSpanId(), child.getSpanId());
        assertEquals(parent.getTraceFlags(), child.getTraceFlags());
    }

    @Test
    void formatTraceparent() {
        W3CTraceContext ctx = W3CTraceContext.create(
                "4bf92f3577b34da6a3ce929d0e0e4736", "00f067aa0ba902b7", "01");

        assertEquals("00-4bf92f3577b34da6a3ce929d0e0e4736-00f067aa0ba902b7-01",
                ctx.formatTraceparent());
    }

    @Test
    void notSampledWhenFlagsZero() {
        W3CTraceContext ctx = W3CTraceContext.create(
                "4bf92f3577b34da6a3ce929d0e0e4736", "00f067aa0ba902b7", "00");
        assertFalse(ctx.isSampled());
    }
}
