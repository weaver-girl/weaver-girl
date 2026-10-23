package com.github.cc11001100.weavergirl.api.observability;

import com.github.cc11001100.weavergirl.api.tracing.SpanCompletionListener;
import com.github.cc11001100.weavergirl.api.tracing.SpanContext;
import com.github.cc11001100.weavergirl.api.tracing.Tracer;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicLong;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * Registry of {@link SpanSink}s, and the bridge that feeds them.
 *
 * <p>The tracer's completion listener is attached when the first sink is registered and detached
 * when the last one goes away, so an application that registers no sink pays nothing beyond an
 * empty list check. Registration is meant to happen during plugin initialization; it is thread-safe
 * and may also be done at runtime.
 *
 * <p>Registration order is delivery order. All sinks are called on the thread that ended the span,
 * and an exception from one is logged and does not affect the others.
 *
 * <p>Do not call {@link Tracer#setCompletionListener}: it removes every listener, including this
 * dispatcher and the built-in exporters. Use {@link Tracer#addCompletionListener} or this registry.
 *
 * @since 1.0.0
 */
public final class SpanSinks {

  private static final Logger log = Logger.getLogger(SpanSinks.class.getName());

  /** How long to stay quiet after one sink throws, so a broken sink cannot flood the log. */
  private static final long FAILURE_LOG_INTERVAL_MS = 60_000L;

  private static final List<SinkHolder> holders = new CopyOnWriteArrayList<>();
  private static final SpanCompletionListener DISPATCHER = SpanSinks::dispatch;
  private static volatile boolean dispatcherInstalled;

  private SpanSinks() {}

  /**
   * Register a sink. Registering the same instance twice is a no-op.
   *
   * @param sink the sink to receive completed spans
   */
  public static void register(SpanSink sink) {
    if (sink == null) {
      return;
    }
    synchronized (SpanSinks.class) {
      for (SinkHolder holder : holders) {
        if (holder.sink == sink) {
          return;
        }
      }
      holders.add(new SinkHolder(sink));
      if (!dispatcherInstalled) {
        Tracer.addCompletionListener(DISPATCHER);
        dispatcherInstalled = true;
      }
    }
  }

  /**
   * Remove a sink. When the last one is removed the tracer listener is detached as well.
   *
   * @param sink the sink to remove
   */
  public static void unregister(SpanSink sink) {
    if (sink == null) {
      return;
    }
    synchronized (SpanSinks.class) {
      SinkHolder found = null;
      for (SinkHolder holder : holders) {
        if (holder.sink == sink) {
          found = holder;
          break;
        }
      }
      if (found != null) {
        holders.remove(found);
      }
      if (holders.isEmpty() && dispatcherInstalled) {
        Tracer.removeCompletionListener(DISPATCHER);
        dispatcherInstalled = false;
      }
    }
  }

  /**
   * The registered sinks, in delivery order.
   *
   * @return an immutable snapshot
   */
  public static List<SpanSink> getSinks() {
    List<SpanSink> sinks = new ArrayList<>(holders.size());
    for (SinkHolder holder : holders) {
      sinks.add(holder.sink);
    }
    return Collections.unmodifiableList(sinks);
  }

  /** Remove every sink. Tests use this so one test does not deliver spans to another's sink. */
  public static void clear() {
    synchronized (SpanSinks.class) {
      holders.clear();
      if (dispatcherInstalled) {
        Tracer.removeCompletionListener(DISPATCHER);
        dispatcherInstalled = false;
      }
    }
  }

  /** Convert once and deliver to every sink; each sink is isolated from the others' failures. */
  private static void dispatch(SpanContext span, long durationMs) {
    if (span == null || holders.isEmpty()) {
      return;
    }
    SpanRecord record;
    try {
      record = SpanRecord.from(span, durationMs);
    } catch (RuntimeException e) {
      log.log(Level.WARNING, "Failed to convert completed span for sinks", e);
      return;
    }
    for (SinkHolder holder : holders) {
      holder.deliver(record);
    }
  }

  /**
   * Delivery and failure counters per sink name, for metric bridges.
   *
   * <p>Two sinks that report the same name are summed: the label a metrics backend sees cannot
   * distinguish them either.
   *
   * @return an immutable snapshot, empty when no sink is registered
   */
  public static Map<String, SpanSinkStats> getSinkStats() {
    Map<String, long[]> merged = new TreeMap<>();
    for (SinkHolder holder : holders) {
      String name = holder.name();
      long[] counts = merged.get(name);
      if (counts == null) {
        counts = new long[2];
        merged.put(name, counts);
      }
      counts[0] += holder.deliveries.get();
      counts[1] += holder.failures.get();
    }
    Map<String, SpanSinkStats> stats = new LinkedHashMap<>();
    for (Map.Entry<String, long[]> entry : merged.entrySet()) {
      long[] counts = entry.getValue();
      stats.put(entry.getKey(), new SpanSinkStats(entry.getKey(), counts[0], counts[1]));
    }
    return Collections.unmodifiableMap(stats);
  }

  /** A sink plus its counters and rate-limited failure logging state. */
  private static final class SinkHolder {
    private final SpanSink sink;
    private final AtomicLong lastWarningAt = new AtomicLong(0);
    private final AtomicLong failures = new AtomicLong(0);
    private final AtomicLong deliveries = new AtomicLong(0);

    SinkHolder(SpanSink sink) {
      this.sink = sink;
    }

    String name() {
      String name = sink.name();
      return name == null || name.trim().isEmpty() ? "unknown" : name.trim();
    }

    void deliver(SpanRecord record) {
      deliveries.incrementAndGet();
      try {
        sink.onSpanEnd(record);
      } catch (RuntimeException e) {
        long count = failures.incrementAndGet();
        long now = System.currentTimeMillis();
        long last = lastWarningAt.get();
        if (now - last >= FAILURE_LOG_INTERVAL_MS
            && lastWarningAt.compareAndSet(last, now)) {
          log.log(
              Level.WARNING,
              "SpanSink '"
                  + sink.name()
                  + "' threw ("
                  + count
                  + " failures so far); further failures are not logged for a minute",
              e);
        }
      }
    }
  }
}
