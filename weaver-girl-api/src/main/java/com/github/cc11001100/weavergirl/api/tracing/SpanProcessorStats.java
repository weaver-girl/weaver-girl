package com.github.cc11001100.weavergirl.api.tracing;

/**
 * Counters for one registered {@link SpanProcessor}, keyed by {@link SpanProcessor#name()}.
 *
 * <p>A filter that drops too much is indistinguishable from an application that stopped producing
 * spans unless the drop count is observable. Read these from a metrics scrape.
 *
 * @since 1.0.0
 */
public final class SpanProcessorStats {

  private final String name;
  private final long processed;
  private final long dropped;
  private final long failures;

  public SpanProcessorStats(String name, long processed, long dropped, long failures) {
    this.name = name;
    this.processed = processed;
    this.dropped = dropped;
    this.failures = failures;
  }

  /** The processor's reported name. */
  public String getName() {
    return name;
  }

  /** Spans the processor returned a value for (kept or replaced). */
  public long getProcessed() {
    return processed;
  }

  /** Spans the processor dropped by returning {@code null}. */
  public long getDropped() {
    return dropped;
  }

  /** Invocations where the processor threw; the span was published unchanged. */
  public long getFailures() {
    return failures;
  }
}
