package com.github.cc11001100.weavergirl.api.topology;

import static org.junit.jupiter.api.Assertions.*;

import java.util.*;
import org.junit.jupiter.api.*;

/** Tests for service topology (P58). */
class TopologyGraphTest {

  @AfterEach
  void tearDown() {
    TopologyGraph.clear();
  }

  // ===== ServiceNode =====

  @Test
  void node_creation() {
    ServiceNode node = new ServiceNode("order-service", "http", Map.of("version", "1.0"));
    assertEquals("order-service", node.getName());
    assertEquals("http", node.getType());
    assertEquals("1.0", node.getMetadata().get("version"));
  }

  @Test
  void node_equalityByName() {
    ServiceNode n1 = new ServiceNode("svc", "http");
    ServiceNode n2 = new ServiceNode("svc", "grpc");
    assertEquals(n1, n2);
    assertEquals(n1.hashCode(), n2.hashCode());
  }

  @Test
  void node_metadataImmutable() {
    ServiceNode node = new ServiceNode("svc", "http", new HashMap<>());
    assertThrows(UnsupportedOperationException.class, () -> node.getMetadata().put("k", "v"));
  }

  // ===== ServiceEdge =====

  @Test
  void edge_properties() {
    ServiceEdge edge = new ServiceEdge("a", "b", "http", 100, 5, 50.0);
    assertEquals("a", edge.getSource());
    assertEquals("b", edge.getTarget());
    assertEquals("http", edge.getProtocol());
    assertEquals(100, edge.getCallCount());
    assertEquals(5, edge.getErrorCount());
    assertEquals(50.0, edge.getAvgLatencyMs());
    assertEquals(0.05, edge.getErrorRate(), 0.001);
  }

  @Test
  void edge_zeroCallCountErrorRate() {
    ServiceEdge edge = new ServiceEdge("a", "b", "http", 0, 0, 0);
    assertEquals(0.0, edge.getErrorRate(), 0.001);
  }

  // ===== TopologyGraph =====

  @Test
  void addNode_registersService() {
    TopologyGraph.addNode(new ServiceNode("svc-a", "http"));
    assertEquals(1, TopologyGraph.nodeCount());
    assertTrue(TopologyGraph.getNodes().stream().anyMatch(n -> "svc-a".equals(n.getName())));
  }

  @Test
  void addNode_deduplicatesByName() {
    TopologyGraph.addNode(new ServiceNode("svc", "http"));
    TopologyGraph.addNode(new ServiceNode("svc", "grpc"));
    assertEquals(1, TopologyGraph.nodeCount());
  }

  @Test
  void recordCall_createsEdge() {
    TopologyGraph.recordCall("svc-a", "svc-b", "http", 100, false);
    TopologyGraph.recordCall("svc-a", "svc-b", "http", 200, false);

    assertEquals(1, TopologyGraph.edgeCount());
    ServiceEdge edge = TopologyGraph.getEdges().get(0);
    assertEquals("svc-a", edge.getSource());
    assertEquals("svc-b", edge.getTarget());
    assertEquals(2, edge.getCallCount());
    assertEquals(150.0, edge.getAvgLatencyMs(), 0.1);
  }

  @Test
  void recordCall_tracksErrors() {
    TopologyGraph.recordCall("a", "b", "grpc", 50, false);
    TopologyGraph.recordCall("a", "b", "grpc", 50, true);

    ServiceEdge edge = TopologyGraph.getEdges().get(0);
    assertEquals(1, edge.getErrorCount());
    assertEquals(0.5, edge.getErrorRate(), 0.01);
  }

  @Test
  void recordCall_differentProtocolsDifferentEdges() {
    TopologyGraph.recordCall("a", "b", "http", 50, false);
    TopologyGraph.recordCall("a", "b", "grpc", 50, false);
    assertEquals(2, TopologyGraph.edgeCount());
  }

  @Test
  void getOutgoingEdges() {
    TopologyGraph.recordCall("a", "b", "http", 50, false);
    TopologyGraph.recordCall("a", "c", "grpc", 50, false);
    TopologyGraph.recordCall("b", "c", "http", 50, false);

    assertEquals(2, TopologyGraph.getOutgoingEdges("a").size());
    assertEquals(1, TopologyGraph.getOutgoingEdges("b").size());
  }

  @Test
  void getIncomingEdges() {
    TopologyGraph.recordCall("a", "c", "http", 50, false);
    TopologyGraph.recordCall("b", "c", "grpc", 50, false);

    assertEquals(2, TopologyGraph.getIncomingEdges("c").size());
  }

  @Test
  void recordCall_nullSourceIgnored() {
    TopologyGraph.recordCall(null, "b", "http", 50, false);
    assertEquals(0, TopologyGraph.edgeCount());
  }

  @Test
  void clear_removesAll() {
    TopologyGraph.addNode(new ServiceNode("a", "http"));
    TopologyGraph.recordCall("a", "b", "http", 50, false);
    TopologyGraph.clear();
    assertEquals(0, TopologyGraph.nodeCount());
    assertEquals(0, TopologyGraph.edgeCount());
  }

  @Test
  void toText_containsInfo() {
    TopologyGraph.addNode(new ServiceNode("order-svc", "http"));
    TopologyGraph.recordCall("order-svc", "db", "jdbc", 50, false);

    String text = TopologyGraph.toText();
    assertTrue(text.contains("order-svc"));
    assertTrue(text.contains("Nodes"));
    assertTrue(text.contains("Edges"));
  }
}
