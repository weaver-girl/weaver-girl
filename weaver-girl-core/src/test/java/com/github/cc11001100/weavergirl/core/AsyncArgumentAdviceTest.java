package com.github.cc11001100.weavergirl.core;

import static org.junit.jupiter.api.Assertions.assertEquals;

import com.github.cc11001100.weavergirl.api.interceptor.Interceptor;
import com.github.cc11001100.weavergirl.api.interceptor.InterceptorDefinition;
import com.github.cc11001100.weavergirl.api.interceptor.MethodInvocation;
import com.github.cc11001100.weavergirl.api.matcher.ClassMatcher;
import com.github.cc11001100.weavergirl.api.matcher.MethodMatcher;
import com.github.cc11001100.weavergirl.api.pointcut.Pointcut;
import com.github.cc11001100.weavergirl.core.registry.DefaultInterceptorRegistry;
import java.lang.reflect.Method;
import java.util.concurrent.Callable;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class AsyncArgumentAdviceTest {

  private DefaultInterceptorRegistry registry;

  @BeforeEach
  void setUp() {
    registry = new DefaultInterceptorRegistry();
    InterceptorHolder.setRegistry(registry);
  }

  @AfterEach
  void tearDown() {
    InterceptorHolder.setRegistry(null);
  }

  @Test
  void onMethodEnterFiltersArgumentRewriteInterceptorsByFullSignature() throws Exception {
    AtomicInteger runnableResultInvocations = new AtomicInteger();
    AtomicInteger callableInvocations = new AtomicInteger();

    registry.register(
        argumentRewrite(
            "submit-runnable-result",
            MethodMatcher.bySignature("submit", "java.lang.Runnable,java.lang.Object"),
            new Interceptor() {
              @Override
              public void before(MethodInvocation invocation) {
                runnableResultInvocations.incrementAndGet();
              }
            }));
    registry.register(
        argumentRewrite(
            "submit-callable",
            MethodMatcher.bySignature("submit", "java.util.concurrent.Callable"),
            new Interceptor() {
              @Override
              public void before(MethodInvocation invocation) {
                callableInvocations.incrementAndGet();
              }
            }));

    Method method = SampleExecutor.class.getMethod("submit", Runnable.class, Object.class);

    Runnable task = () -> {};
    AsyncArgumentAdvice.onMethodEnter(
        SampleExecutor.class,
        method,
        new Object[] {task, "result"},
        task,
        "result",
        null,
        null,
        null,
        null,
        null,
        null);

    assertEquals(
        1,
        runnableResultInvocations.get(),
        "The matching submit(Runnable,Object) interceptor should run");
    assertEquals(
        0,
        callableInvocations.get(),
        "Same-name submit(Callable) interceptor must not run for a different signature");
  }

  @Test
  void onMethodEnterExposesFullArgumentListToInterceptors() throws Exception {
    AtomicInteger seenCount = new AtomicInteger();

    registry.register(
        argumentRewrite(
            "submit-observe",
            MethodMatcher.bySignature("submit", "java.lang.Runnable,java.lang.Object"),
            new Interceptor() {
              @Override
              public void before(MethodInvocation invocation) {
                seenCount.set(invocation.getArguments().length);
              }
            }));

    Method method = SampleExecutor.class.getMethod("submit", Runnable.class, Object.class);

    Runnable task = () -> {};
    AsyncArgumentAdvice.onMethodEnter(
        SampleExecutor.class,
        method,
        new Object[] {task, "result"},
        task,
        "result",
        null,
        null,
        null,
        null,
        null,
        null);

    assertEquals(
        2,
        seenCount.get(),
        "Interceptor should see the full argument list, not just the first argument");
  }

  @Test
  void onMethodEnterAllowsRewritingNonFirstArgument() throws Exception {
    AtomicInteger rewriteCount = new AtomicInteger();

    registry.register(
        argumentRewrite(
            "submit-rewrite-second",
            MethodMatcher.bySignature("submit", "java.lang.Runnable,java.lang.Object"),
            new Interceptor() {
              @Override
              public void before(MethodInvocation invocation) {
                invocation.setArgument(1, "wrapped-result");
                rewriteCount.incrementAndGet();
              }
            }));

    Method method = SampleExecutor.class.getMethod("submit", Runnable.class, Object.class);

    Runnable task = () -> {};
    // Direct call cannot observe parameter-slot writeback (locals are lost
    // on return); this verifies setArgument(1) flows through before()
    // without error. End-to-end writeback is covered by the integration test.
    AsyncArgumentAdvice.onMethodEnter(
        SampleExecutor.class,
        method,
        new Object[] {task, "result"},
        task,
        "result",
        null,
        null,
        null,
        null,
        null,
        null);

    assertEquals(1, rewriteCount.get(), "Interceptor rewriting the second argument should run");
  }

  private InterceptorDefinition argumentRewrite(
      String name, MethodMatcher matcher, Interceptor interceptor) {
    return new InterceptorDefinition(
        name,
        new Pointcut(ClassMatcher.byName(SampleExecutor.class.getName()), matcher),
        interceptor,
        0,
        InterceptorDefinition.AdviceMode.ARGUMENT_REWRITE);
  }

  static final class SampleExecutor {
    public void submit(Runnable task, Object result) {}

    public void submit(Callable<?> task) {}
  }
}
