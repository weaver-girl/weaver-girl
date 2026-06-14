package com.github.cc11001100.weavergirl.api.topology;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Builds and stores the service topology graph.
 *
 * <p>Thread-safe. Services are deduplicated by name. Edges are aggregated
 * by source+target+protocol.</p>
 *
 * @since 1.2.0
 */
public final class TopologyGraph {

    private static final ConcurrentHashMap<String, ServiceNode> nodes = new ConcurrentHashMap<>();
    private static final ConcurrentHashMap<String, EdgeAccumulator> edges = new ConcurrentHashMap<>();

    private TopologyGraph() {
    }

    /**
     * Register a service node.
     *
     * @param node the service node
     */
    public static void addNode(ServiceNode node) {
        if (node != null && node.getName() != null) {
            nodes.put(node.getName(), node);
        }
    }

    /**
     * Record a call between services.
     *
     * @param source   source service name
     * @param target   target service name
     * @param protocol communication protocol
     * @param durationMs call duration
     * @param error    whether the call resulted in an error
     */
    public static void recordCall(String source, String target, String protocol,
                                   long durationMs, boolean error) {
        if (source == null || target == null) return;
        String key = source + "->" + target + ":" + (protocol != null ? protocol : "unknown");
        edges.computeIfAbsent(key, k -> new EdgeAccumulator(source, target, protocol))
                .record(durationMs, error);
    }

    /**
     * Get all service nodes.
     */
    public static Collection<ServiceNode> getNodes() {
        return Collections.unmodifiableCollection(nodes.values());
    }

    /**
     * Get all service edges.
     */
    public static List<ServiceEdge> getEdges() {
        List<ServiceEdge> result = new ArrayList<>();
        for (EdgeAccumulator acc : edges.values()) {
            result.add(acc.toEdge());
        }
        return Collections.unmodifiableList(result);
    }

    /**
     * Get edges originating from a specific service.
     */
    public static List<ServiceEdge> getOutgoingEdges(String serviceName) {
        List<ServiceEdge> result = new ArrayList<>();
        for (EdgeAccumulator acc : edges.values()) {
            if (serviceName.equals(acc.source)) {
                result.add(acc.toEdge());
            }
        }
        return result;
    }

    /**
     * Get edges targeting a specific service.
     */
    public static List<ServiceEdge> getIncomingEdges(String serviceName) {
        List<ServiceEdge> result = new ArrayList<>();
        for (EdgeAccumulator acc : edges.values()) {
            if (serviceName.equals(acc.target)) {
                result.add(acc.toEdge());
            }
        }
        return result;
    }

    /**
     * Get node count.
     */
    public static int nodeCount() {
        return nodes.size();
    }

    /**
     * Get edge count.
     */
    public static int edgeCount() {
        return edges.size();
    }

    /**
     * Clear the topology graph.
     */
    public static void clear() {
        nodes.clear();
        edges.clear();
    }

    /**
     * Export topology as a simple text representation.
     */
    public static String toText() {
        StringBuilder sb = new StringBuilder();
        sb.append("=== Service Topology ===\n\n");
        sb.append("Nodes (").append(nodes.size()).append("):\n");
        for (ServiceNode node : nodes.values()) {
            sb.append("  ").append(node.getName()).append(" [").append(node.getType()).append("]\n");
        }
        sb.append("\nEdges (").append(edges.size()).append("):\n");
        for (EdgeAccumulator acc : edges.values()) {
            ServiceEdge e = acc.toEdge();
            sb.append(String.format("  %s → %s (%s) calls=%d errors=%d avg=%.1fms\n",
                    e.getSource(), e.getTarget(), e.getProtocol(),
                    e.getCallCount(), e.getErrorCount(), e.getAvgLatencyMs()));
        }
        return sb.toString();
    }

    // ===== Internal accumulator =====

    private static class EdgeAccumulator {
        final String source;
        final String target;
        final String protocol;
        final java.util.concurrent.atomic.AtomicLong callCount = new java.util.concurrent.atomic.AtomicLong(0);
        final java.util.concurrent.atomic.AtomicLong errorCount = new java.util.concurrent.atomic.AtomicLong(0);
        final java.util.concurrent.atomic.AtomicLong totalDurationMs = new java.util.concurrent.atomic.AtomicLong(0);

        EdgeAccumulator(String source, String target, String protocol) {
            this.source = source;
            this.target = target;
            this.protocol = protocol;
        }

        void record(long durationMs, boolean error) {
            callCount.incrementAndGet();
            totalDurationMs.addAndGet(durationMs);
            if (error) errorCount.incrementAndGet();
        }

        ServiceEdge toEdge() {
            long count = callCount.get();
            double avg = count > 0 ? (double) totalDurationMs.get() / count : 0;
            return new ServiceEdge(source, target, protocol, count, errorCount.get(), avg);
        }
    }
}
