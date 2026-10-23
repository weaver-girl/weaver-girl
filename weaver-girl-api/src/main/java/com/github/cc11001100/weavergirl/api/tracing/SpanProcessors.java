package com.github.cc11001100.weavergirl.api.tracing;

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
 * Registry and execution chain for {@link SpanProcessor}s.
 *
 * <p>{@link Tracer#endSpan} runs the chain before notifying completion listeners, so a processor
 * governs every downstream consumer. When no processor is registered the only cost is an empty
 * list check.
 *
 * <p>Registration order is execution order. Each processor receives the output of the previous one.
 * A processor that returns {@code null} drops the span and ends the chain. A processor that throws
 * is skipped: the span continues unchanged and the failure is counted.
 *
 * <p>Register through {@code PluginContext.registerSpanProcessor} so the processor is withdrawn
 * with the plugin on unload.
 *
 * @since 1.0.0
 */
public final class SpanProcessors {

  private static final Logger log = Logger.getLogger(SpanProcessors.class.getName());

  /** How long to stay quiet after one processor throws, so a broken one cannot flood the log. */
  private static final long FAILURE_LOG_INTERVAL_MS = 60_000L;

  private static final List<Holder> holders = new CopyOnWriteArrayList<>();

  private SpanProcessors() {}

  /**
   * Register a processor. Registering the same instance twice is a no-op.
   *
   * @param processor the processor to add to the end of the chain
   */
  public static void register(SpanProcessor processor) {
    if (processor == null) {
      return;
    }
    synchronized (SpanProcessors.class) {
      for (Holder holder : holders) {
        if (holder.processor == processor) {
          return;
        }
      }
      holders.add(new Holder(processor));
    }
  }

  /**
   * Remove a processor.
   *
   * @param processor the processor to remove
   */
  public static void unregister(SpanProcessor processor) {
    if (processor == null) {
      return;
    }
    synchronized (SpanProcessors.class) {
      for (Holder holder : holders) {
        if (holder.processor == processor) {
          holders.remove(holder);
          return;
        }
      }
    }
  }

  /** The registered processors, in execution order. */
  public static List<SpanProcessor> getProcessors() {
    List<SpanProcessor> processors = new ArrayList<>(holders.size());
    for (Holder holder : holders) {
      processors.add(holder.processor);
    }
    return Collections.unmodifiableList(processors);
  }

  /**
   * Processed / dropped / failed counts per processor name, for metrics bridges.
   *
   * <p>Processors that report the same name are summed.
   *
   * @return an immutable snapshot, empty when nothing is registered
   */
  public static Map<String, SpanProcessorStats> getStats() {
    Map<String, long[]> merged = new TreeMap<>();
    for (Holder holder : holders) {
      String name = holder.name();
      long[] counts = merged.get(name);
      if (counts == null) {
        counts = new long[3];
        merged.put(name, counts);
      }
      counts[0] += holder.processed.get();
      counts[1] += holder.dropped.get();
      counts[2] += holder.failures.get();
    }
    Map<String, SpanProcessorStats> stats = new LinkedHashMap<>();
    for (Map.Entry<String, long[]> entry : merged.entrySet()) {
      long[] counts = entry.getValue();
      stats.put(
          entry.getKey(),
          new SpanProcessorStats(entry.getKey(), counts[0], counts[1], counts[2]));
    }
    return Collections.unmodifiableMap(stats);
  }

  /** Remove every processor. Tests use this so one test cannot see another's chain. */
  public static void clear() {
    synchronized (SpanProcessors.class) {
      holders.clear();
    }
  }

  /**
   * Run the chain over a finished span.
   *
   * @param span the finished span, or null
   * @return the span to publish, or null when a processor dropped it
   */
  public static SpanContext apply(SpanContext span) {
    if (span == null || holders.isEmpty()) {
      return span;
    }
    SpanContext current = span;
    for (Holder holder : holders) {
      SpanContext next = holder.apply(current);
      if (next == null) {
        return null;
      }
      current = next;
    }
    return current;
  }

  /** A processor plus its counters and rate-limited failure logging state. */
  private static final class Holder {
    private final SpanProcessor processor;
    private final AtomicLong processed = new AtomicLong(0);
    private final AtomicLong dropped = new AtomicLong(0);
    private final AtomicLong failures = new AtomicLong(0);
    private final AtomicLong lastWarningAt = new AtomicLong(0);

    Holder(SpanProcessor processor) {
      this.processor = processor;
    }

    String name() {
      String name = processor.name();
      return name == null || name.trim().isEmpty() ? "unknown" : name.trim();
    }

    SpanContext apply(SpanContext span) {
      SpanContext result;
      try {
        result = processor.process(span);
      } catch (RuntimeException e) {
        failures.incrementAndGet();
        warn(e);
        return span;
      }
      if (result == null) {
        dropped.incrementAndGet();
      } else {
        processed.incrementAndGet();
      }
      return result;
    }

    private void warn(RuntimeException e) {
      long now = System.currentTimeMillis();
      long last = lastWarningAt.get();
      if (now - last >= FAILURE_LOG_INTERVAL_MS && lastWarningAt.compareAndSet(last, now)) {
        log.log(
            Level.WARNING,
            "SpanProcessor '"
                + name()
                + "' threw ("
                + failures.get()
                + " failures so far); the span was published unchanged and further failures"
                + " are not logged for a minute",
            e);
      }
    }
  }
}
