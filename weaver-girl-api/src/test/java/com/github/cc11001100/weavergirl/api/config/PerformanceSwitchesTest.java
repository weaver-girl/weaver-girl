package com.github.cc11001100.weavergirl.api.config;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.LinkedHashMap;
import java.util.Map;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

/** The switches default on, can be flipped by key, and let a system property win over a config map. */
class PerformanceSwitchesTest {

  private static final String[] KEYS = {
    PerformanceSwitches.TELEMETRY,
    PerformanceSwitches.RETURN_SNAPSHOT,
    PerformanceSwitches.MDC_BRIDGE,
    PerformanceSwitches.PLUGIN_METRICS,
    PerformanceSwitches.CIRCUIT_BREAKER
  };

  @AfterEach
  void reset() {
    for (String key : KEYS) {
      System.clearProperty("weavergirl." + key + ".enabled");
    }
    PerformanceSwitches.resetForTest();
    PerformanceSwitches.reloadFromSystemPropertiesForTest();
  }

  @Test
  void everySwitchDefaultsOn() {
    Map<String, Boolean> state = PerformanceSwitches.snapshot();
    assertNotNull(state);
    for (String key : KEYS) {
      assertTrue(state.get(key), key + " should default to enabled");
    }
  }

  @Test
  void setEnabledFlipsEachSwitch() {
    for (String key : KEYS) {
      assertTrue(PerformanceSwitches.setEnabled(key, false), key + " should be a known key");
      assertFalse(PerformanceSwitches.snapshot().get(key), key + " should read back as off");
      PerformanceSwitches.setEnabled(key, true);
      assertTrue(PerformanceSwitches.snapshot().get(key), key + " should read back as on");
    }
  }

  @Test
  void unknownKeysAreIgnored() {
    assertFalse(PerformanceSwitches.setEnabled("notASwitch", false));
    assertFalse(PerformanceSwitches.isKnownKey("notASwitch"));
  }

  @Test
  void applyFromReadsBooleansAndIgnoresOtherKeys() {
    Map<String, String> config = new LinkedHashMap<>();
    config.put(PerformanceSwitches.TELEMETRY, "false");
    config.put(PerformanceSwitches.RETURN_SNAPSHOT, "false");
    config.put("samplingRate", "10"); // not a switch
    config.put("somePluginOption", "x");

    PerformanceSwitches.applyFrom(config);

    assertFalse(PerformanceSwitches.isTelemetryEnabled());
    assertFalse(PerformanceSwitches.isReturnSnapshotEnabled());
    // Untouched switches keep their default.
    assertTrue(PerformanceSwitches.isMdcBridgeEnabled());
    assertTrue(PerformanceSwitches.isCircuitBreakerEnabled());
  }

  /** Anything that is not clearly true reads as false, matching how agent args parse. */
  @Test
  void nonTrueValuesTurnTheSwitchOff() {
    Map<String, String> config = new LinkedHashMap<>();
    config.put(PerformanceSwitches.TELEMETRY, "yes");
    PerformanceSwitches.applyFrom(config);
    assertFalse(PerformanceSwitches.isTelemetryEnabled());
  }

  /** A -D override must survive a config map that says otherwise. */
  @Test
  void systemPropertyWinsOverConfigMap() {
    System.setProperty("weavergirl.telemetry.enabled", "true");
    Map<String, String> config = new LinkedHashMap<>();
    config.put(PerformanceSwitches.TELEMETRY, "false");

    PerformanceSwitches.reloadFromSystemPropertiesForTest();
    PerformanceSwitches.applyFrom(config);

    assertTrue(
        PerformanceSwitches.isTelemetryEnabled(),
        "the system property should keep the switch on despite the config map");
  }

  @Test
  void systemPropertyCanTurnASwitchOff() {
    System.setProperty("weavergirl.circuitBreaker.enabled", "false");
    PerformanceSwitches.reloadFromSystemPropertiesForTest();
    assertFalse(PerformanceSwitches.isCircuitBreakerEnabled());
  }
}
