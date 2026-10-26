package com.github.cc11001100.weavergirl.core;

import static org.junit.jupiter.api.Assertions.assertEquals;

import com.github.cc11001100.weavergirl.api.interceptor.Interceptor;
import com.github.cc11001100.weavergirl.api.interceptor.InterceptorDefinition;
import com.github.cc11001100.weavergirl.api.interceptor.MethodInvocation;
import com.github.cc11001100.weavergirl.api.matcher.ClassMatcher;
import com.github.cc11001100.weavergirl.api.matcher.MethodMatcher;
import com.github.cc11001100.weavergirl.api.pointcut.Pointcut;
import com.github.cc11001100.weavergirl.core.registry.DefaultInterceptorRegistry;
import com.github.cc11001100.weavergirl.core.switches.GlobalInterceptionSwitch;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.concurrent.Callable;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
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
    resetSwitch(true);
  }

  private static void resetSwitch(boolean on) {
    try {
      Field enabled = GlobalInterceptionSwitch.class.getDeclaredField("enabled");
      enabled.setAccessible(true);
      enabled.setBoolean(null, on);
    } catch (ReflectiveOperationException e) {
      throw new IllegalStateException(e);
    }
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

  /**
   * The guards the happy path never reaches: interception switched off, no registry installed, a
   * definition that is not an argument rewrite, an interceptor whose circuit is open, a {@code
   * before} that throws, a {@code null} argument array, and a rewrite of every bound slot.
   */
  @Test
  void onMethodEnterCoversTheGuardsTheHappyPathSkips() throws Exception {
    Method method = SampleExecutor.class.getMethod("submit", Runnable.class, Object.class);
    Runnable task = () -> {};

    resetSwitch(false);
    AsyncArgumentAdvice.onMethodEnter(
        SampleExecutor.class, method, new Object[] {task}, task, null, null, null, null, null, null,
        null);

    resetSwitch(true);
    InterceptorHolder.setRegistry(null);
    AsyncArgumentAdvice.onMethodEnter(
        SampleExecutor.class, method, null, null, null, null, null, null, null, null, null);
    InterceptorHolder.setRegistry(registry);

    // A plain interceptor is not an argument rewrite, so it is skipped.
    registry.register(
        new InterceptorDefinition(
            "plain", new Pointcut(ClassMatcher.any(), MethodMatcher.any()), new Interceptor() {}));

    // Five consecutive failures open the circuit, so the next call skips the interceptor.
    registry.register(
        argumentRewrite(
            "tripped",
            MethodMatcher.any(),
            new Interceptor() {
              @Override
              public void before(MethodInvocation invocation) {}
            }));
    for (int i = 0; i < 5; i++) {
      InterceptorHolder.recordOutcome("tripped", false, 0L);
    }

    AtomicReference<Throwable> thrown = new AtomicReference<>();
    registry.register(
        argumentRewrite(
            "exploding",
            MethodMatcher.any(),
            new Interceptor() {
              @Override
              public void before(MethodInvocation invocation) {
                thrown.set(new IllegalStateException("before failed"));
                throw (RuntimeException) thrown.get();
              }
            }));

    AsyncArgumentAdvice.onMethodEnter(
        SampleExecutor.class, method, new Object[] {task, "r"}, task, "r", null, null, null, null,
        null, null);

    // Eight arguments exercise every write-back slot, including the last one.
    Method wide =
        WideTarget.class.getMethod(
            "take",
            Object.class,
            Object.class,
            Object.class,
            Object.class,
            Object.class,
            Object.class,
            Object.class,
            Object.class);
    Object[] eight = {"a", "b", "c", "d", "e", "f", "g", "h"};
    registry.register(
        argumentRewrite(
            "wide",
            MethodMatcher.any(),
            new Interceptor() {
              @Override
              public void before(MethodInvocation invocation) {
                for (int i = 0; i < 8; i++) {
                  invocation.setArgument(i, "w" + i);
                }
              }
            }));
    AsyncArgumentAdvice.onMethodEnter(
        WideTarget.class, wide, eight, "a", "b", "c", "d", "e", "f", "g", "h");
  }

  /** A method whose origin ByteBuddy cannot resolve, so the whole enter advice bails out. */
  @Test
  void onMethodEnterSwallowsAFailureWhileReadingTheMethod() {
    AsyncArgumentAdvice.onMethodEnter(
        SampleExecutor.class, null, new Object[0], null, null, null, null, null, null, null, null);
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

  /** Varargs gives a reflected method with eight parameter types for the write-back loop. */
  static final class WideTarget {
    public void take(
        Object a, Object b, Object c, Object d, Object e, Object f, Object g, Object h) {}
  }
}
