package com.github.cc11001100.weavergirl.api.tracing;

import java.util.concurrent.atomic.LongAdder;

/**
 * Self-telemetry for the tracing pipeline itself.
 *
 * <p>Until this class existed, a drop in the {@link SpanProcessor} chain, a sampling decision, or
 * an application that simply stopped tracing were indistinguishable — nothing counted spans. These
 * counters make the pipeline's own behaviour observable; the Prometheus exporter turns them into
 * {@code weavergirl_spans_*} series.
 *
 * <p>Only the totals live here. Per-processor processed/dropped/failed counts already exist in
 * {@link SpanProcessors#getStats()}, and per-extension timing in {@link
 * com.github.cc11001100.weavergirl.api.observability.ExtensionPerformanceTracker}.
 *
 * @since 1.3.0
 */
public final class TracingMetrics {

  private static final LongAdder started = new LongAdder();
  private static final LongAdder ended = new LongAdder();
  private static final LongAdder dropped = new LongAdder();
  private static final LongAdder published = new LongAdder();

  private TracingMetrics() {}

  /** Count a span created by the tracer. Called from {@link Tracer#startSpan}. */
  public static void recordSpanStarted() {
    started.increment();
  }

  /** Count a span that completed and made it through the processor chain. */
  public static void recordSpanEnded() {
    ended.increment();
  }

  /** Count a span that completed but was dropped because no span was active or a processor dropped it. */
  public static void recordSpanDropped() {
    dropped.increment();
  }

  /** Count a span delivered to completion listeners (exported to at least one consumer). */
  public static void recordSpanPublished() {
    published.increment();
  }

  /** Spans created so far. */
  public static long getStarted() {
    return started.sum();
  }

  /** Spans that completed so far, whether published or dropped. */
  public static long getEnded() {
    return ended.sum();
  }

  /** Completed spans that never reached a consumer: dropped by the processor chain or no span active. */
  public static long getDropped() {
    return dropped.sum();
  }

  /** Completed spans delivered to completion listeners. */
  public static long getPublished() {
    return published.sum();
  }

  /** Reset every counter. Tests use this so one test cannot see another's spans. */
  public static void reset() {
    started.reset();
    ended.reset();
    dropped.reset();
    published.reset();
  }
}