package com.github.cc11001100.weavergirl.api.status;

/**
 * Read-only counters for one interceptor definition (one woven hook point).
 *
 * <p>The engine records an invocation for every advice callback it runs, whether it succeeded or
 * threw, together with how long the callback itself took. Modules that only depend on the API — the
 * Prometheus bridge, an exporter, a management endpoint — read those counters through {@link
 * AgentStatusView#getInterceptorMetrics()} instead of depending on the engine's status type.
 *
 * <p>All accessors are lock-free snapshots and safe to call concurrently. Read them on scrape or
 * request boundaries, not on the intercepted hot path.
 *
 * @since 1.0.0
 */
public interface InterceptorMetricsView {

  /** Cumulative number of times this interceptor was invoked. */
  long getInvocations();

  /** Cumulative number of invocations whose callback threw. */
  long getErrors();

  /** Cumulative time spent inside the callback, in nanoseconds. */
  long getTotalNanos();

  /** The longest single callback invocation seen, in nanoseconds. */
  long getMaxNanos();

  /** Invocations at or above the agent's slow-hook threshold. */
  long getSlowCalls();

  /** Mean callback duration in nanoseconds, or 0 when never invoked. */
  double getAverageNanos();

  /**
   * Approximate percentile of the callback latency distribution, in nanoseconds.
   *
   * @param percentile a value in {@code (0, 100]}
   * @return the estimated duration in nanoseconds, 0 when never invoked
   */
  double estimatePercentile(double percentile);

  /**
   * Upper bounds of the latency histogram buckets in nanoseconds, ascending, the last being {@link
   * Long#MAX_VALUE} for the open-ended bucket. Empty when the implementation keeps no histogram.
   */
  default long[] getLatencyBucketBoundsNanos() {
    return new long[0];
  }

  /**
   * Non-cumulative count per latency bucket, aligned with {@link #getLatencyBucketBoundsNanos()}.
   * Empty when the implementation keeps no histogram.
   */
  default long[] getLatencyBucketCounts() {
    return new long[0];
  }
}
