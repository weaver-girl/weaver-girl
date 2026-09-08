package com.github.cc11001100.weavergirl.core;

import static org.junit.jupiter.api.Assertions.*;

import com.github.cc11001100.weavergirl.api.interceptor.Interceptor;
import com.github.cc11001100.weavergirl.api.interceptor.InterceptorDefinition;
import com.github.cc11001100.weavergirl.api.interceptor.MethodInvocation;
import com.github.cc11001100.weavergirl.api.matcher.ClassMatcher;
import com.github.cc11001100.weavergirl.api.matcher.MethodMatcher;
import com.github.cc11001100.weavergirl.api.pointcut.Pointcut;
import com.github.cc11001100.weavergirl.core.registry.DefaultInterceptorRegistry;
import com.github.cc11001100.weavergirl.core.sampling.SamplingController;
import java.lang.reflect.Constructor;
import org.junit.jupiter.api.*;

class ConstructorAdviceTest {
  static class Target {
    Target(String value) {}
  }

  private DefaultInterceptorRegistry registry;
  private Constructor<?> constructor;

  @BeforeEach
  void setUp() throws Exception {
    registry = new DefaultInterceptorRegistry();
    InterceptorHolder.setRegistry(registry);
    InterceptorHolder.setInterceptionEnabled(true, "test");
    SamplingController.getInstance().setSamplingRate(1);
    SamplingController.getInstance().resetCounter();
    constructor = Target.class.getDeclaredConstructor(String.class);
  }

  @AfterEach
  void tearDown() {
    InterceptorHolder.setRegistry(null);
    InterceptorHolder.setInterceptionEnabled(true, "test");
  }

  @Test
  void enterAndExit_invokeMatchingCallbacksAndClearSkip() {
    int[] calls = new int[2];
    Interceptor interceptor =
        new Interceptor() {
          public void before(MethodInvocation invocation) {
            calls[0]++;
            invocation.skipMethod();
          }

          public void after(MethodInvocation invocation) {
            calls[1]++;
            assertSame(Target.class, invocation.getTargetClass());
          }
        };
    registry.register(
        new InterceptorDefinition(
            "ctor-test", new Pointcut(ClassMatcher.byName(Target.class.getName()), MethodMatcher.byConstructor()), interceptor));
    MethodInvocation invocation =
        ConstructorAdvice.onMethodEnter(Target.class, constructor, null, new Object[] {"x"});
    assertNotNull(invocation);
    assertFalse(invocation.isSkipped());
    Target target = new Target("x");
    ConstructorAdvice.onMethodExit(invocation, Target.class, constructor, target, new Object[] {"x"});
    assertArrayEquals(new int[] {1, 1}, calls);
  }

  @Test
  void disabledOrUnsampled_enterReturnsNull() {
    InterceptorHolder.setInterceptionEnabled(false, "test");
    assertNull(ConstructorAdvice.onMethodEnter(Target.class, constructor, null, new Object[0]));
    InterceptorHolder.setInterceptionEnabled(true, "test");
    SamplingController.getInstance().setSamplingRate(2);
    assertNull(ConstructorAdvice.onMethodEnter(Target.class, constructor, null, new Object[0]));
    ConstructorAdvice.onMethodExit(null, Target.class, constructor, new Target("x"), new Object[0]);
  }

  @Test
  void callbackErrorsAreContainedAndNullRegistryIsSafe() {
    registry.register(
        new InterceptorDefinition(
            "ctor-error",
            new Pointcut(ClassMatcher.byName(Target.class.getName()), MethodMatcher.byConstructor()),
            new Interceptor() {
              public void before(MethodInvocation invocation) { throw new AssertionError("before"); }
              public void after(MethodInvocation invocation) { throw new AssertionError("after"); }
            }));
    MethodInvocation invocation =
        ConstructorAdvice.onMethodEnter(Target.class, constructor, null, new Object[] {"x"});
    assertNotNull(invocation);
    ConstructorAdvice.onMethodExit(invocation, Target.class, constructor, new Target("x"), new Object[] {"x"});
    InterceptorHolder.setRegistry(null);
    assertNull(ConstructorAdvice.onMethodEnter(Target.class, constructor, null, new Object[0]));
    ConstructorAdvice.onMethodExit(null, Target.class, constructor, null, new Object[0]);
  }
}
