package com.github.cc11001100.weavergirl.api.topology;

/**
 * Represents a directed edge (call) between two services in the topology.
 *
 * @since 1.2.0
 */
public class ServiceEdge {

  private final String source;
  private final String target;
  private final String protocol;
  private final long callCount;
  private final long errorCount;
  private final double avgLatencyMs;

  public ServiceEdge(
      String source,
      String target,
      String protocol,
      long callCount,
      long errorCount,
      double avgLatencyMs) {
    this.source = source;
    this.target = target;
    this.protocol = protocol;
    this.callCount = callCount;
    this.errorCount = errorCount;
    this.avgLatencyMs = avgLatencyMs;
  }

  /** Source service name. */
  public String getSource() {
    return source;
  }

  /** Target service name. */
  public String getTarget() {
    return target;
  }

  /** Communication protocol (e.g. "http", "grpc", "jdbc", "redis"). */
  public String getProtocol() {
    return protocol;
  }

  /** Total call count. */
  public long getCallCount() {
    return callCount;
  }

  /** Error count. */
  public long getErrorCount() {
    return errorCount;
  }

  /** Average latency in milliseconds. */
  public double getAvgLatencyMs() {
    return avgLatencyMs;
  }

  /** Error rate (0.0 to 1.0). */
  public double getErrorRate() {
    return callCount > 0 ? (double) errorCount / callCount : 0.0;
  }

  @Override
  public String toString() {
    return "ServiceEdge{"
        + source
        + " → "
        + target
        + " ("
        + protocol
        + "), calls="
        + callCount
        + ", errors="
        + errorCount
        + "}";
  }
}
