package com.github.cc11001100.weavergirl.api.event;

/**
 * Counters for one registered {@link InterceptorEventTransformer}, keyed by its reported name.
 *
 * <p>A transformer that drops every event looks exactly like a plugin that stopped publishing
 * unless the drop count is observable, which is why the registry counts these.
 *
 * @since 1.3.0
 */
public final class EventTransformerStats {

  private final String name;
  private final long processed;
  private final long dropped;
  private final long failures;

  public EventTransformerStats(String name, long processed, long dropped, long failures) {
    this.name = name;
    this.processed = processed;
    this.dropped = dropped;
    this.failures = failures;
  }

  /** The transformer's reported name. */
  public String getName() {
    return name;
  }

  /** Events the transformer returned a value for (kept or replaced). */
  public long getProcessed() {
    return processed;
  }

  /** Events the transformer dropped by returning {@code null}. */
  public long getDropped() {
    return dropped;
  }

  /** Invocations where the transformer threw; the event was delivered unchanged. */
  public long getFailures() {
    return failures;
  }
}