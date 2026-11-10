package com.github.cc11001100.weavergirl.api.observability;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.LongAdder;

/**
 * Per-extension call counters and timings, for every extension point the agent invokes at runtime.
 *
 * <p>Extension failures are isolated by design: a {@link SpanSink} that throws is rate-limit logged
 * and the other sinks still run, a {@link com.github.cc11001100.weavergirl.api.tracing.SpanProcessor}
 * that throws is skipped, a {@link TagEnricher} that throws leaves the span unchanged. That
 * isolation is right for the application, but it also means a permanently broken extension is
 * invisible unless its failure is counted. This tracker is where it is counted.
 *
 * <p>Built-in extension points record here under a stable {@code kind}:
 *
 * <ul>
 *   <li>{@code span-processor} — {@link com.github.cc11001100.weavergirl.api.tracing.SpanProcessors}
 *   <li>{@code span-sink} — {@link SpanSinks}
 *   <li>{@code metric-source} — a {@link MetricSource} collected during a scrape
 *   <li>{@code health-check} — a {@link HealthCheck}, including {@link HealthCheckExtension} adapters
 *   <li>{@code tag-enricher} — {@link TagEnrichers}
 *   <li>{@code metric-processor} — {@link MetricProcessors}
 *   <li>{@code event-transformer} —
 *       {@link com.github.cc11001100.weavergirl.api.event.EventTransformers}
 * </ul>
 *
 * <p>Recording is a couple of concurrent map lookups and adder increments, so it is safe to call on
 * the span-end and scrape paths. Names are trimmed; a null or blank name is recorded as {@code
 * "unknown"} so one anonymous extension cannot hide behind another. Nothing is recorded before an
 * extension has been called, so a scrape stays empty on a process that has no extensions.
 *
 * @see ExtensionStats
 * @since 1.2.0
 */
public final class ExtensionPerformanceTracker {

  /** Extension point: a {@code SpanProcessor} in the span governance chain. */
  public static final String KIND_SPAN_PROCESSOR = "span-processor";

  /** Extension point: a {@code SpanSink} receiving completed spans. */
  public static final String KIND_SPAN_SINK = "span-sink";

  /** Extension point: a {@code MetricSource} collected during a scrape. */
  public static final String KIND_METRIC_SOURCE = "metric-source";

  /** Extension point: a health check evaluated by a probe or a scrape. */
  public static final String KIND_HEALTH_CHECK = "health-check";

  /** Extension point: a {@code TagEnricher} enriching a finished span. */
  public static final String KIND_TAG_ENRICHER = "tag-enricher";

  /** Extension point: a {@code MetricProcessor} governing one metric sample. */
  public static final String KIND_METRIC_PROCESSOR = "metric-processor";

  /** Extension point: an {@code InterceptorEventTransformer} governing one interceptor event. */
  public static final String KIND_EVENT_TRANSFORMER = "event-transformer";

  private static final ConcurrentMap<String, ConcurrentMap<String, Counter>> byKind =
      new ConcurrentHashMap<>();

  private ExtensionPerformanceTracker() {}

  /**
   * Record one extension call.
   *
   * @param kind the extension point, one of the {@code KIND_*} constants or a custom label
   * @param name the extension's reported name; null or blank is recorded as {@code "unknown"}
   * @param durationNanos how long the call took
   * @param error whether the call threw
   */
  public static void record(String kind, String name, long durationNanos, boolean error) {
    try {
      String normalizedKind = normalize(kind, "unknown");
      String normalizedName = normalize(name, "unknown");
      ConcurrentMap<String, Counter> counters = byKind.get(normalizedKind);
      if (counters == null) {
        ConcurrentMap<String, Counter> created = new ConcurrentHashMap<>();
        ConcurrentMap<String, Counter> existing = byKind.putIfAbsent(normalizedKind, created);
        counters = existing != null ? existing : created;
      }
      Counter counter = counters.get(normalizedName);
      if (counter == null) {
        Counter created = new Counter(normalizedKind, normalizedName);
        Counter existing = counters.putIfAbsent(normalizedName, created);
        counter = existing != null ? existing : created;
      }
      counter.record(durationNanos, error);
    } catch (Throwable ignored) {
      // Metrics must never break the pipeline they instrument (out of memory, class init failure).
    }
  }

  /**
   * Snapshot for one extension instance.
   *
   * @param kind the extension point
   * @param name the extension name
   * @return the stats, or null when that extension has never been called
   */
  public static ExtensionStats get(String kind, String name) {
    ConcurrentMap<String, Counter> counters = byKind.get(normalize(kind, "unknown"));
    if (counters == null) {
      return null;
    }
    Counter counter = counters.get(normalize(name, "unknown"));
    return counter != null ? counter.snapshot() : null;
  }

  /**
   * Every extension's stats, sorted by kind then name so a scrape is stable.
   *
   * @return an immutable list, empty when nothing has been recorded
   */
  public static List<ExtensionStats> getAll() {
    List<ExtensionStats> all = new ArrayList<>();
    for (ConcurrentMap<String, Counter> counters : byKind.values()) {
      for (Counter counter : counters.values()) {
        all.add(counter.snapshot());
      }
    }
    Collections.sort(
        all,
        new Comparator<ExtensionStats>() {
          @Override
          public int compare(ExtensionStats a, ExtensionStats b) {
            int byKindName = a.getKind().compareTo(b.getKind());
            return byKindName != 0 ? byKindName : a.getName().compareTo(b.getName());
          }
        });
    return Collections.unmodifiableList(all);
  }

  /**
   * Stats for one extension point, keyed by extension name, sorted by name.
   *
   * @param kind the extension point
   * @return an immutable map, empty when nothing has been recorded for that kind
   */
  public static Map<String, ExtensionStats> getByKind(String kind) {
    ConcurrentMap<String, Counter> counters = byKind.get(normalize(kind, "unknown"));
    if (counters == null || counters.isEmpty()) {
      return Collections.emptyMap();
    }
    List<String> names = new ArrayList<>(counters.keySet());
    Collections.sort(names);
    Map<String, ExtensionStats> stats = new LinkedHashMap<>();
    for (String name : names) {
      stats.put(name, counters.get(name).snapshot());
    }
    return Collections.unmodifiableMap(stats);
  }

  /**
   * Drop the counters for one extension, so an unloaded extension does not keep reporting the
   * numbers it accumulated before it went away.
   *
   * @param kind the extension point
   * @param name the extension name
   */
  public static void remove(String kind, String name) {
    ConcurrentMap<String, Counter> counters = byKind.get(normalize(kind, "unknown"));
    if (counters != null) {
      counters.remove(normalize(name, "unknown"));
    }
  }

  /** Forget every counter. Tests use this so one test cannot see another's extensions. */
  public static void clear() {
    byKind.clear();
  }

  private static String normalize(String value, String fallback) {
    if (value == null) {
      return fallback;
    }
    String trimmed = value.trim();
    return trimmed.isEmpty() ? fallback : trimmed;
  }

  /** Thread-safe counters for one extension instance. */
  private static final class Counter {
    private final String kind;
    private final String name;
    private final LongAdder invocations = new LongAdder();
    private final LongAdder errors = new LongAdder();
    private final LongAdder totalDurationNanos = new LongAdder();
    private final AtomicLong lastInvocationAt = new AtomicLong(0);
    private final AtomicLong lastErrorAt = new AtomicLong(0);

    Counter(String kind, String name) {
      this.kind = kind;
      this.name = name;
    }

    void record(long durationNanos, boolean error) {
      invocations.increment();
      if (durationNanos > 0) {
        totalDurationNanos.add(durationNanos);
      }
      lastInvocationAt.set(System.currentTimeMillis());
      if (error) {
        errors.increment();
        lastErrorAt.set(System.currentTimeMillis());
      }
    }

    ExtensionStats snapshot() {
      long calls = invocations.sum();
      long failures = errors.sum();
      double totalMs = totalDurationNanos.sum() / 1_000_000.0;
      double avgMs = calls > 0 ? totalMs / calls : 0.0;
      return new ExtensionStats(
          kind, name, calls, failures, totalMs, avgMs, lastInvocationAt.get(), lastErrorAt.get());
    }
  }
}
