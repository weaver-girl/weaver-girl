package com.github.cc11001100.weavergirl.api.observability;

/**
 * Immutable performance snapshot for one extension instance.
 *
 * <p>An extension is any runtime hook the agent calls on the application's behalf: a {@link
 * com.github.cc11001100.weavergirl.api.tracing.SpanProcessor}, a {@link SpanSink}, a {@link
 * MetricSource}, a {@link HealthCheck}, a {@link TagEnricher}. These counters make a slow or broken
 * one visible from a metrics scrape, which is otherwise impossible because every extension call is
 * isolated — a throwing extension is silent by design.
 *
 * <p>The {@code kind} label tells the scrape which extension point the numbers belong to; the
 * {@code name} label is what the extension reports through its own {@code name()} method.
 *
 * @see ExtensionPerformanceTracker
 * @since 1.2.0
 */
public final class ExtensionStats {

  private final String kind;
  private final String name;
  private final long invocations;
  private final long errors;
  private final double totalDurationMs;
  private final double avgDurationMs;
  private final long lastInvocationAt;
  private final long lastErrorAt;

  ExtensionStats(
      String kind,
      String name,
      long invocations,
      long errors,
      double totalDurationMs,
      double avgDurationMs,
      long lastInvocationAt,
      long lastErrorAt) {
    this.kind = kind;
    this.name = name;
    this.invocations = invocations;
    this.errors = errors;
    this.totalDurationMs = totalDurationMs;
    this.avgDurationMs = avgDurationMs;
    this.lastInvocationAt = lastInvocationAt;
    this.lastErrorAt = lastErrorAt;
  }

  /** The extension point, for example {@code "span-processor"} or {@code "tag-enricher"}. */
  public String getKind() {
    return kind;
  }

  /** The extension's own name. */
  public String getName() {
    return name;
  }

  /** How many times the agent called the extension. */
  public long getInvocations() {
    return invocations;
  }

  /** How many of those calls threw; the agent isolated the failure and carried on. */
  public long getErrors() {
    return errors;
  }

  /** Cumulative time spent inside the extension, in milliseconds. */
  public double getTotalDurationMs() {
    return totalDurationMs;
  }

  /** Mean call duration in milliseconds; 0 when never called. */
  public double getAvgDurationMs() {
    return avgDurationMs;
  }

  /** Epoch millis of the last call, or 0 when never called. */
  public long getLastInvocationAt() {
    return lastInvocationAt;
  }

  /** Epoch millis of the last failing call, or 0 when never failed. */
  public long getLastErrorAt() {
    return lastErrorAt;
  }

  /** Error share in {@code [0,1]}; 0 when never called. */
  public double getErrorRate() {
    return invocations > 0 ? (double) errors / invocations : 0.0;
  }

  @Override
  public String toString() {
    return "ExtensionStats{kind="
        + kind
        + ", name="
        + name
        + ", invocations="
        + invocations
        + ", errors="
        + errors
        + ", avgDurationMs="
        + avgDurationMs
        + "}";
  }
}
