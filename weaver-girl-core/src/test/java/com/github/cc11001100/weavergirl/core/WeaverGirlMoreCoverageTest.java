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
  void parsePositiveInt_withMaxValue() throws Exception {
    WeaverGirl wg = WeaverGirl.create();
    Method method = WeaverGirl.class.getDeclaredMethod("parsePositiveInt", String.class, int.class);
    method.setAccessible(true);

    int result = (int) method.invoke(wg, String.valueOf(Integer.MAX_VALUE), 100);
    assertEquals(Integer.MAX_VALUE, result);
  }

  @Test
  void parsePositiveLong_withMaxValue() throws Exception {
    WeaverGirl wg = WeaverGirl.create();
    Method method = WeaverGirl.class.getDeclaredMethod("parsePositiveLong", String.class, long.class);
    method.setAccessible(true);

    long result = (long) method.invoke(wg, String.valueOf(Long.MAX_VALUE), 1000L);
    assertEquals(Long.MAX_VALUE, result);
  }

  @Test
  void initSpanExport_withAllOptions() {
    WeaverGirl wg = WeaverGirl.create();
    Map<String, String> config = new ConcurrentHashMap<>();
    config.put("otlpEndpoint", "http://localhost:4317");
    config.put("otlpHeaders", "key1=val1;key2=val2");
    config.put("spanExportBatchSize", "500");
    config.put("spanExportIntervalMs", "2000");
    config.put("spanExportBufferSize", "20000");

    assertDoesNotThrow(() -> wg.initSpanExport(config));
  }

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

  @Test
  void initDynamicRefresh_withNegativeInterval() {
    WeaverGirl wg = WeaverGirl.create();
    Map<String, String> config = new ConcurrentHashMap<>();
    config.put("dynamicRefreshInterval", "-1");

    assertDoesNotThrow(() -> wg.initDynamicRefresh(config));
  }

  @Test
  void stopAllComponents_multipleCalls() {
    WeaverGirl wg = WeaverGirl.create();
    assertDoesNotThrow(() -> {
      wg.stopSpanExport();
      wg.stopDynamicRefresh();
      wg.stopSpanExport();
      wg.stopDynamicRefresh();
    });
  }
}
