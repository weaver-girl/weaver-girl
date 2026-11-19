package com.github.cc11001100.weavergirl.core;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
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
    method.invoke(null, config);
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
