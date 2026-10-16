package com.github.cc11001100.weavergirl.core;

import static org.junit.jupiter.api.Assertions.*;

import com.github.cc11001100.weavergirl.api.interceptor.InterceptorDefinition;
import com.github.cc11001100.weavergirl.api.interceptor.MethodInvocation;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * Each builder stage wraps the interceptor registered by the stage before it. The wrapper only
 * forwards when there is something to forward to, so the chain has to be built in both orders and
 * then actually invoked.
 */
class WeaverGirlChainedCallbackTest {

  @Test
  void expressionCallbacksForwardToTheStageRegisteredBeforeThem() {
    WeaverGirl wg = WeaverGirl.create();
    List<String> seen = new ArrayList<>();

    wg.interceptExpression("execution(* com.example.Svc.run(..))")
        .before(inv -> seen.add("before"))
        .after(inv -> seen.add("after"))
        .onException(inv -> seen.add("onException"))
        .afterFinally(inv -> seen.add("afterFinally"))
        .install();

    InterceptorDefinition definition = wg.getRegistry().getAllDefinitions().get(0);
    MethodInvocation invocation = new MethodInvocation(getClass(), "run", null, null);
    definition.getInterceptor().before(invocation);
    definition.getInterceptor().after(invocation);
    definition.getInterceptor().onException(invocation);
    definition.getInterceptor().afterFinally(invocation);

    assertEquals(java.util.Arrays.asList("before", "after", "onException", "afterFinally"), seen);
  }

  @Test
  void expressionCallbacksForwardWhenTheChainIsBuiltInReverse() {
    WeaverGirl wg = WeaverGirl.create();
    List<String> seen = new ArrayList<>();

    wg.interceptExpression("execution(* com.example.Svc.run(..))")
        .afterFinally(inv -> seen.add("afterFinally"))
        .onException(inv -> seen.add("onException"))
        .after(inv -> seen.add("after"))
        .before(inv -> seen.add("before"))
        .install();

    InterceptorDefinition definition = wg.getRegistry().getAllDefinitions().get(0);
    MethodInvocation invocation = new MethodInvocation(getClass(), "run", null, null);
    definition.getInterceptor().before(invocation);
    definition.getInterceptor().after(invocation);
    definition.getInterceptor().onException(invocation);
    definition.getInterceptor().afterFinally(invocation);

    assertEquals(java.util.Arrays.asList("before", "after", "onException", "afterFinally"), seen);
  }

  /** Without a transformer there is nothing to retransform, and that has to be reported as such. */
  @Test
  void retransformDoesNothingBeforeTheAgentIsInstalled() {
    assertEquals(0, WeaverGirl.create().retransformLoadedClasses());
  }
}
