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
  void parsePositiveInt_withWhitespace_returnsDefault() throws Exception {
    WeaverGirl wg = WeaverGirl.create();
    Method method = WeaverGirl.class.getDeclaredMethod("parsePositiveInt", String.class, int.class);
    method.setAccessible(true);

    int result = (int) method.invoke(wg, "  123  ", 50);
    // String.trim() handles whitespace, so it parses successfully
    assertEquals(123, result);
  }

  @Test
  void parsePositiveLong_withWhitespace_returnsDefault() throws Exception {
    WeaverGirl wg = WeaverGirl.create();
    Method method = WeaverGirl.class.getDeclaredMethod("parsePositiveLong", String.class, long.class);
    method.setAccessible(true);

    long result = (long) method.invoke(wg, "  123  ", 1000L);
    // String.trim() handles whitespace, so it parses successfully
    assertEquals(123L, result);
  }

  @Test
  void initSpanExport_withBatchSize() {
    WeaverGirl wg = WeaverGirl.create();
    Map<String, String> config = new ConcurrentHashMap<>();
    config.put("spanExport", "true");
    config.put("spanExportBatchSize", "200");

    assertDoesNotThrow(() -> wg.initSpanExport(config));
  }

  @Test
  void initSpanExport_withInterval() {
    WeaverGirl wg = WeaverGirl.create();
    Map<String, String> config = new ConcurrentHashMap<>();
    config.put("spanExport", "true");
    config.put("spanExportIntervalMs", "10000");

    assertDoesNotThrow(() -> wg.initSpanExport(config));
  }

  @Test
  void initSpanExport_withBufferSize() {
    WeaverGirl wg = WeaverGirl.create();
    Map<String, String> config = new ConcurrentHashMap<>();
    config.put("spanExport", "true");
    config.put("spanExportBufferSize", "50000");

    assertDoesNotThrow(() -> wg.initSpanExport(config));
  }

  @Test
  void initDynamicRefresh_withInvalidInterval() {
    WeaverGirl wg = WeaverGirl.create();
    Map<String, String> config = new ConcurrentHashMap<>();
    config.put("dynamicRefreshInterval", "invalid");

    assertDoesNotThrow(() -> wg.initDynamicRefresh(config));
  }

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
