package com.github.cc11001100.weavergirl.api.event;

import com.github.cc11001100.weavergirl.api.observability.ExtensionPerformanceTracker;
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
 * Registry and execution chain for {@link InterceptorEventTransformer}s.
 *
 * <p>{@link InterceptorEventPublisher#publish} runs this chain over every event right after the
 * active span is stamped and before listeners, exporters and the event history see it. A dropped
 * event reaches none of them. With no transformer registered the only cost is an empty list check.
 *
 * <p>Registration order is execution order, resolved by {@link
 * InterceptorEventTransformer#priority()} ascending; transformers with the same priority keep
 * their registration order. Each receives the output of the previous one. A transformer that
 * returns {@code null} drops the event and ends the chain. A transformer that throws is skipped:
 * the event continues unchanged and the failure is counted.
 *
 * <p>Register through {@link com.github.cc11001100.weavergirl.api.plugin.PluginContext#registerEventTransformer}
 * so the transformer is withdrawn with the plugin on unload.
 *
 * @see InterceptorEventTransformer
 * @see InterceptorEvent
 * @since 1.3.0
 */
public final class EventTransformers {

  private static final Logger log = Logger.getLogger(EventTransformers.class.getName());

  /** How long to stay quiet after one transformer throws, so a broken one cannot flood the log. */
  private static final long FAILURE_LOG_INTERVAL_MS = 60_000L;

  private static final List<Holder> holders = new CopyOnWriteArrayList<>();

  private EventTransformers() {}

  /**
   * Register a transformer. Registering the same instance twice is a no-op.
   *
   * @param transformer the transformer to add to the chain
   */
  public static void register(InterceptorEventTransformer transformer) {
    if (transformer == null) {
      return;
    }
    synchronized (EventTransformers.class) {
      for (Holder holder : holders) {
        if (holder.transformer == transformer) {
          return;
        }
      }
      holders.add(new Holder(transformer));
      holders.sort((a, b) -> Integer.compare(a.priority, b.priority));
    }
  }

  /**
   * Remove a transformer.
   *
   * @param transformer the transformer to remove
   */
  public static void unregister(InterceptorEventTransformer transformer) {
    if (transformer == null) {
      return;
    }
    synchronized (EventTransformers.class) {
      for (Holder holder : holders) {
        if (holder.transformer == transformer) {
          holders.remove(holder);
          return;
        }
      }
    }
  }

  /** The registered transformers, in execution order. */
  public static List<InterceptorEventTransformer> getTransformers() {
    List<InterceptorEventTransformer> transformers = new ArrayList<>(holders.size());
    for (Holder holder : holders) {
      transformers.add(holder.transformer);
    }
    return Collections.unmodifiableList(transformers);
  }

  /** Processed / dropped / failed counts per transformer name, for metrics bridges. */
  public static Map<String, EventTransformerStats> getStats() {
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
    Map<String, EventTransformerStats> stats = new LinkedHashMap<>();
    for (Map.Entry<String, long[]> entry : merged.entrySet()) {
      long[] counts = entry.getValue();
      stats.put(
          entry.getKey(),
          new EventTransformerStats(entry.getKey(), counts[0], counts[1], counts[2]));
    }
    return Collections.unmodifiableMap(stats);
  }

  /**
   * Run the chain over one event.
   *
   * @param event the event as published, or null
   * @return the event to deliver, or null when a transformer dropped it (or the input was null)
   */
  public static InterceptorEvent transform(InterceptorEvent event) {
    if (event == null || holders.isEmpty()) {
      return event;
    }
    InterceptorEvent current = event;
    for (Holder holder : holders) {
      InterceptorEvent next = holder.apply(current);
      if (next == null) {
        return null;
      }
      current = next;
    }
    return current;
  }

  /** Remove every transformer. Tests use this so one test cannot see another's chain. */
  public static void clear() {
    synchronized (EventTransformers.class) {
      holders.clear();
    }
  }

  /** A transformer plus its counters and rate-limited failure logging state. */
  private static final class Holder {
    private final InterceptorEventTransformer transformer;
    private final int priority;
    private final String name;
    private final AtomicLong processed = new AtomicLong(0);
    private final AtomicLong dropped = new AtomicLong(0);
    private final AtomicLong failures = new AtomicLong(0);
    private final AtomicLong lastWarningAt = new AtomicLong(0);

    Holder(InterceptorEventTransformer transformer) {
      this.transformer = transformer;
      this.priority = transformer.priority();
      String reported;
      try {
        reported = transformer.name();
      } catch (RuntimeException e) {
        reported = null;
      }
      this.name = reported == null || reported.trim().isEmpty() ? "unknown" : reported.trim();
    }

    InterceptorEvent apply(InterceptorEvent event) {
      long start = System.nanoTime();
      InterceptorEvent result;
      try {
        result = transformer.transform(event);
      } catch (RuntimeException e) {
        failures.incrementAndGet();
        ExtensionPerformanceTracker.record(
            ExtensionPerformanceTracker.KIND_EVENT_TRANSFORMER,
            name,
            System.nanoTime() - start,
            true);
        warn(e);
        return event;
      }
      ExtensionPerformanceTracker.record(
          ExtensionPerformanceTracker.KIND_EVENT_TRANSFORMER,
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
            "InterceptorEventTransformer '"
                + name
                + "' threw ("
                + failures.get()
                + " failures so far); further failures are not logged for a minute",
            e);
      }
    }
  }
}