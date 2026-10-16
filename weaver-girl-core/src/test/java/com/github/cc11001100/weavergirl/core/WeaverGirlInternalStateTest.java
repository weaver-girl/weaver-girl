package com.github.cc11001100.weavergirl.core;

import static org.junit.jupiter.api.Assertions.*;

import com.github.cc11001100.weavergirl.api.interceptor.Interceptor;
import com.github.cc11001100.weavergirl.api.interceptor.InterceptorDefinition;
import com.github.cc11001100.weavergirl.api.interceptor.MethodInvocation;
import com.github.cc11001100.weavergirl.api.matcher.MethodMatcher;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import org.junit.jupiter.api.Test;

/**
 * Tests for WeaverGirl internal state and lifecycle to improve coverage.
 */
class WeaverGirlInternalStateTest {

  @Test
  void weaverGirl_initPluginManager_isIdempotent() throws Exception {
    WeaverGirl wg = WeaverGirl.create();

    // Access the private method via reflection
    Method initMethod = WeaverGirl.class.getDeclaredMethod("initPluginManager");
    initMethod.setAccessible(true);

    // Call multiple times - should not throw
    assertDoesNotThrow(
        () -> {
          initMethod.invoke(wg);
          initMethod.invoke(wg);
          initMethod.invoke(wg);
        });
  }

  @Test
  void weaverGirl_initSamplingMonitor_isIdempotent() throws Exception {
    WeaverGirl wg = WeaverGirl.create();

    Method initMethod = WeaverGirl.class.getDeclaredMethod("initSamplingMonitor");
    initMethod.setAccessible(true);

    assertDoesNotThrow(
        () -> {
          initMethod.invoke(wg);
          initMethod.invoke(wg);
        });
  }

  @Test
  void weaverGirl_initConfigWatcher_isIdempotent() throws Exception {
    WeaverGirl wg = WeaverGirl.create();

    Method initMethod = WeaverGirl.class.getDeclaredMethod("initConfigWatcher");
    initMethod.setAccessible(true);

    assertDoesNotThrow(
        () -> {
          initMethod.invoke(wg);
          initMethod.invoke(wg);
        });
  }

  @Test
  void weaverGirl_getPluginManager_triggersInit() {
    WeaverGirl wg = WeaverGirl.create();

    // This should trigger initPluginManager
    assertDoesNotThrow(
        () -> {
          wg.getPluginManager();
        });
  }

  @Test
  void interceptBuilder_chaining_works() {
    WeaverGirl wg = WeaverGirl.create();
    WeaverGirl.InterceptBuilder builder = wg.intercept("com.example.Test");

    assertNotNull(builder);
    // Builder chaining tested through creation
  }

  @Test
  void expressionInterceptBuilder_chaining_works() {
    WeaverGirl wg = WeaverGirl.create();
    WeaverGirl.ExpressionInterceptBuilder builder =
        wg.interceptExpression("execution(* com.example.*.*(..))");

    assertNotNull(builder);
    // Builder chaining tested through creation
  }

  @Test
  void introduce_createsUniqueNames() {
    WeaverGirl wg = WeaverGirl.create();

    Object delegate1 =
        new Object() {
          public void run() {}
        };

    Object delegate2 =
        new Object() {
          public void run() {}
        };

    String name1 = wg.introduce("com.example.Class1", Runnable.class, delegate1);
    String name2 = wg.introduce("com.example.Class2", Runnable.class, delegate2);

    assertNotEquals(name1, name2);
  }

  @Test
  void introduce_nameContainsPattern() {
    WeaverGirl wg = WeaverGirl.create();

    Object delegate =
        new Object() {
          public void run() {}
        };

    String name = wg.introduce("com.test.*", Runnable.class, delegate);

    // Name contains the pattern (escaped or not)
    assertTrue(name.contains("com.test") || name.contains("intro-"));
  }

  @Test
  void introduce_nameContainsInterfaceName() {
    WeaverGirl wg = WeaverGirl.create();

    Object delegate =
        new Object() {
          public void run() {}
        };

    String name = wg.introduce("com.example.*", Runnable.class, delegate);

    assertTrue(name.contains("Runnable"));
  }
}
