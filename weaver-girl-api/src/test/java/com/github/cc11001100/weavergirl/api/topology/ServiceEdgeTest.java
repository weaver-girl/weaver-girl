package com.github.cc11001100.weavergirl.api.topology;

import static org.junit.jupiter.api.Assertions.*;

import org.junit.jupiter.api.Test;

class ServiceEdgeTest {

  @Test
  void edgeExposesDetailsAndComputesErrorRate() {
    ServiceEdge edge = new ServiceEdge("orders", "payments", "http", 4, 1, 12.5);
    assertEquals("orders", edge.getSource());
    assertEquals("payments", edge.getTarget());
    assertEquals("http", edge.getProtocol());
    assertEquals(4, edge.getCallCount());
    assertEquals(1, edge.getErrorCount());
    assertEquals(12.5, edge.getAvgLatencyMs());
    assertEquals(0.25, edge.getErrorRate());
    assertTrue(edge.toString().contains("orders"));
  }

  @Test
  void edgeWithNoCallsHasZeroErrorRate() {
    assertEquals(0, new ServiceEdge("a", "b", "grpc", 0, 0, 0).getErrorRate());
  }
}
