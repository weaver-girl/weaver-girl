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
  void parsePositiveInt_withLeadingZeros() throws Exception {
    WeaverGirl wg = WeaverGirl.create();
    Method method = WeaverGirl.class.getDeclaredMethod("parsePositiveInt", String.class, int.class);
    method.setAccessible(true);

    int result = (int) method.invoke(wg, "00100", 50);
    assertEquals(100, result);
  }

  @Test
  void parsePositiveLong_withLeadingZeros() throws Exception {
    WeaverGirl wg = WeaverGirl.create();
    Method method = WeaverGirl.class.getDeclaredMethod("parsePositiveLong", String.class, long.class);
    method.setAccessible(true);

    long result = (long) method.invoke(wg, "001000", 1000L);
    assertEquals(1000L, result);
  }

  @Test
  void initSpanExport_withHeaders() {
    WeaverGirl wg = WeaverGirl.create();
    Map<String, String> config = new ConcurrentHashMap<>();
    config.put("otlpEndpoint", "http://localhost:4317");
    config.put("otlpHeaders", "Authorization=Bearer token;X-Custom=value");

    assertDoesNotThrow(() -> wg.initSpanExport(config));
  }

  @Test
  void initSpanExport_withMalformedHeaders() {
    WeaverGirl wg = WeaverGirl.create();
    Map<String, String> config = new ConcurrentHashMap<>();
    config.put("otlpEndpoint", "http://localhost:4317");
    config.put("otlpHeaders", "malformed-header-without-equals");

    assertDoesNotThrow(() -> wg.initSpanExport(config));
  }

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

  @Test
  void initDynamicRefresh_withZeroInterval() {
    WeaverGirl wg = WeaverGirl.create();
    Map<String, String> config = new ConcurrentHashMap<>();
    config.put("dynamicRefreshInterval", "0");

    assertDoesNotThrow(() -> wg.initDynamicRefresh(config));
  }

  @Test
  void stopSpanExport_multipleTimes() {
    WeaverGirl wg = WeaverGirl.create();
    assertDoesNotThrow(() -> {
      wg.stopSpanExport();
      wg.stopSpanExport();
    });
  }

  @Test
  void stopDynamicRefresh_multipleTimes() {
    WeaverGirl wg = WeaverGirl.create();
    assertDoesNotThrow(() -> {
      wg.stopDynamicRefresh();
      wg.stopDynamicRefresh();
    });
  }
}
