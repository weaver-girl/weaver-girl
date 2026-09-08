package com.github.cc11001100.weavergirl.core.topology;

import static org.junit.jupiter.api.Assertions.*;

import com.github.cc11001100.weavergirl.api.topology.ServiceNode;
import com.github.cc11001100.weavergirl.api.topology.TopologyGraph;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class TopologyJsonExporterTest {

  @BeforeEach
  void setUp() {
    TopologyGraph.clear();
  }

  @AfterEach
  void tearDown() {
    TopologyGraph.clear();
  }

  @Test
  void export_emptyGraph_returnsEmptyArrays() {
    String json = TopologyJsonExporter.export();
    assertTrue(json.contains("\"nodes\":[]"));
    assertTrue(json.contains("\"edges\":[]"));
    assertTrue(json.contains("\"nodeCount\":0"));
  }

  @Test
  void export_withNodesAndEdges_returnsValidJson() {
    TopologyGraph.addNode(new ServiceNode("svc-a", "service"));
    TopologyGraph.addNode(new ServiceNode("svc-b", "service"));
    TopologyGraph.recordCall("svc-a", "svc-b", "http", 50, false);

    String json = TopologyJsonExporter.export();
    assertTrue(json.contains("\"name\":\"svc-a\""));
    assertTrue(json.contains("\"name\":\"svc-b\""));
    assertTrue(json.contains("\"source\":\"svc-a\""));
    assertTrue(json.contains("\"target\":\"svc-b\""));
    assertTrue(json.contains("\"protocol\":\"http\""));
    assertTrue(json.contains("\"callCount\":1"));
    assertTrue(json.contains("\"errorCount\":0"));
  }

  @Test
  void export_multipleEdges_aggregatesCorrectly() {
    TopologyGraph.addNode(new ServiceNode("a", "service"));
    TopologyGraph.addNode(new ServiceNode("b", "service"));
    TopologyGraph.recordCall("a", "b", "grpc", 10, false);
    TopologyGraph.recordCall("a", "b", "grpc", 30, true);

    String json = TopologyJsonExporter.export();
    assertTrue(json.contains("\"callCount\":2"));
    assertTrue(json.contains("\"errorCount\":1"));
  }
}
