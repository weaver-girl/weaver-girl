package com.github.cc11001100.weavergirl.core;

import static org.junit.jupiter.api.Assertions.*;

import com.github.cc11001100.weavergirl.api.interceptor.MethodInvocation;
import java.util.function.Consumer;
import org.junit.jupiter.api.Test;

/**
 * Tests for ExpressionInterceptBuilder anonymous Interceptor classes.
 */
class WeaverGirlExpressionInterceptBuilderTest {

  @Test
  void expressionInterceptBuilder_beforeCallback() {
    WeaverGirl wg = WeaverGirl.create();

    // This triggers ExpressionInterceptBuilder creation and before() method
    assertDoesNotThrow(() -> {
      wg.interceptExpression("execution(* com.example.Service.*(..))")
          .before(inv -> {});
    });
  }

  @Test
  void expressionInterceptBuilder_afterCallback() {
    WeaverGirl wg = WeaverGirl.create();

    assertDoesNotThrow(() -> {
      wg.interceptExpression("execution(* com.example.Service.*(..))")
          .after(inv -> {});
    });
  }

  @Test
  void expressionInterceptBuilder_onExceptionCallback() {
    WeaverGirl wg = WeaverGirl.create();

    assertDoesNotThrow(() -> {
      wg.interceptExpression("execution(* com.example.Service.*(..))")
          .onException(inv -> {});
    });
  }

  @Test
  void expressionInterceptBuilder_finallyCallback() {
    WeaverGirl wg = WeaverGirl.create();

    assertDoesNotThrow(() -> {
      wg.interceptExpression("execution(* com.example.Service.*(..))")
          .afterFinally(inv -> {});
    });
  }

  @Test
  void expressionInterceptBuilder_chainedCallbacks() {
    WeaverGirl wg = WeaverGirl.create();

    assertDoesNotThrow(() -> {
      wg.interceptExpression("execution(* com.example.Service.*(..))")
          .before(inv -> {})
          .after(inv -> {})
          .onException(inv -> {})
          .afterFinally(inv -> {})
          .priority(10);
    });
  }

  @Test
  void expressionInterceptBuilder_withNullCallback() {
    WeaverGirl wg = WeaverGirl.create();

    // Should not throw at definition time (callback will fail at runtime if null)
    assertDoesNotThrow(() -> {
      wg.interceptExpression("execution(* com.example.Service.*(..))")
          .before(null);
    });
  }
}
