package com.github.cc11001100.weavergirl.core;

import static org.junit.jupiter.api.Assertions.*;

import com.github.cc11001100.weavergirl.api.interceptor.Interceptor;
import com.github.cc11001100.weavergirl.api.interceptor.InterceptorDefinition;
import com.github.cc11001100.weavergirl.api.interceptor.MethodInvocation;
import com.github.cc11001100.weavergirl.api.matcher.ClassMatcher;
import com.github.cc11001100.weavergirl.api.matcher.MethodMatcher;
import com.github.cc11001100.weavergirl.api.pointcut.Pointcut;
import com.github.cc11001100.weavergirl.api.pointcut.PointcutExpression;
import com.github.cc11001100.weavergirl.api.registry.InterceptorRegistry;
import com.github.cc11001100.weavergirl.core.registry.DefaultInterceptorRegistry;
import com.github.cc11001100.weavergirl.core.sampling.SamplingController;
import java.lang.reflect.Constructor;
import java.util.Collections;
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
    SamplingController.getInstance().setSamplingRate(1);
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

  @Test
  void circuitBreakerOpen_skipsBeforeCallback_inEnter() {
    String name = "ctor-adv-circuit-enter-" + System.identityHashCode(this);
    int[] beforeCalls = new int[1];
    registry.register(
        new InterceptorDefinition(
            name,
            new Pointcut(ClassMatcher.byName(Target.class.getName()), MethodMatcher.byConstructor()),
            new Interceptor() {
              public void before(MethodInvocation invocation) {
                beforeCalls[0]++;
              }
            }));
    for (int i = 0; i < 5; i++) {
      InterceptorHolder.recordInterceptorFailure(name);
    }
    assertFalse(InterceptorHolder.shouldInvoke(name), "Circuit breaker should be open");

    MethodInvocation invocation =
        ConstructorAdvice.onMethodEnter(Target.class, constructor, null, new Object[] {"x"});

    assertNotNull(invocation);
    assertEquals(0, beforeCalls[0], "before() should be skipped while circuit breaker is open");
  }

  @Test
  void circuitBreakerOpen_skipsAfterCallback_inExit() {
    String name = "ctor-adv-circuit-exit-" + System.identityHashCode(this);
    int[] afterCalls = new int[1];
    registry.register(
        new InterceptorDefinition(
            name,
            new Pointcut(ClassMatcher.byName(Target.class.getName()), MethodMatcher.byConstructor()),
            new Interceptor() {
              public void after(MethodInvocation invocation) {
                afterCalls[0]++;
              }
            }));
    for (int i = 0; i < 5; i++) {
      InterceptorHolder.recordInterceptorFailure(name);
    }
    assertFalse(InterceptorHolder.shouldInvoke(name), "Circuit breaker should be open");

    ConstructorAdvice.onMethodExit(
        null, Target.class, constructor, new Target("x"), new Object[] {"x"});

    assertEquals(0, afterCalls[0], "after() should be skipped while circuit breaker is open");
  }

  @Test
  void ifExpression_conditionTrue_invokesBeforeCallback() {
    int[] beforeCalls = new int[1];
    registry.register(
        new InterceptorDefinition(
            "ctor-if-true",
            new Pointcut(
                ClassMatcher.byName(Target.class.getName()),
                MethodMatcher.byConstructor(),
                PointcutExpression.ifCondition("args.length > 0")),
            new Interceptor() {
              public void before(MethodInvocation invocation) {
                beforeCalls[0]++;
              }
            }));

    MethodInvocation invocation =
        ConstructorAdvice.onMethodEnter(Target.class, constructor, null, new Object[] {"x"});

    assertNotNull(invocation);
    assertEquals(1, beforeCalls[0], "before() should run when the if() condition is true");
  }

  @Test
  void ifExpression_conditionFalse_skipsInterceptor() {
    int[] beforeCalls = new int[1];
    registry.register(
        new InterceptorDefinition(
            "ctor-if-false",
            new Pointcut(
                ClassMatcher.byName(Target.class.getName()),
                MethodMatcher.byConstructor(),
                PointcutExpression.ifCondition("args.length > 5")),
            new Interceptor() {
              public void before(MethodInvocation invocation) {
                beforeCalls[0]++;
              }
            }));

    MethodInvocation invocation =
        ConstructorAdvice.onMethodEnter(Target.class, constructor, null, new Object[] {"x"});

    assertNotNull(invocation);
    assertEquals(0, beforeCalls[0], "before() should be skipped when the if() condition is false");
  }

  @Test
  void disabledSwitch_onExit_releasesBothNonNullAndNullInvocation() {
    MethodInvocation invocation =
        ConstructorAdvice.onMethodEnter(Target.class, constructor, null, new Object[] {"x"});
    assertNotNull(invocation);

    InterceptorHolder.setInterceptionEnabled(false, "test");
    assertDoesNotThrow(
        () ->
            ConstructorAdvice.onMethodExit(
                invocation, Target.class, constructor, new Target("x"), new Object[] {"x"}));
    assertDoesNotThrow(
        () ->
            ConstructorAdvice.onMethodExit(
                null, Target.class, constructor, new Target("x"), new Object[] {"x"}));
  }

  @Test
  void onMethodExit_targetAlreadySet_doesNotOverwrite() {
    Target original = new Target("x");
    MethodInvocation invocation =
        new MethodInvocation(Target.class, "<init>", null, new Object[] {"x"});
    invocation.setTarget(original);

    Object[] observedTarget = new Object[1];
    registry.register(
        new InterceptorDefinition(
            "ctor-target-preset",
            new Pointcut(ClassMatcher.byName(Target.class.getName()), MethodMatcher.byConstructor()),
            new Interceptor() {
              public void after(MethodInvocation inv) {
                observedTarget[0] = inv.getTarget();
              }
            }));

    Target another = new Target("y");
    ConstructorAdvice.onMethodExit(invocation, Target.class, constructor, another, new Object[] {"x"});

    assertSame(original, observedTarget[0], "Existing target should not be overwritten");
  }

  @Test
  void onMethodExit_nullTarget_doesNotSetTarget() {
    MethodInvocation invocation =
        new MethodInvocation(Target.class, "<init>", null, new Object[] {"x"});

    Object[] observedTarget = new Object[] {"unset"};
    registry.register(
        new InterceptorDefinition(
            "ctor-target-null",
            new Pointcut(ClassMatcher.byName(Target.class.getName()), MethodMatcher.byConstructor()),
            new Interceptor() {
              public void after(MethodInvocation inv) {
                observedTarget[0] = inv.getTarget();
              }
            }));

    ConstructorAdvice.onMethodExit(invocation, Target.class, constructor, null, new Object[] {"x"});

    assertNull(observedTarget[0]);
  }

  @Test
  void registryThrows_onMethodEnter_outerCatchReturnsNull() {
    InterceptorRegistry throwing =
        new InterceptorRegistry() {
          public void register(InterceptorDefinition definition) {}

          public boolean unregister(String name) {
            return false;
          }

          public java.util.List<InterceptorDefinition> getInterceptorsForClass(String className) {
            throw new RuntimeException("boom");
          }

          public java.util.List<InterceptorDefinition> getAllDefinitions() {
            return Collections.emptyList();
          }
        };
    InterceptorHolder.setRegistry(throwing);
    try {
      MethodInvocation result =
          ConstructorAdvice.onMethodEnter(Target.class, constructor, null, new Object[] {"x"});
      assertNull(result, "Outer catch should swallow the exception and return null");
    } finally {
      // onMethodEnter pushed a cflow frame before the registry lookup threw; balance it out
      // to avoid leaking the ThreadLocal cflow stack into subsequent tests.
      PointcutExpression.exitCflow(Target.class.getName(), "<init>");
    }
  }

  @Test
  void registryThrows_onMethodExit_outerCatchDoesNotPropagate() {
    InterceptorRegistry throwing =
        new InterceptorRegistry() {
          public void register(InterceptorDefinition definition) {}

          public boolean unregister(String name) {
            return false;
          }

          public java.util.List<InterceptorDefinition> getInterceptorsForClass(String className) {
            throw new RuntimeException("boom");
          }

          public java.util.List<InterceptorDefinition> getAllDefinitions() {
            return Collections.emptyList();
          }
        };
    InterceptorHolder.setRegistry(throwing);

    assertDoesNotThrow(
        () ->
            ConstructorAdvice.onMethodExit(
                null, Target.class, constructor, new Target("x"), new Object[] {"x"}));
  }

  @Test
  void registryThrows_onMethodExit_withNonNullInvocation_releasesItInOuterCatch() {
    MethodInvocation invocation =
        ConstructorAdvice.onMethodEnter(Target.class, constructor, null, new Object[] {"x"});
    assertNotNull(invocation);

    InterceptorRegistry throwing =
        new InterceptorRegistry() {
          public void register(InterceptorDefinition definition) {}

          public boolean unregister(String name) {
            return false;
          }

          public java.util.List<InterceptorDefinition> getInterceptorsForClass(String className) {
            throw new RuntimeException("boom");
          }

          public java.util.List<InterceptorDefinition> getAllDefinitions() {
            return Collections.emptyList();
          }
        };
    InterceptorHolder.setRegistry(throwing);

    assertDoesNotThrow(
        () ->
            ConstructorAdvice.onMethodExit(
                invocation, Target.class, constructor, new Target("x"), new Object[] {"x"}));
  }
}
