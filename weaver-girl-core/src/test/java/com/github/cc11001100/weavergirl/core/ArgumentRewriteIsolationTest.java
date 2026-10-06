package com.github.cc11001100.weavergirl.core;

import static org.junit.jupiter.api.Assertions.*;

import com.github.cc11001100.weavergirl.api.interceptor.Interceptor;
import com.github.cc11001100.weavergirl.api.interceptor.InterceptorDefinition;
import com.github.cc11001100.weavergirl.api.interceptor.MethodInvocation;
import com.github.cc11001100.weavergirl.api.matcher.ClassMatcher;
import com.github.cc11001100.weavergirl.api.matcher.MethodMatcher;
import com.github.cc11001100.weavergirl.api.pointcut.Pointcut;
import com.github.cc11001100.weavergirl.api.pointcut.PointcutExpression;
import com.github.cc11001100.weavergirl.core.registry.DefaultInterceptorRegistry;
import com.github.cc11001100.weavergirl.core.sampling.SamplingController;
import java.lang.reflect.Method;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * Locks in the {@link InterceptorDefinition.AdviceMode#ARGUMENT_REWRITE} isolation in {@link
 * InterceptAdvice}: rewrite-mode definitions are dispatched exclusively by {@code
 * AsyncArgumentAdvice} on method entry. If {@code InterceptAdvice} also dispatched them, a {@code
 * setArgument} rewrite would run twice (once against a non-writable {@code @Advice.AllArguments}
 * copy, once against the writable slots) — and a signature-scoped rewrite could misfire on a
 * same-name overload, since {@code InterceptAdvice} matches entry by name only.
 */
class ArgumentRewriteIsolationTest {

  public static class Sample {
    public String greet(String name) {
      return "hello " + name;
    }
  }

  private DefaultInterceptorRegistry registry;

  @BeforeEach
  void setUp() {
    registry = new DefaultInterceptorRegistry();
    InterceptorHolder.setRegistry(registry);
    InterceptorHolder.setInterceptionEnabled(true, "test");
    SamplingController.getInstance().setSamplingRate(1);
    SamplingController.getInstance().resetCounter();
  }

  @AfterEach
  void tearDown() {
    InterceptorHolder.setRegistry(null);
    InterceptorHolder.setInterceptionEnabled(true, "test");
    SamplingController.getInstance().setSamplingRate(1);
  }

  private void registerRewrite(String name, Interceptor interceptor) {
    registry.register(
        new InterceptorDefinition(
            name,
            new Pointcut(
                ClassMatcher.byName(Sample.class.getName()), MethodMatcher.byName("greet")),
            interceptor,
            0,
            InterceptorDefinition.AdviceMode.ARGUMENT_REWRITE));
  }

  private void registerStandard(String name, Interceptor interceptor) {
    registry.register(
        new InterceptorDefinition(
            name,
            new Pointcut(
                ClassMatcher.byName(Sample.class.getName()), MethodMatcher.byName("greet")),
            interceptor));
  }

  @Test
  void onMethodEnter_skipsArgumentRewriteDefinitions() throws Exception {
    AtomicInteger rewriteCalls = new AtomicInteger();
    registerRewrite(
        "rewrite-enter",
        new Interceptor() {
          @Override
          public void before(MethodInvocation invocation) {
            rewriteCalls.incrementAndGet();
          }
        });

    Method method = Sample.class.getMethod("greet", String.class);
    try {
      Object result =
          InterceptAdvice.onMethodEnter(
              Sample.class, method, new Sample(), new Object[] {"world"});
      assertNull(result, "no STANDARD interceptor matched, so the method must proceed");
      assertEquals(
          0,
          rewriteCalls.get(),
          "ARGUMENT_REWRITE before() must not run via InterceptAdvice (AsyncArgumentAdvice owns it)");
    } finally {
      // onMethodEnter pushed a cflow frame that only onMethodExit would pop; balance it here
      // to avoid leaking the ThreadLocal cflow stack into subsequent tests.
      PointcutExpression.exitCflow(Sample.class.getName(), "greet");
    }
  }

  @Test
  void onMethodEnter_stillDispatchesStandardDefinitionsAlongsideRewrite() throws Exception {
    AtomicInteger standardCalls = new AtomicInteger();
    AtomicInteger rewriteCalls = new AtomicInteger();
    registerStandard(
        "standard-enter",
        new Interceptor() {
          @Override
          public void before(MethodInvocation invocation) {
            standardCalls.incrementAndGet();
          }
        });
    registerRewrite(
        "rewrite-enter",
        new Interceptor() {
          @Override
          public void before(MethodInvocation invocation) {
            rewriteCalls.incrementAndGet();
          }
        });

    Method method = Sample.class.getMethod("greet", String.class);
    try {
      InterceptAdvice.onMethodEnter(Sample.class, method, new Sample(), new Object[] {"world"});
      assertEquals(1, standardCalls.get(), "STANDARD interceptors must keep working");
      assertEquals(0, rewriteCalls.get(), "ARGUMENT_REWRITE interceptors must stay skipped");
    } finally {
      PointcutExpression.exitCflow(Sample.class.getName(), "greet");
    }
  }

  @Test
  void onMethodExit_skipsArgumentRewriteDefinitions() throws Exception {
    boolean[] fired = {false, false, false};
    registerRewrite(
        "rewrite-exit",
        new Interceptor() {
          @Override
          public void after(MethodInvocation invocation) {
            fired[0] = true;
          }

          @Override
          public void onException(MethodInvocation invocation) {
            fired[1] = true;
          }

          @Override
          public void afterFinally(MethodInvocation invocation) {
            fired[2] = true;
          }
        });

    Method method = Sample.class.getMethod("greet", String.class);
    // Success path: after/afterFinally must not fire for rewrite-mode definitions.
    InterceptAdvice.onMethodExit(
        null, Sample.class, method, new Sample(), new Object[] {"world"}, null, "hello world");
    assertArrayEquals(
        new boolean[] {false, false, false},
        fired,
        "ARGUMENT_REWRITE is before-only; after/afterFinally must not run via InterceptAdvice");

    // Exception path: onException/afterFinally must not fire either.
    RuntimeException failure = new RuntimeException("boom");
    InterceptAdvice.onMethodExit(
        null, Sample.class, method, new Sample(), new Object[] {"world"}, failure, null);
    assertArrayEquals(
        new boolean[] {false, false, false},
        fired,
        "ARGUMENT_REWRITE onException/afterFinally must not run via InterceptAdvice");
  }
}
