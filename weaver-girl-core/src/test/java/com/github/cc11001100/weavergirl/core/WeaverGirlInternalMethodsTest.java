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

}
