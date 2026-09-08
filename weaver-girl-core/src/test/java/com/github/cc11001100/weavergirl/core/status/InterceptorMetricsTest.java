// weaver-girl-core/src/test/java/com/github/cc11001100/weavergirl/core/status/InterceptorMetricsTest.java
package com.github.cc11001100.weavergirl.core.status;

import static org.junit.jupiter.api.Assertions.*;

import org.junit.jupiter.api.Test;

/**
 * Unit tests for the lock-free per-hook {@link AgentStatus.InterceptorMetrics}, which is written
 * from the inlined {@code @Advice} hot path. Covers invocation/error accounting, latency
 * totals/average/max, slow-call counting, histogram bucketing, and the interpolated percentile
 * estimate.
 */
class InterceptorMetricsTest {

  @Test
  void recordCountsInvocationsAndErrors() {
    AgentStatus.InterceptorMetrics m = new AgentStatus.InterceptorMetrics();
    m.record(true, 100L);
    m.record(true, 200L);
    m.record(false, 300L);
    assertEquals(3, m.getInvocations());
    assertEquals(1, m.getErrors());
  }

  @Test
  void totalAndAverageNanos() {
    AgentStatus.InterceptorMetrics m = new AgentStatus.InterceptorMetrics();
    m.record(true, 100L);
    m.record(true, 300L);
    assertEquals(400L, m.getTotalNanos());
    assertEquals(200.0, m.getAverageNanos(), 1e-9);
  }

  @Test
  void averageIsZeroWithNoInvocations() {
    AgentStatus.InterceptorMetrics m = new AgentStatus.InterceptorMetrics();
    assertEquals(0.0, m.getAverageNanos(), 0.0);
    assertEquals(0L, m.getInvocations());
  }

  @Test
  void maxNanosTracksLargestValue() {
    AgentStatus.InterceptorMetrics m = new AgentStatus.InterceptorMetrics();
    m.record(true, 500L);
    m.record(true, 5_000L);
    m.record(true, 1_000L);
    assertEquals(5_000L, m.getMaxNanos());
  }

  @Test
  void negativeDurationClampedToZero() {
    AgentStatus.InterceptorMetrics m = new AgentStatus.InterceptorMetrics();
    m.record(true, -9999L);
    assertEquals(1, m.getInvocations());
    assertEquals(0L, m.getTotalNanos());
    assertEquals(0L, m.getMaxNanos());
  }

  @Test
  void slowCallsIncrementAboveThreshold() {
    // Default SLOW_THRESHOLD_NS is 50ms (50_000_000ns). A 1-second call is clearly slow;
    // a 1us call is clearly not. This holds for any threshold <= 1s.
    AgentStatus.InterceptorMetrics m = new AgentStatus.InterceptorMetrics();
    m.record(true, 1_000L); // 1us — not slow
    m.record(true, 1_000_000_000L); // 1s — slow
    m.record(true, 1_000_000_000L); // 1s — slow
    assertEquals(2, m.getSlowCalls());
  }

  @Test
  void bucketIndexRespectsCumulativeBounds() {
    // Boundaries: 1us, 10us, 100us, 1ms, 10ms, 100ms, 1s, MAX
    assertEquals(0, AgentStatus.InterceptorMetrics.bucketIndex(0));
    assertEquals(0, AgentStatus.InterceptorMetrics.bucketIndex(1_000L)); // exactly 1us -> bucket 0
    assertEquals(
        1, AgentStatus.InterceptorMetrics.bucketIndex(1_001L)); // just over 1us -> bucket 1
    assertEquals(
        1, AgentStatus.InterceptorMetrics.bucketIndex(10_000L)); // exactly 10us -> bucket 1
    assertEquals(2, AgentStatus.InterceptorMetrics.bucketIndex(50_000L)); // 50us -> bucket 2
    assertEquals(5, AgentStatus.InterceptorMetrics.bucketIndex(50_000_000L)); // 50ms -> bucket 5
    assertEquals(
        7, AgentStatus.InterceptorMetrics.bucketIndex(5_000_000_000L)); // >1s -> last bucket
  }

  @Test
  void percentileIsZeroWithNoInvocations() {
    AgentStatus.InterceptorMetrics m = new AgentStatus.InterceptorMetrics();
    assertEquals(0.0, m.estimatePercentile(95.0), 0.0);
  }

  @Test
  void percentileInterpolatesWithinBuckets() {
    // 10 identical 500ns samples all fall in bucket 0 (<=1us). p50 target = 5.0;
    // bucket 0 has 10, frac = 5/10 = 0.5 -> 0 + 0.5*(1000-0) = 500ns.
    AgentStatus.InterceptorMetrics m = new AgentStatus.InterceptorMetrics();
    for (int i = 0; i < 10; i++) {
      m.record(true, 500L);
    }
    assertEquals(500.0, m.estimatePercentile(50.0), 1e-6);
    // p100 (target=10) lands at the bucket's upper bound when fully filled.
    assertTrue(m.estimatePercentile(100.0) <= 1_000.0);
  }

  @Test
  void percentileRisesWithSlowerSamples() {
    AgentStatus.InterceptorMetrics low = new AgentStatus.InterceptorMetrics();
    AgentStatus.InterceptorMetrics high = new AgentStatus.InterceptorMetrics();
    for (int i = 0; i < 100; i++) {
      low.record(true, 500L); // all ~1us
      high.record(true, 50_000_000L); // all ~50ms
    }
    // p95 of the slow distribution must exceed p95 of the fast one.
    assertTrue(high.estimatePercentile(95.0) > low.estimatePercentile(95.0));
  }

  @Test
  void recordThroughAgentStatusKeyedByName() {
    AgentStatus status = AgentStatus.getInstance();
    status.reset();
    status.recordInterceptorInvocation("hook-a", true, 1_000L);
    status.recordInterceptorInvocation("hook-a", false, 2_000L);
    status.recordInterceptorInvocation("hook-b", true, 3_000L);

    AgentStatus.InterceptorMetrics a = status.getInterceptorMetrics().get("hook-a");
    assertNotNull(a);
    assertEquals(2, a.getInvocations());
    assertEquals(1, a.getErrors());
    assertEquals(3_000L, a.getTotalNanos());
    assertNotNull(status.getInterceptorMetrics().get("hook-b"));
  }

  @Test
  void getReportIncludesPerHookLatencyLine() {
    AgentStatus status = AgentStatus.getInstance();
    status.reset();
    status.recordInterceptorInvocation("my-hook", true, 1_500_000L); // 1.5ms
    String report = status.getReport();
    assertTrue(report.contains("Interceptor Metrics:"));
    assertTrue(report.contains("my-hook"));
    assertTrue(report.contains("invocations=1"));
    assertTrue(report.contains("slow="));
  }
}
