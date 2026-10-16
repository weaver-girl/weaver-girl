package com.github.cc11001100.weavergirl.core;

import static org.junit.jupiter.api.Assertions.*;

import com.github.cc11001100.weavergirl.api.interceptor.Interceptor;
import com.github.cc11001100.weavergirl.api.interceptor.InterceptorDefinition;
import com.github.cc11001100.weavergirl.api.interceptor.MethodInvocation;
import com.github.cc11001100.weavergirl.api.matcher.MethodMatcher;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import org.junit.jupiter.api.Test;

/**
 * Tests for WeaverGirl internal methods to improve coverage.
 */
class WeaverGirlInternalMethodsTest {

  @Test
  void parsePositiveInt_withValidValue_returnsValue() throws Exception {
    WeaverGirl wg = WeaverGirl.create();

    Method method = WeaverGirl.class.getDeclaredMethod("parsePositiveInt", String.class, int.class);
    method.setAccessible(true);

    int result = (int) method.invoke(wg, "100", 50);
    assertEquals(100, result);
  }

  @Test
  void parsePositiveInt_withNull_returnsDefault() throws Exception {
    WeaverGirl wg = WeaverGirl.create();

    Method method = WeaverGirl.class.getDeclaredMethod("parsePositiveInt", String.class, int.class);
    method.setAccessible(true);

    int result = (int) method.invoke(wg, (String) null, 50);
    assertEquals(50, result);
  }

  @Test
  void parsePositiveInt_withInvalid_returnsDefault() throws Exception {
    WeaverGirl wg = WeaverGirl.create();

    Method method = WeaverGirl.class.getDeclaredMethod("parsePositiveInt", String.class, int.class);
    method.setAccessible(true);

    int result = (int) method.invoke(wg, "invalid", 50);
    assertEquals(50, result);
  }

  @Test
  void parsePositiveInt_withZero_returnsDefault() throws Exception {
    WeaverGirl wg = WeaverGirl.create();

    Method method = WeaverGirl.class.getDeclaredMethod("parsePositiveInt", String.class, int.class);
    method.setAccessible(true);

    int result = (int) method.invoke(wg, "0", 50);
    assertEquals(50, result);
  }

  @Test
  void parsePositiveInt_withNegative_returnsDefault() throws Exception {
    WeaverGirl wg = WeaverGirl.create();

    Method method = WeaverGirl.class.getDeclaredMethod("parsePositiveInt", String.class, int.class);
    method.setAccessible(true);

    int result = (int) method.invoke(wg, "-10", 50);
    assertEquals(50, result);
  }

  @Test
  void parsePositiveLong_withValidValue_returnsValue() throws Exception {
    WeaverGirl wg = WeaverGirl.create();

    Method method = WeaverGirl.class.getDeclaredMethod("parsePositiveLong", String.class, long.class);
    method.setAccessible(true);

    long result = (long) method.invoke(wg, "5000", 1000L);
    assertEquals(5000L, result);
  }

  @Test
  void parsePositiveLong_withNull_returnsDefault() throws Exception {
    WeaverGirl wg = WeaverGirl.create();

    Method method = WeaverGirl.class.getDeclaredMethod("parsePositiveLong", String.class, long.class);
    method.setAccessible(true);

    long result = (long) method.invoke(wg, (String) null, 1000L);
    assertEquals(1000L, result);
  }

  @Test
  void parsePositiveLong_withInvalid_returnsDefault() throws Exception {
    WeaverGirl wg = WeaverGirl.create();

    Method method = WeaverGirl.class.getDeclaredMethod("parsePositiveLong", String.class, long.class);
    method.setAccessible(true);

    long result = (long) method.invoke(wg, "invalid", 1000L);
    assertEquals(1000L, result);
  }

  @Test
  void applyCoreConfig_withNull_doesNotThrow() throws Exception {
    Method method = WeaverGirl.class.getDeclaredMethod("applyCoreConfig", Map.class);
    method.setAccessible(true);

    assertDoesNotThrow(
        () -> {
          method.invoke(null, (Map<String, String>) null);
        });
  }

  @Test
  void applyCoreConfig_withEmpty_doesNotThrow() throws Exception {
    Method method = WeaverGirl.class.getDeclaredMethod("applyCoreConfig", Map.class);
    method.setAccessible(true);

    Map<String, String> empty = new ConcurrentHashMap<>();
    assertDoesNotThrow(
        () -> {
          method.invoke(null, empty);
        });
  }

  @Test
  void initSpanExport_withNull_doesNotThrow() {
    WeaverGirl wg = WeaverGirl.create();

    assertDoesNotThrow(
        () -> {
          wg.initSpanExport(null);
        });
  }

  @Test
  void initSpanExport_withEmpty_doesNotThrow() {
    WeaverGirl wg = WeaverGirl.create();

    Map<String, String> empty = new ConcurrentHashMap<>();
    assertDoesNotThrow(
        () -> {
          wg.initSpanExport(empty);
        });
  }

  @Test
  void initDynamicRefresh_withNull_doesNotThrow() {
    WeaverGirl wg = WeaverGirl.create();

    assertDoesNotThrow(
        () -> {
          wg.initDynamicRefresh(null);
        });
  }

  @Test
  void stopSpanExport_doesNotThrow() {
    WeaverGirl wg = WeaverGirl.create();

    assertDoesNotThrow(
        () -> {
          wg.stopSpanExport();
        });
  }

  @Test
  void stopDynamicRefresh_doesNotThrow() {
    WeaverGirl wg = WeaverGirl.create();

    assertDoesNotThrow(
        () -> {
          wg.stopDynamicRefresh();
        });
  }
}
