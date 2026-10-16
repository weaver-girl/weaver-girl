package com.github.cc11001100.weavergirl.core;

import static org.junit.jupiter.api.Assertions.*;

import java.lang.reflect.Method;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import org.junit.jupiter.api.Test;

/**
 * Additional tests for WeaverGirl parse methods to improve coverage.
 */
class WeaverGirlParseMethodsTest {

  @Test
  void parsePositiveInt_edgeCases() throws Exception {
    WeaverGirl wg = WeaverGirl.create();
    Method method = WeaverGirl.class.getDeclaredMethod("parsePositiveInt", String.class, int.class);
    method.setAccessible(true);

    // Test various edge cases
    assertEquals(1, (int) method.invoke(wg, "1", 100));
    assertEquals(999, (int) method.invoke(wg, "999", 100));
    assertEquals(100, (int) method.invoke(wg, "", 100));
    assertEquals(100, (int) method.invoke(wg, "   ", 100));
  }

  @Test
  void parsePositiveLong_edgeCases() throws Exception {
    WeaverGirl wg = WeaverGirl.create();
    Method method = WeaverGirl.class.getDeclaredMethod("parsePositiveLong", String.class, long.class);
    method.setAccessible(true);

    assertEquals(1L, (long) method.invoke(wg, "1", 1000L));
    assertEquals(9999L, (long) method.invoke(wg, "9999", 1000L));
    assertEquals(1000L, (long) method.invoke(wg, "", 1000L));
  }

  @Test
  void initSpanExport_withLogOnly() {
    WeaverGirl wg = WeaverGirl.create();
    Map<String, String> config = new ConcurrentHashMap<>();
    config.put("spanExport", "true");

    assertDoesNotThrow(() -> wg.initSpanExport(config));
  }

  @Test
  void initSpanExport_withOtlpEndpoint() {
    WeaverGirl wg = WeaverGirl.create();
    Map<String, String> config = new ConcurrentHashMap<>();
    config.put("otlpEndpoint", "http://localhost:4317");

    assertDoesNotThrow(() -> wg.initSpanExport(config));
  }

  @Test
  void initDynamicRefresh_withInterval() {
    WeaverGirl wg = WeaverGirl.create();
    Map<String, String> config = new ConcurrentHashMap<>();
    config.put("dynamicRefreshInterval", "30");

    assertDoesNotThrow(() -> wg.initDynamicRefresh(config));
  }
}
