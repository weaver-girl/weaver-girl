package com.github.cc11001100.weavergirl.core.trace;

import static org.junit.jupiter.api.Assertions.*;

import com.github.cc11001100.weavergirl.api.topology.ServiceEdge;
import com.github.cc11001100.weavergirl.api.topology.ServiceNode;
import com.github.cc11001100.weavergirl.api.topology.TopologyGraph;
import com.github.cc11001100.weavergirl.api.tracing.SpanContext;
import java.util.Collection;
import java.util.List;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

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
    SpanContext.Builder builder = SpanContext.builder().traceId("trace123").spanId("span456");
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
    SpanContext span =
        SpanContext.builder()
            .traceId("t1")
            .spanId("s1")
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
    assertEquals("unknown", TracerTopologyBridge.inferProtocol(null));
    assertEquals("kafka", TracerTopologyBridge.inferProtocol("kafka publish orders"));
  }

  /** Installing the bridge makes it the tracer's listener, and uninstalling clears it. */
  @Test
  void installAndUninstallSwapTheTracersListener() throws Exception {
    java.lang.reflect.Field holder =
        com.github.cc11001100.weavergirl.api.tracing.Tracer.class.getDeclaredField(
            "completionListeners");
    Object composite =
        com.github.cc11001100.weavergirl.core.testsupport.Statics.read(holder, null);
    java.util.List<com.github.cc11001100.weavergirl.api.tracing.SpanCompletionListener> before =
        new java.util.ArrayList<>();
    for (Object listener : listeners(composite)) {
      before.add((com.github.cc11001100.weavergirl.api.tracing.SpanCompletionListener) listener);
    }
    try {
      bridge.install();
      assertTrue(listeners(composite).contains(bridge), "install did not register the bridge");

      bridge.uninstall();
      assertFalse(listeners(composite).contains(bridge), "uninstall left the bridge registered");
    } finally {
      com.github.cc11001100.weavergirl.api.tracing.Tracer.setCompletionListener(null);
      for (com.github.cc11001100.weavergirl.api.tracing.SpanCompletionListener listener : before) {
        com.github.cc11001100.weavergirl.api.tracing.Tracer.addCompletionListener(listener);
      }
    }
  }

  private static java.util.List<?> listeners(Object composite) throws Exception {
    return new java.util.ArrayList<>(
        (java.util.Collection<?>) composite.getClass().getMethod("getListeners").invoke(composite));
  }
}
