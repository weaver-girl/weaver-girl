package com.github.cc11001100.weavergirl.api.config;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

/**
 * A switch flip has to be visible to the very next reader, because the inlined advice asks on
 * every invocation and a stale read would mean the cost stays until a restart.
 *
 * <p>This is also the property that broke a real run once: the engine and the API module are
 * parent-first visible to the advice, so whoever flips the switch must be flipping the same
 * static field the hot path reads. Volatile state in the API class is where that is guaranteed.
 */
class PerformanceSwitchesStateTest {

  @AfterEach
  void reset() {
    PerformanceSwitches.resetForTest();
  }

  @Test
  void aFlipIsVisibleFromAnotherThreadImmediately() throws Exception {
    PerformanceSwitches.setEnabled(PerformanceSwitches.TELEMETRY, true);

    Thread reader =
        new Thread(
            () -> {
              // Read from a different thread than the writer: volatile is what makes this work.
              assertTrue(PerformanceSwitches.isTelemetryEnabled());
            });
    reader.start();
    reader.join();

    PerformanceSwitches.setEnabled(PerformanceSwitches.TELEMETRY, false);
    assertFalse(PerformanceSwitches.isTelemetryEnabled(), "the flip must be visible at once");
  }

  @Test
  void eachSwitchIsIndependent() {
    PerformanceSwitches.setEnabled(PerformanceSwitches.TELEMETRY, false);
    assertTrue(PerformanceSwitches.isReturnSnapshotEnabled(), "turning telemetry off is not a global off");
    assertTrue(PerformanceSwitches.isMdcBridgeEnabled());
    assertTrue(PerformanceSwitches.isPluginMetricsEnabled());
    assertTrue(PerformanceSwitches.isCircuitBreakerEnabled());
  }
}
