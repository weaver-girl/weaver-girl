package com.github.cc11001100.weavergirl.core.topology;

import com.github.cc11001100.weavergirl.api.topology.ServiceEdge;
import com.github.cc11001100.weavergirl.api.topology.ServiceNode;
import com.github.cc11001100.weavergirl.api.topology.TopologyGraph;
import java.util.Collection;

/**
 * Exports the service topology as a JSON string.
 *
 * @since 1.2.0
 */
public class TopologyJsonExporter {

  /**
   * Export the current topology graph as JSON.
   *
   * @return JSON representation of the topology
   */
  public static String export() {
    StringBuilder json = new StringBuilder();
    json.append("{");

    // Nodes
    json.append("\"nodes\":[");
    Collection<ServiceNode> nodes = TopologyGraph.getNodes();
    boolean first = true;
    for (ServiceNode node : nodes) {
      if (!first) json.append(",");
      first = false;
      json.append("{");
      json.append("\"name\":\"").append(esc(node.getName())).append("\",");
      json.append("\"type\":\"").append(esc(node.getType())).append("\"");
      if (!node.getMetadata().isEmpty()) {
        json.append(",\"metadata\":{");
        boolean mf = true;
        for (java.util.Map.Entry<String, String> e : node.getMetadata().entrySet()) {
          if (!mf) json.append(",");
          mf = false;
          json.append("\"")
              .append(esc(e.getKey()))
              .append("\":\"")
              .append(esc(e.getValue()))
              .append("\"");
        }
        json.append("}");
      }
      json.append("}");
    }
    json.append("],");

    // Edges
    json.append("\"edges\":[");
    java.util.List<ServiceEdge> edges = TopologyGraph.getEdges();
    first = true;
    for (ServiceEdge edge : edges) {
      if (!first) json.append(",");
      first = false;
      json.append("{");
      json.append("\"source\":\"").append(esc(edge.getSource())).append("\",");
      json.append("\"target\":\"").append(esc(edge.getTarget())).append("\",");
      json.append("\"protocol\":\"").append(esc(edge.getProtocol())).append("\",");
      json.append("\"callCount\":").append(edge.getCallCount()).append(",");
      json.append("\"errorCount\":").append(edge.getErrorCount()).append(",");
      json.append("\"avgLatencyMs\":")
          .append(String.format("%.1f", edge.getAvgLatencyMs()))
          .append(",");
      json.append("\"errorRate\":").append(String.format("%.4f", edge.getErrorRate()));
      json.append("}");
    }
    json.append("],");

    // Summary
    json.append("\"nodeCount\":").append(TopologyGraph.nodeCount()).append(",");
    json.append("\"edgeCount\":").append(TopologyGraph.edgeCount());
    json.append("}");

    return json.toString();
  }

  private static String esc(String s) {
    if (s == null) return "";
    return s.replace("\\", "\\\\").replace("\"", "\\\"").replace("\n", "\\n");
  }
}
