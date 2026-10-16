package com.github.cc11001100.weavergirl.core;

import static org.junit.jupiter.api.Assertions.*;

import org.junit.jupiter.api.Test;

/**
 * Comprehensive test for WeaverGirl builder patterns to improve coverage.
 */
class WeaverGirlBuilderComprehensiveTest {

  @Test
  void interceptBuilder_allMethods() {
    WeaverGirl wg = WeaverGirl.create();

    assertDoesNotThrow(() -> {
      wg.intercept("com.example.Test")
          .anyMethod()
          .before(inv -> {})
          .after(inv -> {})
          .onException(inv -> {})
          .priority(100)
          .install();
    });
  }

  @Test
  void expressionInterceptBuilder_allMethods() {
    WeaverGirl wg = WeaverGirl.create();

    assertDoesNotThrow(() -> {
      wg.interceptExpression("execution(* com.example.*.*(..))")
          .before(inv -> {})
          .after(inv -> {})
          .onException(inv -> {})
          .priority(50);
    });
  }

  @Test
  void interceptClassPattern_createsBuilder() {
    WeaverGirl wg = WeaverGirl.create();

    assertDoesNotThrow(() -> {
      wg.interceptClassPattern("com\\.example\\..*Service")
          .anyMethod()
          .before(inv -> {});
    });
  }

  @Test
  void interceptAnnotated_createsBuilder() {
    WeaverGirl wg = WeaverGirl.create();

    assertDoesNotThrow(() -> {
      wg.interceptAnnotated("com.example.Monitored")
          .anyMethod()
          .before(inv -> {});
    });
  }

  @Test
  void interceptSubclassOf_createsBuilder() {
    WeaverGirl wg = WeaverGirl.create();

    assertDoesNotThrow(() -> {
      wg.interceptSubclassOf("com.example.BaseService")
          .anyMethod()
          .before(inv -> {});
    });
  }

  @Test
  void interceptImplementing_createsBuilder() {
    WeaverGirl wg = WeaverGirl.create();

    assertDoesNotThrow(() -> {
      wg.interceptImplementing("java.io.Serializable")
          .anyMethod()
          .before(inv -> {});
    });
  }

  @Test
  void multipleInterceptCalls() {
    WeaverGirl wg = WeaverGirl.create();

    assertDoesNotThrow(() -> {
      wg.intercept("com.example.A").anyMethod().before(inv -> {});
      wg.intercept("com.example.B").anyMethod().after(inv -> {});
      wg.intercept("com.example.C").anyMethod().onException(inv -> {});
    });
  }
}
