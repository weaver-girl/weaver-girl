package com.github.cc11001100.weavergirl.core.testsupport;

import com.github.cc11001100.weavergirl.api.config.PerformanceSwitches;
import com.github.cc11001100.weavergirl.core.InterceptorHolder;
import com.github.cc11001100.weavergirl.core.graceful.GracefulDegradationManager;
import com.github.cc11001100.weavergirl.core.sampling.SamplingController;
import com.github.cc11001100.weavergirl.core.sampling.SamplingMonitor;
import com.github.cc11001100.weavergirl.core.status.AgentStatus;

/**
 * Restores the process-wide singletons a test may have changed.
 *
 * <p>Surefire runs every core test in one JVM. A test that applies a real config (or drives
 * {@link GracefulDegradationManager}) leaves three pieces of global state behind — emergency mode,
 * the interception kill-switch it switches off, and the sampling cap it raises — and the next
 * weaving test in the fork then sees "before() should have been called: expected true but was
 * false". Calling this from a test's {@code finally} keeps one test's config from becoming the next
 * test's environment.
 *
 * <p>{@link GracefulDegradationManager#resetForTest()} alone is not enough: it clears the emergency
 * flag but leaves the kill-switch and the sampling rate as they were.
 */
public final class ProcessWideState {

  /** {@code InterceptorCircuitBreaker()}'s no-arg defaults, which the engine starts with. */
  private static final int DEFAULT_FAILURE_THRESHOLD = 5;

  private static final long DEFAULT_COOLDOWN_MILLIS = 60_000L;

  private ProcessWideState() {}

  /**
   * Put emergency mode, the kill-switch, the sampling controller and the per-call performance
   * switches back to their defaults.
   *
   * <p>The switches belong here too: a test that applies {@code returnSnapshot=false} through a
   * config map leaves every later weaving test without a return snapshot, so a hook that reads the
   * called method's return value (durable-registry snapshot, annotation advice) sees {@code null}.
   */
  public static void restore() {
    GracefulDegradationManager.resetForTest();
    InterceptorHolder.setInterceptionEnabled(true, "test-cleanup");
    PerformanceSwitches.resetForTest();
    // The breaker is process-wide too: a test that retunes the threshold (HookTelemetryTest uses 2
    // and then 1) or trips a hook leaves every later hook tripping after one failure.
    InterceptorHolder.resetCircuitBreakerForTest();
    SamplingController controller = SamplingController.getInstance();
    controller.setSamplingRate(1);
    controller.setMaxRate(100);
    controller.setThresholdInvocationsPerSecond(10000);
    // A leftover sampling monitor is the worst of these: it is a live thread that re-raises the
    // sampling rate every second from stale AgentStatus counts, so it re-breaks tests after this
    // method has already put the rate back. The engine only runs monitors a test itself started,
    // so stop them here and clear the counters they read.
    SamplingMonitor.stopAllForTest();
    AgentStatus.getInstance().reset();
  }
}
