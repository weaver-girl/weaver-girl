package com.github.cc11001100.weavergirl.api.taint;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.github.cc11001100.weavergirl.api.interceptor.MethodInvocation;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/** The interceptor form of a sanitiser clears the value the hooked method vouched for. */
class TaintSanitizerInterceptorTest {

  @BeforeEach
  void open() {
    Taint.openScope();
  }

  @AfterEach
  void close() {
    Taint.closeScope();
  }

  @Test
  void returnValueModeClearsOnlyTheResult() {
    String input = new String("dirty");
    String output = new String("clean-output");
    Taint.tag(input, "http.parameter:q");
    Taint.tag(output, "http.parameter:q");

    MethodInvocation invocation = invocation(input, output);
    TaintSanitizerInterceptor.returnValue().after(invocation);

    assertFalse(Taint.isTainted(output), "the encoded result is trusted");
    assertTrue(Taint.hasSource(input, "http.parameter:q"), "the original input stays tainted");
  }

  @Test
  void argumentModeClearsTheInPlaceValue() {
    String input = new String("dirty");
    Taint.tag(input, "http.parameter:q");

    MethodInvocation invocation = invocation(input, null);
    TaintSanitizerInterceptor.argument(0).after(invocation);

    assertFalse(Taint.isTainted(input), "an in-place validator cleans its argument");
  }

  @Test
  void combinedModeClearsBothAndNullsAreHarmless() {
    String input = new String("dirty");
    String output = new String("clean");
    Taint.tag(input, "http.parameter:q");
    Taint.tag(output, "http.parameter:q");

    MethodInvocation invocation = invocation(input, output);
    TaintSanitizerInterceptor.returnValueAndArgument(0).after(invocation);

    assertFalse(Taint.isTainted(input));
    assertFalse(Taint.isTainted(output));

    TaintSanitizerInterceptor.argument(5).after(invocation);
    TaintSanitizerInterceptor.returnValue().after(null);
    System.out.println("IAST sanitiser interceptor cleared return value and argument");
  }

  private MethodInvocation invocation(Object argument, Object returnValue) {
    MethodInvocation invocation =
        new MethodInvocation(
            TaintSanitizerInterceptorTest.class,
            "sanitize",
            null,
            new Object[] {argument});
    invocation.setReturnValue(returnValue);
    return invocation;
  }
}
