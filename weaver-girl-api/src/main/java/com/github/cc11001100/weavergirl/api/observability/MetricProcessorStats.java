package com.github.cc11001100.weavergirl.api.observability;

/**
 * Counters for one registered {@link MetricProcessor}, keyed by its reported name.
 *
 * <p>A processor that drops every sample looks exactly like a source that stopped reporting unless
 * the drop count is observable, which is why the registry counts these.
 *
 * @since 1.3.0
 */
public final class MetricProcessorStats {

  private final String name;
  private final long processed;
  private final long dropped;
  private final long failures;

  public MetricProcessorStats(String name, long processed, long dropped, long failures) {
    this.name = name;
    this.processed = processed;
    this.dropped = dropped;
    this.failures = failures;
  }

  /** The processor's reported name. */
  public String getName() {
    return name;
  }

  /** Samples the processor returned a value for (kept or replaced). */
  public long getProcessed() {
    return processed;
  }

  /** Samples the processor dropped by returning {@code null}. */
  public long getDropped() {
    return dropped;
  }

  /** Invocations where the processor threw; the sample was kept unchanged. */
  public long getFailures() {
    return failures;
  }
}