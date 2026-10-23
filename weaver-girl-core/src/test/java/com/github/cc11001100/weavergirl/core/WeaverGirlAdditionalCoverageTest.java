package com.github.cc11001100.weavergirl.core;

import static org.junit.jupiter.api.Assertions.*;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import org.junit.jupiter.api.Test;

/**
 * Additional tests for WeaverGirl to improve coverage further.
 */
class WeaverGirlAdditionalCoverageTest {

  @Test
  void applyCoreConfig_withSamplingRate() throws Exception {
    Method method = WeaverGirl.class.getDeclaredMethod("applyCoreConfig", Map.class);
    method.setAccessible(true);

    Map<String, String> config = new ConcurrentHashMap<>();
    config.put("samplingRate", "0.5");

    assertDoesNotThrow(() -> method.invoke(null, config));
  }

  @Test
  void applyCoreConfig_withInvalidSamplingRate() throws Exception {
    Method method = WeaverGirl.class.getDeclaredMethod("applyCoreConfig", Map.class);
    method.setAccessible(true);

    Map<String, String> config = new ConcurrentHashMap<>();
    config.put("samplingRate", "invalid");

    assertDoesNotThrow(() -> method.invoke(null, config));
  }

  @Test
  void applyCoreConfig_withApiPort() throws Exception {
    Method method = WeaverGirl.class.getDeclaredMethod("applyCoreConfig", Map.class);
    method.setAccessible(true);

    Map<String, String> config = new ConcurrentHashMap<>();
    config.put("apiPort", "8080");

    assertDoesNotThrow(() -> method.invoke(null, config));
  }
}
