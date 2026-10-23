package com.github.cc11001100.weavergirl.api.observability;

/**
 * Delivery counters for one registered {@link SpanSink}, keyed by the sink's {@link
 * SpanSink#name()}.
 *
 * <p>A sink that throws is isolated and cannot affect the application, which also makes the failure
 * invisible without these counters. They are cheap lock-free snapshots, safe to read from a metrics
 * scrape.
 *
 * @since 1.0.0
 */
public final class SpanSinkStats {

  private final String name;
  private final long deliveries;
  private final long failures;

  public SpanSinkStats(String name, long deliveries, long failures) {
    this.name = name;
    this.deliveries = deliveries;
    this.failures = failures;
  }

  /** The sink's reported name. */
  public String getName() {
    return name;
  }

  /** Total completed spans handed to the sink, including the ones it failed on. */
  public long getDeliveries() {
    return deliveries;
  }

  /** Deliveries whose {@code onSpanEnd} threw. */
  public long getFailures() {
    return failures;
  }
}
