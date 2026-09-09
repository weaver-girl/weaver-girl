package com.github.cc11001100.weavergirl.core.status;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Runtime status of the weaver-girl agent. Tracks transformation counts, error counts, active
 * plugins, and custom metrics.
 *
 * <p>All methods are thread-safe. This class is a singleton — obtain via {@link #getInstance()}.
 */
public class AgentStatus {

  private static final AgentStatus INSTANCE = new AgentStatus();

  private final AtomicLong transformationCount = new AtomicLong(0);
  private final AtomicLong transformationErrorCount = new AtomicLong(0);
  private final AtomicLong interceptorInvocationCount = new AtomicLong(0);
  private final AtomicLong interceptorErrorCount = new AtomicLong(0);
  private final ConcurrentHashMap<String, String> customMetrics = new ConcurrentHashMap<>();
  private final CopyOnWriteArrayList<String> transformedClasses = new CopyOnWriteArrayList<>();
  private final ConcurrentHashMap<String, InterceptorMetrics> interceptorMetrics =
      new ConcurrentHashMap<>();
  private final ConcurrentHashMap<String, PluginStatus> pluginStatuses = new ConcurrentHashMap<>();

  private volatile long startTime = System.currentTimeMillis();
  private volatile int activePluginCount = 0;
  private volatile int registeredInterceptorCount = 0;

  private AgentStatus() {}

  public static AgentStatus getInstance() {
    return INSTANCE;
  }

  // --- Counters ---

  public long incrementTransformationCount() {
    return transformationCount.incrementAndGet();
  }

  public long incrementTransformationErrorCount() {
    return transformationErrorCount.incrementAndGet();
  }

  public long incrementInterceptorInvocationCount() {
    return interceptorInvocationCount.incrementAndGet();
  }

  public long incrementInterceptorErrorCount() {
    return interceptorErrorCount.incrementAndGet();
  }

  // --- Setters ---

  public void setActivePluginCount(int count) {
    this.activePluginCount = count;
  }

  public void setRegisteredInterceptorCount(int count) {
    this.registeredInterceptorCount = count;
  }

  public void putCustomMetric(String key, String value) {
    customMetrics.put(key, value);
  }

  public void addTransformedClass(String className) {
    if (transformedClasses.size() < 10000) { // prevent unbounded growth
      transformedClasses.add(className);
    }
  }

  public List<String> getTransformedClasses() {
    return Collections.unmodifiableList(new ArrayList<>(transformedClasses));
  }

  // --- Getters ---

  public long getTransformationCount() {
    return transformationCount.get();
  }

  public long getTransformationErrorCount() {
    return transformationErrorCount.get();
  }

  public long getInterceptorInvocationCount() {
    return interceptorInvocationCount.get();
  }

  public long getInterceptorErrorCount() {
    return interceptorErrorCount.get();
  }

  public int getActivePluginCount() {
    return activePluginCount;
  }

  public int getRegisteredInterceptorCount() {
    return registeredInterceptorCount;
  }

  public Map<String, String> getCustomMetrics() {
    return Collections.unmodifiableMap(customMetrics);
  }

  public long getUptimeSeconds() {
    return (System.currentTimeMillis() - startTime) / 1000;
  }

  // --- Per-interceptor metrics ---

  /**
   * Record an interceptor invocation (success or failure) and its duration.
   *
   * @param name the interceptor definition name (the hook point)
   * @param success whether the interceptor callback completed without throwing
   * @param durationNanos wall-clock time spent inside the interceptor callback
   */
  public void recordInterceptorInvocation(String name, boolean success, long durationNanos) {
    interceptorMetrics
        .computeIfAbsent(name, k -> new InterceptorMetrics())
        .record(success, durationNanos);
  }

  /** Get per-interceptor metrics. */
  public Map<String, InterceptorMetrics> getInterceptorMetrics() {
    return Collections.unmodifiableMap(interceptorMetrics);
  }

  /**
   * Derive a lightweight 0.0-1.0 pressure factor from current invocation/error/slow-call metrics.
   *
   * <p>This is read-only and intended for off-path consumers such as {@link
   * com.github.cc11001100.weavergirl.core.sampling.SamplingMonitor}. It does not mutate any state.
   */
  public double getPressureFactor() {
    long invocations = interceptorInvocationCount.get();
    if (invocations == 0) {
      return 0.0;
    }
    long errors = interceptorErrorCount.get();
    long slowCalls = 0L;
    for (InterceptorMetrics metrics : interceptorMetrics.values()) {
      slowCalls += metrics.getSlowCalls();
    }
    double errorRatio = (double) errors / (double) invocations;
    double slowRatio = (double) slowCalls / (double) invocations;
    if (errorRatio > 0.1 || slowRatio > 0.2) {
      return 0.9;
    }
    return 0.0;
  }

  // --- Plugin status tracking ---

  /** Record plugin load result. */
  public void recordPluginStatus(String name, boolean loaded, String error) {
    pluginStatuses.put(name, new PluginStatus(loaded, error));
  }

  /** Get plugin statuses. */
  public Map<String, PluginStatus> getPluginStatuses() {
    return Collections.unmodifiableMap(pluginStatuses);
  }

  /** Generate a human-readable status report. */
  public String getReport() {
    StringBuilder sb = new StringBuilder();
    sb.append("=== Weaver-Girl Agent Status ===\n");
    sb.append("Uptime: ").append(getUptimeSeconds()).append("s\n");
    sb.append("Active Plugins: ").append(activePluginCount).append("\n");
    sb.append("Registered Interceptors: ").append(registeredInterceptorCount).append("\n");
    sb.append("Transformations: ")
        .append(transformationCount.get())
        .append(" (errors: ")
        .append(transformationErrorCount.get())
        .append(")\n");
    sb.append("Interceptor Invocations: ")
        .append(interceptorInvocationCount.get())
        .append(" (errors: ")
        .append(interceptorErrorCount.get())
        .append(")\n");
    if (!customMetrics.isEmpty()) {
      sb.append("Custom Metrics:\n");
      customMetrics.forEach(
          (k, v) -> sb.append("  ").append(k).append(": ").append(v).append("\n"));
    }
    if (!pluginStatuses.isEmpty()) {
      sb.append("Plugins:\n");
      pluginStatuses.forEach(
          (name, status) -> {
            sb.append("  ").append(name).append(": ");
            if (status.loaded) {
              sb.append("LOADED");
            } else {
              sb.append("FAILED (").append(status.error).append(")");
            }
            sb.append("\n");
          });
    }
    if (!interceptorMetrics.isEmpty()) {
      sb.append("Interceptor Metrics:\n");
      interceptorMetrics.forEach(
          (name, m) ->
              sb.append("  ")
                  .append(name)
                  .append(": invocations=")
                  .append(m.getInvocations())
                  .append(", errors=")
                  .append(m.getErrors())
                  .append(", avg=")
                  .append(String.format("%.3f", m.getAverageNanos() / 1_000_000.0))
                  .append("ms")
                  .append(", max=")
                  .append(String.format("%.3f", m.getMaxNanos() / 1_000_000.0))
                  .append("ms")
                  .append(", slow=")
                  .append(m.getSlowCalls())
                  .append("\n"));
    }
    sb.append("================================");
    return sb.toString();
  }

  /** Reset all counters. Useful for testing. */
  public void reset() {
    transformationCount.set(0);
    transformationErrorCount.set(0);
    interceptorInvocationCount.set(0);
    interceptorErrorCount.set(0);
    activePluginCount = 0;
    registeredInterceptorCount = 0;
    customMetrics.clear();
    transformedClasses.clear();
    interceptorMetrics.clear();
    pluginStatuses.clear();
    startTime = System.currentTimeMillis();
  }

  // --- Inner classes ---

  /**
   * Per-interceptor invocation metrics, including latency distribution.
   *
   * <p>All fields are lock-free ({@link AtomicLong}) because instances are written from the
   * instrumented-method hot path inside inlined {@code @Advice}. The latency distribution is
   * approximated by a fixed cumulative-bucket histogram (no per-sample retention), so recording is
   * O(1) and memory-bounded; percentiles are interpolated across buckets only on read (infrequent).
   */
  public static class InterceptorMetrics {

    /** Cumulative upper bounds (nanoseconds) for each latency bucket. */
    static final long[] BUCKET_BOUNDS_NS = {
      1_000L,
      10_000L,
      100_000L,
      1_000_000L,
      10_000_000L,
      100_000_000L,
      1_000_000_000L,
      Long.MAX_VALUE
    };

    /**
     * A single interceptor callback slower than this counts as a "slow call". Configurable via
     * {@code -Dweavergirl.hook.slowThresholdNanos}; default 50ms.
     */
    static final long SLOW_THRESHOLD_NS =
        Long.getLong("weavergirl.hook.slowThresholdNanos", 50_000_000L);

    private final AtomicLong invocations = new AtomicLong(0);
    private final AtomicLong errors = new AtomicLong(0);
    private final AtomicLong totalNanos = new AtomicLong(0);
    private final AtomicLong maxNanos = new AtomicLong(0);
    private final AtomicLong slowCalls = new AtomicLong(0);
    private final AtomicLong[] latencyBuckets;

    InterceptorMetrics() {
      latencyBuckets = new AtomicLong[BUCKET_BOUNDS_NS.length];
      for (int i = 0; i < latencyBuckets.length; i++) {
        latencyBuckets[i] = new AtomicLong(0);
      }
    }

    void record(boolean success, long durationNanos) {
      invocations.incrementAndGet();
      if (!success) {
        errors.incrementAndGet();
      }
      if (durationNanos < 0) {
        durationNanos = 0;
      }
      totalNanos.addAndGet(durationNanos);
      updateMax(durationNanos);
      if (durationNanos >= SLOW_THRESHOLD_NS) {
        slowCalls.incrementAndGet();
      }
      latencyBuckets[bucketIndex(durationNanos)].incrementAndGet();
    }

    private void updateMax(long candidate) {
      long current;
      do {
        current = maxNanos.get();
        if (candidate <= current) {
          return;
        }
      } while (!maxNanos.compareAndSet(current, candidate));
    }

    static int bucketIndex(long nanos) {
      for (int i = 0; i < BUCKET_BOUNDS_NS.length; i++) {
        if (nanos <= BUCKET_BOUNDS_NS[i]) {
          return i;
        }
      }
      return BUCKET_BOUNDS_NS.length - 1;
    }

    public long getInvocations() {
      return invocations.get();
    }

    public long getErrors() {
      return errors.get();
    }

    public long getTotalNanos() {
      return totalNanos.get();
    }

    public long getMaxNanos() {
      return maxNanos.get();
    }

    public long getSlowCalls() {
      return slowCalls.get();
    }

    /** Average duration in nanoseconds (0 if no invocations). */
    public double getAverageNanos() {
      long n = invocations.get();
      return n > 0 ? (double) totalNanos.get() / n : 0.0;
    }

    /**
     * Approximate percentile (0-100) of the latency distribution, interpolated across the
     * cumulative histogram buckets. Returns nanoseconds. Read-only; call infrequently (e.g. from
     * the /stats endpoint), not on the hot path.
     */
    public double estimatePercentile(double percentile) {
      long total = invocations.get();
      if (total == 0) {
        return 0.0;
      }
      double target = (percentile / 100.0) * total;
      long cumulative = 0;
      long lowerBound = 0;
      for (int i = 0; i < BUCKET_BOUNDS_NS.length; i++) {
        long inBucket = latencyBuckets[i].get();
        long prevCumulative = cumulative;
        cumulative += inBucket;
        if (cumulative >= target) {
          long upper = BUCKET_BOUNDS_NS[i];
          if (inBucket == 0) {
            return upper == Long.MAX_VALUE ? lowerBound : upper;
          }
          if (upper == Long.MAX_VALUE) {
            // Open-ended final bucket (>1s): best estimate is its lower bound.
            return lowerBound;
          }
          double frac = (target - prevCumulative) / inBucket;
          return lowerBound + frac * (upper - lowerBound);
        }
        lowerBound = BUCKET_BOUNDS_NS[i];
      }
      return maxNanos.get();
    }
  }

  /** Plugin load status. */
  public static class PluginStatus {
    private final boolean loaded;
    private final String error;

    PluginStatus(boolean loaded, String error) {
      this.loaded = loaded;
      this.error = error;
    }

    public boolean isLoaded() {
      return loaded;
    }

    public String getError() {
      return error;
    }
  }
}
