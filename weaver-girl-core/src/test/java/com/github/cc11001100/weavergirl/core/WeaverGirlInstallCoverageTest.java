package com.github.cc11001100.weavergirl.core;

import static org.junit.jupiter.api.Assertions.*;

import java.lang.instrument.Instrumentation;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

/**
 * Tests that trigger install() to cover anonymous Interceptor classes.
 */
class WeaverGirlInstallCoverageTest {

  @Test
  void interceptBuilder_install_withoutInstrumentation_createsDefinition() {
    WeaverGirl wg = WeaverGirl.create();

    // Just create the builder and install without actual instrumentation
    // This exercises the Interceptor creation code path
    assertDoesNotThrow(() -> {
      wg.intercept("com.example.TestClass")
          .anyMethod()
          .before(inv -> {})
          .after(inv -> {})
          .install();
    });
  }

  @Test
  void expressionInterceptBuilder_install_withoutInstrumentation_createsDefinition() {
    WeaverGirl wg = WeaverGirl.create();

    assertDoesNotThrow(() -> {
      wg.interceptExpression("execution(* com.example.*.*(..))")
          .before(inv -> {})
          .after(inv -> {})
          .install();
    });
  }
}
