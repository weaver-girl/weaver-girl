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
 * <p>Surefire runs every core test in one JVM, and the engine keeps its operating state in static
 * singletons. A test that applies a real config, trips a hook, retunes a threshold or fails to shut
 * a bootstrapped agent down leaves that state in the fork, and a later weaving test then fails as
 * "before() should have been called: expected true but was false" — only in a full-suite run, and
 * on a different set of classes each time because the order is not stable.
 *
 * <p>What has to be reset, and why each one matters:
 *
 * <ul>
 *   <li>emergency mode and the interception kill-switch — {@code weavergirl.emergency.disable}
 *       switches interception off process-wide;
 *   <li>the per-call performance switches — {@code returnSnapshot=false} leaves later hooks with no
 *       return value to read;
 *   <li>the circuit breaker (states and threshold) — states are keyed by hook name, and a retuned
 *       threshold trips hooks the test never failed;
 *   <li>the sampling controller — a raised rate silently drops {@code before()} callbacks;
 *   <li>a live {@link SamplingMonitor} — a leftover thread re-raises the rate every second, so it
 *       undoes the reset above;
 *   <li>{@link AgentStatus}'s counters, which the monitor reads.
 * </ul>
 *
 * <p>{@link GracefulDegradationManager#resetForTest()} alone covers only the first item's flag.
 */
public final class ProcessWideState {

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
