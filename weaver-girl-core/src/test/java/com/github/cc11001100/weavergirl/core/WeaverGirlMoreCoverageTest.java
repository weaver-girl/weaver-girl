package com.github.cc11001100.weavergirl.core;

import static org.junit.jupiter.api.Assertions.*;

import java.lang.reflect.Method;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import org.junit.jupiter.api.Test;

/**
 * More coverage tests for WeaverGirl.
 */
class WeaverGirlMoreCoverageTest {

  @Test
  void applyCoreConfig_withDisabledPlugins() throws Exception {
    Method method = WeaverGirl.class.getDeclaredMethod("applyCoreConfig", Map.class);
    method.setAccessible(true);

    Map<String, String> config = new ConcurrentHashMap<>();
    config.put("disabledPlugins", "plugin-a,plugin-b,plugin-c");

    assertDoesNotThrow(() -> method.invoke(null, config));
  }

  @Test
  void applyCoreConfig_withEmptyDisabledPlugins() throws Exception {
    Method method = WeaverGirl.class.getDeclaredMethod("applyCoreConfig", Map.class);
    method.setAccessible(true);

    Map<String, String> config = new ConcurrentHashMap<>();
    config.put("disabledPlugins", "");

    assertDoesNotThrow(() -> method.invoke(null, config));
  }

}
