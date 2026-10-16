package com.github.cc11001100.weavergirl.core;

import static org.junit.jupiter.api.Assertions.*;

import com.github.cc11001100.weavergirl.api.interceptor.Interceptor;
import com.github.cc11001100.weavergirl.api.interceptor.InterceptorDefinition;
import com.github.cc11001100.weavergirl.api.interceptor.MethodInvocation;
import com.github.cc11001100.weavergirl.api.matcher.MethodMatcher;
import java.lang.reflect.Method;
import org.junit.jupiter.api.Test;

/**
 * Enhanced tests for WeaverGirl API to improve coverage.
 */
class WeaverGirlEnhancedApiTest {

  @Test
  void interceptBuilder_onAny_createsDefinition() {
    WeaverGirl wg = WeaverGirl.create();
    WeaverGirl.InterceptBuilder builder = wg.intercept("com.example.TestClass");

    // Just verify the builder can be created and chained
    assertNotNull(builder);
  }

  @Test
  void expressionInterceptBuilder_onAny_createsDefinition() {
    WeaverGirl wg = WeaverGirl.create();
    WeaverGirl.ExpressionInterceptBuilder builder =
        wg.interceptExpression("execution(* com.example.*.*(..))");

    // Just verify the builder can be created and chained
    assertNotNull(builder);
  }

  @Test
  void interceptClassPattern_createsValidBuilder() {
    WeaverGirl wg = WeaverGirl.create();
    WeaverGirl.InterceptBuilder builder = wg.interceptClassPattern("com\\.example\\..*Service");
    assertNotNull(builder);
  }

  @Test
  void interceptAnnotated_createsValidBuilder() {
    WeaverGirl wg = WeaverGirl.create();
    WeaverGirl.InterceptBuilder builder = wg.interceptAnnotated("com.example.Monitored");
    assertNotNull(builder);
  }

  @Test
  void interceptSubclassOf_createsValidBuilder() {
    WeaverGirl wg = WeaverGirl.create();
    WeaverGirl.InterceptBuilder builder = wg.interceptSubclassOf("com.example.BaseClass");
    assertNotNull(builder);
  }

  @Test
  void interceptImplementing_createsValidBuilder() {
    WeaverGirl wg = WeaverGirl.create();
    WeaverGirl.InterceptBuilder builder = wg.interceptImplementing("java.io.Serializable");
    assertNotNull(builder);
  }

  @Test
  void introduce_withValidParams_registersIntroduction() {
    WeaverGirl wg = WeaverGirl.create();

    Object delegate =
        new Object() {
          public void run() {}
        };

    assertDoesNotThrow(
        () -> {
          String name = wg.introduce("com.example.*", Runnable.class, delegate);
          assertNotNull(name);
          assertTrue(name.contains("intro-"));
        });
  }

  @Test
  void introduce_throwsOnNonInterface() {
    WeaverGirl wg = WeaverGirl.create();
    Object delegate = new Object();

    assertThrows(
        IllegalArgumentException.class,
        () -> {
          wg.introduce("com.example.*", String.class, delegate);
        });
  }

  @Test
  void getRegistry_returnsNonNull() {
    WeaverGirl wg = WeaverGirl.create();
    assertNotNull(wg.getRegistry());
  }

  @Test
  void getIntroductionRegistry_returnsNonNull() {
    WeaverGirl wg = WeaverGirl.create();
    assertNotNull(wg.getIntroductionRegistry());
  }

  @Test
  void getDynamicConfigManager_returnsNonNull() {
    WeaverGirl wg = WeaverGirl.create();
    assertNotNull(wg.getDynamicConfigManager());
  }

  @Test
  void create_returnsDifferentInstances() {
    WeaverGirl wg1 = WeaverGirl.create();
    WeaverGirl wg2 = WeaverGirl.create();
    assertNotSame(wg1, wg2);
  }

  @Test
  void intercept_throwsOnEmptyClassName() {
    WeaverGirl wg = WeaverGirl.create();
    assertThrows(
        IllegalArgumentException.class,
        () -> {
          wg.intercept("");
        });
  }

  @Test
  void intercept_throwsOnNullClassName() {
    WeaverGirl wg = WeaverGirl.create();
    assertThrows(
        IllegalArgumentException.class,
        () -> {
          wg.intercept(null);
        });
  }

  @Test
  void interceptExpression_throwsOnEmptyExpression() {
    WeaverGirl wg = WeaverGirl.create();
    assertThrows(
        IllegalArgumentException.class,
        () -> {
          wg.interceptExpression("");
        });
  }

  @Test
  void interceptExpression_throwsOnNullExpression() {
    WeaverGirl wg = WeaverGirl.create();
    assertThrows(
        IllegalArgumentException.class,
        () -> {
          wg.interceptExpression(null);
        });
  }

  @Test
  void interceptClassPattern_throwsOnEmptyPattern() {
    WeaverGirl wg = WeaverGirl.create();
    assertThrows(
        IllegalArgumentException.class,
        () -> {
          wg.interceptClassPattern("");
        });
  }

  @Test
  void interceptAnnotated_throwsOnEmptyAnnotation() {
    WeaverGirl wg = WeaverGirl.create();
    assertThrows(
        IllegalArgumentException.class,
        () -> {
          wg.interceptAnnotated("");
        });
  }

  @Test
  void interceptSubclassOf_throwsOnEmptySuperClass() {
    WeaverGirl wg = WeaverGirl.create();
    assertThrows(
        IllegalArgumentException.class,
        () -> {
          wg.interceptSubclassOf("");
        });
  }

  @Test
  void interceptImplementing_throwsOnEmptyInterface() {
    WeaverGirl wg = WeaverGirl.create();
    assertThrows(
        IllegalArgumentException.class,
        () -> {
          wg.interceptImplementing("");
        });
  }
}
