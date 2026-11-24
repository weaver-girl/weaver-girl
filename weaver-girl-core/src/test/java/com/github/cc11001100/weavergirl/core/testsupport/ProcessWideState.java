package com.github.cc11001100.weavergirl.core.testsupport;

import com.github.cc11001100.weavergirl.core.InterceptorHolder;
import com.github.cc11001100.weavergirl.core.graceful.GracefulDegradationManager;
import com.github.cc11001100.weavergirl.core.sampling.SamplingController;

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

  private ProcessWideState() {}

  /** Put emergency mode, the kill-switch and the sampling controller back to their defaults. */
  public static void restore() {
    GracefulDegradationManager.resetForTest();
    InterceptorHolder.setInterceptionEnabled(true, "test-cleanup");
    SamplingController controller = SamplingController.getInstance();
    controller.setSamplingRate(1);
    controller.setMaxRate(100);
    controller.setThresholdInvocationsPerSecond(10000);
  }
}
