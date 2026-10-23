package com.github.cc11001100.weavergirl.core;

import static org.junit.jupiter.api.Assertions.*;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import org.junit.jupiter.api.Test;

/**
 * Final push tests for WeaverGirl to maximize coverage.
 */
class WeaverGirlFinalCoverageTest {

  @Test
  void applyCoreConfig_withMultipleConfigs() throws Exception {
    Method method = WeaverGirl.class.getDeclaredMethod("applyCoreConfig", Map.class);
    method.setAccessible(true);

    Map<String, String> config = new ConcurrentHashMap<>();
    config.put("samplingRate", "0.8");
    config.put("apiPort", "9090");
    config.put("disabledPlugins", "plugin1,plugin2");

    assertDoesNotThrow(() -> method.invoke(null, config));
  }

}
