package com.github.cc11001100.weavergirl.api.observability;

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
 * Registry and execution chain for {@link MetricProcessor}s.
 *
 * <p>A collector that renders {@link MetricSource} output — the Prometheus exporter is the
 * built-in one — runs this chain over every sample it received, so a processor governs everything
 * a scrape exposes from plugin sources. With no processor registered the only cost is an empty
 * list check.
 *
 * <p>Registration order is execution order, resolved by {@link MetricProcessor#priority()}
 * ascending; processors with the same priority keep their registration order. Each receives the
 * output of the previous one. A processor that returns {@code null} drops the sample and ends the
 * chain. A processor that throws is skipped: the sample continues unchanged and the failure is
 * counted.
 *
 * <p>Register through {@link com.github.cc11001100.weavergirl.api.plugin.PluginContext#registerMetricProcessor}
 * so the processor is withdrawn with the plugin on unload.
 *
 * @see MetricProcessor
 * @see MetricPoint
 * @since 1.3.0
 */
public final class MetricProcessors {

  private static final Logger log = Logger.getLogger(MetricProcessors.class.getName());

  /** How long to stay quiet after one processor throws, so a broken one cannot flood the log. */
  private static final long FAILURE_LOG_INTERVAL_MS = 60_000L;

  private static final List<Holder> holders = new CopyOnWriteArrayList<>();

  private MetricProcessors() {}

  /**
   * Register a processor. Registering the same instance twice is a no-op.
   *
   * @param processor the processor to add to the chain
   */
  public static void register(MetricProcessor processor) {
    if (processor == null) {
      return;
    }
    synchronized (MetricProcessors.class) {
      for (Holder holder : holders) {
        if (holder.processor == processor) {
          return;
        }
      }
      holders.add(new Holder(processor));
      holders.sort((a, b) -> Integer.compare(a.priority, b.priority));
    }
  }

  /**
   * Remove a processor.
   *
   * @param processor the processor to remove
   */
  public static void unregister(MetricProcessor processor) {
    if (processor == null) {
      return;
    }
    synchronized (MetricProcessors.class) {
      for (Holder holder : holders) {
        if (holder.processor == processor) {
          holders.remove(holder);
          return;
        }
      }
    }
  }

  /** The registered processors, in execution order. */
  public static List<MetricProcessor> getProcessors() {
    List<MetricProcessor> processors = new ArrayList<>(holders.size());
    for (Holder holder : holders) {
      processors.add(holder.processor);
    }
    return Collections.unmodifiableList(processors);
  }

  /** Whether any processor is registered. Lets a collector skip the chain entirely. */
  public static boolean isEmpty() {
    return holders.isEmpty();
  }

  /** Processed / dropped / failed counts per processor name, for metrics bridges. */
  public static Map<String, MetricProcessorStats> getStats() {
    Map<String, long[]> merged = new TreeMap<>();
    for (Holder holder : holders) {
      String name = holder.name;
      long[] counts = merged.get(name);
      if (counts == null) {
        counts = new long[3];
        merged.put(name, counts);
      }
      counts[0] += holder.processed.get();
      counts[1] += holder.dropped.get();
      counts[2] += holder.failures.get();
    }
    Map<String, MetricProcessorStats> stats = new LinkedHashMap<>();
    for (Map.Entry<String, long[]> entry : merged.entrySet()) {
      long[] counts = entry.getValue();
      stats.put(
          entry.getKey(),
          new MetricProcessorStats(entry.getKey(), counts[0], counts[1], counts[2]));
    }
    return Collections.unmodifiableMap(stats);
  }

  /** Remove every processor. Tests use this so one test cannot see another's chain. */
  public static void clear() {
    synchronized (MetricProcessors.class) {
      holders.clear();
    }
  }

  /**
   * Run the chain over one sample.
   *
   * @param point the sample as the source reported it, or null
   * @return the sample to render, or null when a processor dropped it (or the input was null)
   */
  public static MetricPoint apply(MetricPoint point) {
    if (point == null || holders.isEmpty()) {
      return point;
    }
    MetricPoint current = point;
    for (Holder holder : holders) {
      MetricPoint next = holder.apply(current);
      if (next == null) {
        return null;
      }
      current = next;
    }
    return current;
  }

  /** A processor plus its counters and rate-limited failure logging state. */
  private static final class Holder {
    private final MetricProcessor processor;
    private final int priority;
    private final String name;
    private final AtomicLong processed = new AtomicLong(0);
    private final AtomicLong dropped = new AtomicLong(0);
    private final AtomicLong failures = new AtomicLong(0);
    private final AtomicLong lastWarningAt = new AtomicLong(0);

    Holder(MetricProcessor processor) {
      this.processor = processor;
      this.priority = processor.priority();
      String reported;
      try {
        reported = processor.name();
      } catch (RuntimeException e) {
        reported = null;
      }
      this.name = reported == null || reported.trim().isEmpty() ? "unknown" : reported.trim();
    }

    MetricPoint apply(MetricPoint point) {
      long start = System.nanoTime();
      MetricPoint result;
      try {
        result = processor.process(point);
      } catch (RuntimeException e) {
        failures.incrementAndGet();
        ExtensionPerformanceTracker.record(
            ExtensionPerformanceTracker.KIND_METRIC_PROCESSOR,
            name,
            System.nanoTime() - start,
            true);
        warn(e);
        return point;
      }
      ExtensionPerformanceTracker.record(
          ExtensionPerformanceTracker.KIND_METRIC_PROCESSOR,
          name,
          System.nanoTime() - start,
          false);
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
            "MetricProcessor '"
                + name
                + "' threw ("
                + failures.get()
                + " failures so far); further failures are not logged for a minute",
            e);
      }
    }
  }
}