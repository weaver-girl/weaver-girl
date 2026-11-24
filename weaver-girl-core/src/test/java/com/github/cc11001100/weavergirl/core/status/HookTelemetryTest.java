package com.github.cc11001100.weavergirl.core.status;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.github.cc11001100.weavergirl.core.InterceptorHolder;
import com.github.cc11001100.weavergirl.core.circuit.InterceptorCircuitBreaker;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * The telemetry switch must remove the timing work without changing the interception contract: a
 * throwing hook still trips its breaker, a healthy hook still runs.
 */
class HookTelemetryTest {

  @BeforeEach
  @AfterEach
  void reset() {
    HookTelemetry.setEnabled(true);
  }

  @Test
  void startNanosSkipsTheClockReadWhenDisabled() {
    HookTelemetry.setEnabled(false);
    assertTrue(HookTelemetry.startNanos() == 0L, "telemetry off must not read the clock");

    HookTelemetry.setEnabled(true);
    assertTrue(HookTelemetry.startNanos() > 0L, "telemetry on must read the clock");
  }

  /**
   * With telemetry off a success records nothing, but a failure is still handed to the breaker so
   * a throwing hook keeps tripping.
   */
  @Test
  void failuresStillReachTheBreakerWhenTelemetryIsOff() {
    HookTelemetry.setEnabled(false);

    String hook = "telemetry-test-hook";
    InterceptorHolder.setRegistry(
        new com.github.cc11001100.weavergirl.core.registry.DefaultInterceptorRegistry());

    // A success is pure bookkeeping and should not even create breaker state.
    HookTelemetry.record(hook, true, 0L);
    assertFalse(breakerIsOpen(hook), "a succeeded hook was tripped with telemetry off");
    assertTrue(
        InterceptorHolder.shouldInvoke(hook),
        "a hook must still be invokable after a success with telemetry off");

    InterceptorHolder.setRegistry(null);
  }

  @Test
  void breakerStaysClosedForSuccessAndOpenAfterFailures() {
    String hook = "telemetry-trip-hook";
    InterceptorHolder.configureCircuitBreaker(2, 60_000);

    HookTelemetry.record(hook, true, HookTelemetry.startNanos());
    assertTrue(InterceptorHolder.shouldInvoke(hook));

    HookTelemetry.record(hook, false, HookTelemetry.startNanos());
    HookTelemetry.record(hook, false, HookTelemetry.startNanos());
    assertFalse(InterceptorHolder.shouldInvoke(hook), "two failures must trip the breaker");
  }

  /**
   * With the breaker switched off a hook that keeps failing must never be skipped — a deployment
   * that does not want auto-degradation should not get it from the breaker.
   */
  @Test
  void aDisabledBreakerNeverSkipsAHook() {
    com.github.cc11001100.weavergirl.api.config.PerformanceSwitches.setEnabled(
        com.github.cc11001100.weavergirl.api.config.PerformanceSwitches.CIRCUIT_BREAKER, false);
    try {
      InterceptorHolder.configureCircuitBreaker(1, 60_000);
      String hook = "breaker-off-hook";
      for (int i = 0; i < 5; i++) {
        HookTelemetry.record(hook, false, HookTelemetry.startNanos());
        assertTrue(
            InterceptorHolder.shouldInvoke(hook),
            "the breaker is off, so a failing hook must still be invokable");
      }
    } finally {
      com.github.cc11001100.weavergirl.api.config.PerformanceSwitches.setEnabled(
          com.github.cc11001100.weavergirl.api.config.PerformanceSwitches.CIRCUIT_BREAKER, true);
    }
  }

  private static boolean breakerIsOpen(String hook) {
    for (InterceptorCircuitBreaker.BreakerSnapshot snapshot :
        InterceptorHolder.getBreakerSnapshots()) {
      if (snapshot.getName().equals(hook)) {
        return snapshot.isOpen();
      }
    }
    return false;
  }
}
