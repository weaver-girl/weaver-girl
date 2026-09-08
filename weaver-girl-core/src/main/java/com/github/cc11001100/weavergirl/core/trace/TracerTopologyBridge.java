package com.github.cc11001100.weavergirl.core.trace;

import com.github.cc11001100.weavergirl.api.topology.ServiceNode;
import com.github.cc11001100.weavergirl.api.topology.TopologyGraph;
import com.github.cc11001100.weavergirl.api.tracing.SpanCompletionListener;
import com.github.cc11001100.weavergirl.api.tracing.SpanContext;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Bridge connecting Tracer to TopologyGraph. Listens for completed spans and extracts service
 * identity from baggage to automatically build the service topology.
 *
 * <p>Baggage keys used:
 *
 * <ul>
 *   <li>{@code source.service} — calling service name
 *   <li>{@code target.service} — called service name
 *   <li>{@code rpc.protocol} — communication protocol (e.g., "http", "grpc")
 * </ul>
 *
 * @since 1.2.0
 */
public class TracerTopologyBridge implements SpanCompletionListener {

  private static final Logger log = LoggerFactory.getLogger(TracerTopologyBridge.class);

  static final String SOURCE_SERVICE_KEY = "source.service";
  static final String TARGET_SERVICE_KEY = "target.service";
  static final String PROTOCOL_KEY = "rpc.protocol";

  private volatile boolean enabled = true;

  @Override
  public void onSpanComplete(SpanContext span, long durationMs) {
    if (!enabled) return;

    String source = span.getBaggageItem(SOURCE_SERVICE_KEY);
    String target = span.getBaggageItem(TARGET_SERVICE_KEY);

    // At minimum, need source or target to update topology
    if (source == null && target == null) return;

    // Register nodes
    if (source != null && !source.isEmpty()) {
      TopologyGraph.addNode(new ServiceNode(source, "service"));
    }
    if (target != null && !target.isEmpty()) {
      TopologyGraph.addNode(new ServiceNode(target, "service"));
    }

    // Record edge if both source and target are present
    if (source != null && target != null) {
      String protocol = span.getBaggageItem(PROTOCOL_KEY);
      if (protocol == null || protocol.isEmpty()) {
        protocol = inferProtocol(span.getOperationName());
      }
      boolean error = span.getBaggageItem("error") != null;
      TopologyGraph.recordCall(source, target, protocol, durationMs, error);
    }

    log.debug(
        "Topology updated from span: trace={} source={} target={}",
        span.getTraceId(),
        source,
        target);
  }

  /** Infer protocol from operation name heuristics. */
  static String inferProtocol(String operationName) {
    if (operationName == null) return "unknown";
    String lower = operationName.toLowerCase();
    // Check specific protocols first (before generic HTTP keywords like "get"/"post")
    if (lower.contains("grpc")) return "grpc";
    if (lower.contains("redis")) return "redis";
    if (lower.contains("kafka")) return "kafka";
    if (lower.contains("jdbc") || lower.contains("sql") || lower.contains("database")) {
      return "database";
    }
    if (lower.startsWith("http")
        || lower.contains("get")
        || lower.contains("post")
        || lower.contains("put")
        || lower.contains("delete")) {
      return "http";
    }
    return "unknown";
  }

  /** Enable or disable this bridge. */
  public void setEnabled(boolean enabled) {
    this.enabled = enabled;
  }

  /**
   * Install this bridge as Tracer's completion listener. Note: this replaces any existing listener.
   * For coexistence with TracerSpanExporterBridge, use a composite listener pattern.
   */
  public void install() {
    com.github.cc11001100.weavergirl.api.tracing.Tracer.setCompletionListener(this);
  }

  /** Uninstall this bridge. */
  public void uninstall() {
    com.github.cc11001100.weavergirl.api.tracing.Tracer.setCompletionListener(null);
  }
}
