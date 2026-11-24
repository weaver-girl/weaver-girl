package com.github.cc11001100.weavergirl.core;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.github.cc11001100.weavergirl.api.config.ConfigChangeEvent;
import com.github.cc11001100.weavergirl.api.config.ConfigChangeListener;
import com.github.cc11001100.weavergirl.core.config.WeaverConfig;
import com.github.cc11001100.weavergirl.core.sampling.SamplingController;
import java.lang.reflect.Constructor;
import java.lang.reflect.Method;
import java.util.Collections;
import java.util.HashMap;
import java.util.Map;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

/**
 * The documented agent arguments have to change what the engine does, not only what it logs.
 *
 * <p>Both settings were inert before: {@code circuitBreakerThreshold}/{@code
 * circuitBreakerCooldownMs} were logged and never applied, and the adaptive-sampling monitor
 * ({@link com.github.cc11001100.weavergirl.core.sampling.SamplingMonitor}) was never started, so
 * {@code SamplingController.adaptRate} had no caller. These tests pin the wiring.
 */
class CoreConfigWiringTest {

  private final int originalThreshold = InterceptorHolder.getCircuitBreakerFailureThreshold();
  private final long originalCooldown = InterceptorHolder.getCircuitBreakerCooldownMillis();
  private final int originalSamplingRate = SamplingController.getInstance().getSamplingRate();

  @AfterEach
  void restore() {
    InterceptorHolder.configureCircuitBreaker(originalThreshold, originalCooldown);
    SamplingController.getInstance().setSamplingRate(originalSamplingRate);
  }

  @Test
  void agentArgumentsRetuneTheLiveCircuitBreaker() throws Exception {
    Map<String, String> config = new HashMap<>();
    config.put("circuitBreakerThreshold", "3");
    config.put("circuitBreakerCooldownMs", "15000");

    invokeApplyCoreConfig(config);

    assertEquals(3, InterceptorHolder.getCircuitBreakerFailureThreshold());
    assertEquals(15000L, InterceptorHolder.getCircuitBreakerCooldownMillis());
  }

  @Test
  void invalidOrNonPositiveCircuitBreakerValuesKeepTheCurrentSetting() throws Exception {
    InterceptorHolder.configureCircuitBreaker(7, 20000L);

    Map<String, String> config = new HashMap<>();
    config.put("circuitBreakerThreshold", "not-a-number");
    config.put("circuitBreakerCooldownMs", "0");

    invokeApplyCoreConfig(config);

    assertEquals(7, InterceptorHolder.getCircuitBreakerFailureThreshold());
    assertEquals(20000L, InterceptorHolder.getCircuitBreakerCooldownMillis());
  }

  @Test
  void dynamicConfigRetunesTheBreakerToo() throws Exception {
    InterceptorHolder.configureCircuitBreaker(5, 60000L);
    ConfigChangeListener listener = dynamicCoreConfigListener();

    listener.onConfigChange(new ConfigChangeEvent("circuitBreakerThreshold", "5", "2", "test"));
    assertEquals(2, InterceptorHolder.getCircuitBreakerFailureThreshold());
    assertEquals(60000L, InterceptorHolder.getCircuitBreakerCooldownMillis());

    listener.onConfigChange(new ConfigChangeEvent("circuitBreakerCooldownMs", "60000", "5000", "test"));
    assertEquals(2, InterceptorHolder.getCircuitBreakerFailureThreshold());
    assertEquals(5000L, InterceptorHolder.getCircuitBreakerCooldownMillis());
  }

  // --- per-call performance switches ---

  @Test
  void agentArgumentsTurnThePerformanceSwitchesOff() throws Exception {
    Map<String, String> config = new HashMap<>();
    config.put("telemetry", "false");
    config.put("returnSnapshot", "false");

    invokeApplyCoreConfig(config);

    assertFalse(
        com.github.cc11001100.weavergirl.api.config.PerformanceSwitches.isTelemetryEnabled(),
        "telemetry=false as an agent argument must reach the switch");
    assertFalse(
        com.github.cc11001100.weavergirl.api.config.PerformanceSwitches.isReturnSnapshotEnabled(),
        "returnSnapshot=false as an agent argument must reach the switch");
  }

  @Test
  void aSystemPropertyOverridesTheAgentArguments() throws Exception {
    System.setProperty("weavergirl.mdc.enabled", "false");
    try {
      com.github.cc11001100.weavergirl.api.config.PerformanceSwitches.resetForTest();
      com.github.cc11001100.weavergirl.api.config.PerformanceSwitches
          .reloadFromSystemPropertiesForTest();
      Map<String, String> config = new HashMap<>();
      config.put("mdc", "true");

      invokeApplyCoreConfig(config);

      assertFalse(
          com.github.cc11001100.weavergirl.api.config.PerformanceSwitches.isMdcBridgeEnabled(),
          "a -D override must win over the agent argument");
    } finally {
      System.clearProperty("weavergirl.mdc.enabled");
      com.github.cc11001100.weavergirl.api.config.PerformanceSwitches.resetForTest();
    }
  }

  /**
   * A wrong-typed value must be reported rather than silently applied or ignored. The bootstrap
   * path runs ConfigValidator before touching the components.
   */
  @Test
  void aWrongTypedValueIsReportedWithoutBlockingStartup() throws Exception {
    java.util.List<String> errors =
        com.github.cc11001100.weavergirl.core.config.validation.ConfigValidator.validate(
                Collections.singletonMap("samplingRate", "not-a-number"))
            .getErrors();

    assertEquals(
        1,
        errors.size(),
        "an invalid samplingRate must be reported as an error: " + errors);

    // Startup itself must not throw: applyCoreConfig logs the error and keeps the current value.
    invokeApplyCoreConfig(Collections.singletonMap("samplingRate", "not-a-number"));
  }

  @Test
  void dynamicConfigFlipsAPerformanceSwitch() throws Exception {
    ConfigChangeListener listener = dynamicCoreConfigListener();
    com.github.cc11001100.weavergirl.api.config.PerformanceSwitches.setEnabled("telemetry", true);

    listener.onConfigChange(new ConfigChangeEvent("telemetry", "true", "false", "test"));

    assertFalse(
        com.github.cc11001100.weavergirl.api.config.PerformanceSwitches.isTelemetryEnabled(),
        "a runtime change to telemetry must take effect without a restart");
    com.github.cc11001100.weavergirl.api.config.PerformanceSwitches.setEnabled("telemetry", true);
  }

  @Test
  void bootstrapStartsTheAdaptiveSamplingMonitor() throws Exception {
    WeaverGirl agent =
        WeaverGirl.bootstrap(
            net.bytebuddy.agent.ByteBuddyAgent.install(),
            Collections.<String, String>singletonMap("adaptiveSampling", "true"),
            null);
    try {
      Method accessor = WeaverGirl.class.getDeclaredMethod("getSamplingMonitorForTest");
      accessor.setAccessible(true);
      assertNotNull(accessor.invoke(agent), "adaptive sampling was requested but no monitor runs");
    } finally {
      agent.shutdown();
    }
  }

  @Test
  void adaptiveSamplingCanBeSwitchedOff() throws Exception {
    WeaverGirl agent =
        WeaverGirl.bootstrap(
            net.bytebuddy.agent.ByteBuddyAgent.install(),
            Collections.<String, String>singletonMap("adaptiveSampling", "false"),
            null);
    try {
      Method accessor = WeaverGirl.class.getDeclaredMethod("getSamplingMonitorForTest");
      accessor.setAccessible(true);
      assertEquals(null, accessor.invoke(agent), "adaptiveSampling=false must not start a thread");
    } finally {
      agent.shutdown();
    }
  }

  @Test
  void stateFileStartsThePersisterAndWritesASnapshot(@org.junit.jupiter.api.io.TempDir java.nio.file.Path dir)
      throws Exception {
    WeaverGirl agent =
        WeaverGirl.bootstrap(
            net.bytebuddy.agent.ByteBuddyAgent.install(),
            Collections.<String, String>singletonMap("stateFile", dir.toString()),
            null);
    try {
      Method accessor = WeaverGirl.class.getDeclaredMethod("getStatePersisterForTest");
      accessor.setAccessible(true);
      assertNotNull(accessor.invoke(agent), "stateFile was set but nothing persists the state");
    } finally {
      // shutdown() takes a final snapshot and stops the scheduler, which is what the test relies on.
      agent.shutdown();
    }
    // The capability is real only if bytes land on disk.
    assertTrue(
        java.nio.file.Files.exists(dir.resolve("agent-state.properties")),
        "no snapshot was written into " + dir);
  }

  @Test
  void statePersistenceStaysOffWithoutStateFile() throws Exception {
    WeaverGirl agent =
        WeaverGirl.bootstrap(net.bytebuddy.agent.ByteBuddyAgent.install(), Collections.<String, String>emptyMap(), null);
    try {
      Method accessor = WeaverGirl.class.getDeclaredMethod("getStatePersisterForTest");
      accessor.setAccessible(true);
      assertEquals(null, accessor.invoke(agent), "no stateFile means no snapshot thread");
    } finally {
      agent.shutdown();
    }
  }

  @Test
  void theMonitorActuallyRaisesTheRateUnderLoad() throws Exception {
    // The point of the monitor is that adaptRate runs; prove the mechanism itself moves the rate.
    SamplingController controller = SamplingController.getInstance();
    controller.setSamplingRate(1);
    controller.setMaxRate(10);
    controller.setThresholdInvocationsPerSecond(10);

    Method adapt = SamplingController.class.getDeclaredMethod("adaptRate", double.class);
    adapt.setAccessible(true);
    adapt.invoke(controller, 100.0);

    assertNotEquals(1, controller.getSamplingRate(), "a load above the threshold must sample less");

    adapt.invoke(controller, 0.0);
    assertTrue(controller.getSamplingRate() >= 1);
  }

  private static void invokeApplyCoreConfig(Map<String, String> config) throws Exception {
    Method method =
        WeaverGirl.class.getDeclaredMethod("applyCoreConfig", Map.class);
    method.setAccessible(true);
    try {
      method.invoke(null, config);
    } finally {
      // applyCoreConfig is a process-wide side-effect path: a config with
      // weavergirl.emergency.disable=true enters emergency mode, which turns the global
      // interception kill-switch off and caps the sampling rate. Left in place that leaks into
      // every later test in the same surefire fork (the integration tests then see a disabled
      // kill-switch and report "before() should have been called" failures).
      com.github.cc11001100.weavergirl.core.testsupport.ProcessWideState.restore();
    }
  }

  private static ConfigChangeListener dynamicCoreConfigListener() throws Exception {
    WeaverGirl agent = WeaverGirl.create();
    Class<?> listenerClass =
        Class.forName(
            "com.github.cc11001100.weavergirl.core.WeaverGirl$DynamicCoreConfigListener",
            true,
            WeaverGirl.class.getClassLoader());
    Constructor<?> ctor = listenerClass.getDeclaredConstructor(WeaverGirl.class);
    ctor.setAccessible(true);
    return (ConfigChangeListener) ctor.newInstance(agent);
  }
}
