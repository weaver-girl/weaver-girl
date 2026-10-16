package com.github.cc11001100.weavergirl.core;

import static org.junit.jupiter.api.Assertions.*;

import com.github.cc11001100.weavergirl.api.config.ConfigChangeEvent;
import com.github.cc11001100.weavergirl.api.config.ConfigChangeListener;
import com.github.cc11001100.weavergirl.core.sampling.SamplingController;
import java.lang.reflect.Constructor;
import java.lang.reflect.Method;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * Tests for WeaverGirl.DynamicCoreConfigListener.
 */
class WeaverGirlDynamicConfigListenerTest {

  private WeaverGirl weaverGirl;
  private ConfigChangeListener listener;

  @BeforeEach
  void setUp() throws Exception {
    weaverGirl = WeaverGirl.create();

    // 通过反射获取 DynamicCoreConfigListener 实例
    Class<?> listenerClass = Class.forName(
        "com.github.cc11001100.weavergirl.core.WeaverGirl$DynamicCoreConfigListener",
        true,
        WeaverGirl.class.getClassLoader());

    Constructor<?> ctor = listenerClass.getDeclaredConstructor(WeaverGirl.class);
    ctor.setAccessible(true);
    listener = (ConfigChangeListener) ctor.newInstance(weaverGirl);
  }

  @Test
  void onConfigChange_samplingRate() throws Exception {
    SamplingController sampling = SamplingController.getInstance();
    int originalRate = sampling.getSamplingRate();

    try {
      ConfigChangeEvent event = new ConfigChangeEvent("samplingRate", "50", "80", "test");
      listener.onConfigChange(event);

      assertEquals(80, sampling.getSamplingRate());
    } finally {
      sampling.setSamplingRate(originalRate);
    }
  }

  @Test
  void onConfigChange_samplingRate_invalidValue() throws Exception {
    SamplingController sampling = SamplingController.getInstance();
    int originalRate = sampling.getSamplingRate();

    try {
      ConfigChangeEvent event = new ConfigChangeEvent("samplingRate", "50", "invalid", "test");
      // Should not throw, just log warning
      assertDoesNotThrow(() -> listener.onConfigChange(event));

      // Rate should remain unchanged
      assertEquals(originalRate, sampling.getSamplingRate());
    } finally {
      sampling.setSamplingRate(originalRate);
    }
  }

  @Test
  void onConfigChange_samplingMaxRate() throws Exception {
    SamplingController sampling = SamplingController.getInstance();
    int originalMaxRate = sampling.getMaxRate();

    try {
      ConfigChangeEvent event = new ConfigChangeEvent("samplingMaxRate", "100", "200", "test");
      listener.onConfigChange(event);

      assertEquals(200, sampling.getMaxRate());
    } finally {
      sampling.setMaxRate(originalMaxRate);
    }
  }

  @Test
  void onConfigChange_samplingThreshold() throws Exception {
    SamplingController sampling = SamplingController.getInstance();
    long originalThreshold = sampling.getThresholdInvocationsPerSecond();

    try {
      ConfigChangeEvent event = new ConfigChangeEvent("samplingThreshold", "100", "500", "test");
      listener.onConfigChange(event);

      assertEquals(500L, sampling.getThresholdInvocationsPerSecond());
    } finally {
      sampling.setThresholdInvocationsPerSecond(originalThreshold);
    }
  }

  @Test
  void onConfigChange_unknownKey_ignored() {
    ConfigChangeEvent event = new ConfigChangeEvent("unknownKey", null, "value", "test");

    // Should not throw
    assertDoesNotThrow(() -> listener.onConfigChange(event));
  }

  @Test
  void onConfigChange_nullValue_ignored() {
    ConfigChangeEvent event = new ConfigChangeEvent("samplingRate", "50", null, "test");

    // Should not throw and should not change rate
    assertDoesNotThrow(() -> listener.onConfigChange(event));
  }
}
