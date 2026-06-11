package com.github.cc11001100.weavergirl.core.trace;

import com.github.cc11001100.weavergirl.api.topology.ServiceEdge;
import com.github.cc11001100.weavergirl.api.topology.ServiceNode;
import com.github.cc11001100.weavergirl.api.topology.TopologyGraph;
import com.github.cc11001100.weavergirl.api.tracing.SpanContext;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.Collection;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class TracerTopologyBridgeTest {

    private TracerTopologyBridge bridge;

    @BeforeEach
    void setUp() {
        bridge = new TracerTopologyBridge();
        TopologyGraph.clear();
    }

    @AfterEach
    void tearDown() {
        TopologyGraph.clear();
    }

    private SpanContext spanWithServices(String source, String target, String protocol) {
        SpanContext.Builder builder = SpanContext.builder()
                .traceId("trace123").spanId("span456");
        if (source != null) builder.baggage("source.service", source);
        if (target != null) builder.baggage("target.service", target);
        if (protocol != null) builder.baggage("rpc.protocol", protocol);
        return builder.build();
    }

    @Test
    void onSpanComplete_registersSourceAndTargetNodes() {
        SpanContext span = spanWithServices("order-service", "payment-service", "http");
        bridge.onSpanComplete(span, 50);

        Collection<ServiceNode> nodes = TopologyGraph.getNodes();
        assertEquals(2, nodes.size());
    }

    @Test
    void onSpanComplete_recordsEdgeWithProtocol() {
        SpanContext span = spanWithServices("order-service", "payment-service", "http");
        bridge.onSpanComplete(span, 50);

        List<ServiceEdge> edges = TopologyGraph.getEdges();
        assertEquals(1, edges.size());
        assertEquals("order-service", edges.get(0).getSource());
        assertEquals("payment-service", edges.get(0).getTarget());
        assertEquals("http", edges.get(0).getProtocol());
        assertEquals(1, edges.get(0).getCallCount());
    }

    @Test
    void onSpanComplete_aggregatesMultipleCalls() {
        bridge.onSpanComplete(spanWithServices("svc-a", "svc-b", "grpc"), 10);
        bridge.onSpanComplete(spanWithServices("svc-a", "svc-b", "grpc"), 20);

        List<ServiceEdge> edges = TopologyGraph.getEdges();
        assertEquals(1, edges.size());
        assertEquals(2, edges.get(0).getCallCount());
    }

    @Test
    void onSpanComplete_noServiceBaggage_ignored() {
        SpanContext span = SpanContext.builder().traceId("t1").spanId("s1").build();
        bridge.onSpanComplete(span, 50);
        assertEquals(0, TopologyGraph.nodeCount());
    }

    @Test
    void onSpanComplete_disabled_doesNothing() {
        bridge.setEnabled(false);
        bridge.onSpanComplete(spanWithServices("a", "b", "http"), 50);
        assertEquals(0, TopologyGraph.nodeCount());
    }

    @Test
    void onSpanComplete_errorBaggage_marksError() {
        SpanContext span = SpanContext.builder()
                .traceId("t1").spanId("s1")
                .baggage("source.service", "a")
                .baggage("target.service", "b")
                .baggage("error", "true")
                .build();
        bridge.onSpanComplete(span, 50);
        List<ServiceEdge> edges = TopologyGraph.getEdges();
        assertEquals(1, edges.size());
        assertEquals(1, edges.get(0).getErrorCount());
    }

    @Test
    void inferProtocol_httpOperations() {
        assertEquals("http", TracerTopologyBridge.inferProtocol("HTTP GET /api/users"));
        assertEquals("http", TracerTopologyBridge.inferProtocol("POST /orders"));
        assertEquals("grpc", TracerTopologyBridge.inferProtocol("grpc/UserService/GetUser"));
        assertEquals("redis", TracerTopologyBridge.inferProtocol("redis GET key"));
        assertEquals("database", TracerTopologyBridge.inferProtocol("jdbc SELECT * FROM users"));
        assertEquals("unknown", TracerTopologyBridge.inferProtocol("custom-operation"));
    }
}
