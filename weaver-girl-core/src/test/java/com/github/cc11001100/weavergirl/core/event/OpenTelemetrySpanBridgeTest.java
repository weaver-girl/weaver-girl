package com.github.cc11001100.weavergirl.core.event;

import com.github.cc11001100.weavergirl.api.event.InterceptorEvent;
import org.junit.jupiter.api.*;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Tests for the OpenTelemetry Span Bridge.
 */
class OpenTelemetrySpanBridgeTest {

    private OpenTelemetrySpanBridge bridge;

    @BeforeEach
    void setUp() {
        bridge = new OpenTelemetrySpanBridge(100);
    }

    @Test
    void convertsEventToSpan() {
        InterceptorEvent event = InterceptorEvent.builder()
                .type("slow-query")
                .plugin("jdbc")
                .className("PreparedStatement")
                .methodName("execute")
                .durationMs(1500)
                .attribute("sql", "SELECT * FROM users")
                .build();

        Map<String, Object> span = bridge.convertToSpan(event);

        assertNotNull(span.get("traceId"));
        assertNotNull(span.get("spanId"));
        assertEquals("slow-query", span.get("name"));
        assertEquals("INTERNAL", span.get("kind"));

        @SuppressWarnings("unchecked")
        Map<String, Object> attrs = (Map<String, Object>) span.get("attributes");
        assertEquals("jdbc", attrs.get("weaver.plugin"));
        assertEquals("PreparedStatement", attrs.get("weaver.class"));
        assertEquals("execute", attrs.get("weaver.method"));
        assertEquals(1500L, attrs.get("weaver.duration_ms"));
        assertEquals("SELECT * FROM users", attrs.get("weaver.attr.sql"));
    }

    @Test
    void errorEventSetsErrorStatus() {
        InterceptorEvent event = InterceptorEvent.builder()
                .type("jdbc-error")
                .plugin("jdbc")
                .className("Connection")
                .build();

        Map<String, Object> span = bridge.convertToSpan(event);

        @SuppressWarnings("unchecked")
        Map<String, Object> status = (Map<String, Object>) span.get("status");
        assertEquals("ERROR", status.get("code"));
    }

    @Test
    void normalEventSetsOkStatus() {
        InterceptorEvent event = InterceptorEvent.builder()
                .type("http-request")
                .plugin("servlet")
                .build();

        Map<String, Object> span = bridge.convertToSpan(event);

        @SuppressWarnings("unchecked")
        Map<String, Object> status = (Map<String, Object>) span.get("status");
        assertEquals("OK", status.get("code"));
    }

    @Test
    void buffersRecentSpans() {
        for (int i = 0; i < 5; i++) {
            bridge.onEvent(InterceptorEvent.builder()
                    .type("event-" + i).plugin("test").build());
        }
        assertEquals(5, bridge.size());
    }

    @Test
    void respectsMaxBufferSize() {
        OpenTelemetrySpanBridge smallBridge = new OpenTelemetrySpanBridge(3);
        for (int i = 0; i < 10; i++) {
            smallBridge.onEvent(InterceptorEvent.builder()
                    .type("event-" + i).plugin("test").build());
        }
        assertEquals(3, smallBridge.size());
    }

    @Test
    void rendersOtelJson() {
        bridge.onEvent(InterceptorEvent.builder()
                .type("slow-query").plugin("jdbc")
                .className("Stmt").methodName("execute")
                .durationMs(100)
                .attribute("sql", "SELECT 1")
                .build());

        String json = bridge.renderOtelJson();
        assertTrue(json.contains("\"resourceSpans\""));
        assertTrue(json.contains("\"service.name\""));
        assertTrue(json.contains("\"slow-query\""));
        assertTrue(json.contains("\"jdbc\""));
        assertTrue(json.contains("\"SELECT 1\""));
    }

    @Test
    void clearRemovesAllSpans() {
        bridge.onEvent(InterceptorEvent.builder().type("test").plugin("test").build());
        assertEquals(1, bridge.size());
        bridge.clear();
        assertEquals(0, bridge.size());
    }
}
