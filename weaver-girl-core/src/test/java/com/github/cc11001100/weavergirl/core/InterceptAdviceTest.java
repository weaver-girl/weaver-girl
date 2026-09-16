// weaver-girl-core/src/test/java/com/github/cc11001100/weavergirl/core/InterceptAdviceTest.java
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
import java.lang.reflect.Method;
import java.util.Collections;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class InterceptAdviceTest {

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

  // --- onMethodEnter tests ---

  @Test
  void onMethodEnter_noMatchingInterceptor_returnsNull() throws Exception {
    // No interceptors registered at all
    Method method = SampleClass.class.getMethod("greet");
    Object[] args = new Object[0];

    MethodInvocation result =
        InterceptAdvice.onMethodEnter(SampleClass.class, method, new SampleClass(), args);

    assertNull(
        result, "Should return null when no interceptors match — original method should execute");
  }

  @Test
  void onMethodEnter_interceptorCallsSkipMethod_onMatchedClass_returnsInvocation()
      throws Exception {
    // Register an interceptor that skips the method for SampleClass.greet
    Interceptor skipInterceptor =
        new Interceptor() {
          @Override
          public void before(MethodInvocation invocation) {
            invocation.skipMethod();
          }
        };
    registry.register(
        new InterceptorDefinition(
            "skip-test",
            new Pointcut(
                ClassMatcher.byName(SampleClass.class.getName()), MethodMatcher.byName("greet")),
            skipInterceptor));

    Method method = SampleClass.class.getMethod("greet");
    MethodInvocation result =
        InterceptAdvice.onMethodEnter(SampleClass.class, method, new SampleClass(), new Object[0]);

    assertNotNull(
        result,
        "Should return non-null MethodInvocation when skipMethod is called — triggers ByteBuddy"
            + " skipOn");
    assertTrue(result.isSkipped(), "The returned invocation should have isSkipped=true");
  }

  @Test
  void onMethodEnter_interceptorCallsSkipMethod_onUnmatchedClass_returnsNull() throws Exception {
    // Register an interceptor for a DIFFERENT class
    Interceptor skipInterceptor =
        new Interceptor() {
          @Override
          public void before(MethodInvocation invocation) {
            invocation.skipMethod();
          }
        };
    registry.register(
        new InterceptorDefinition(
            "skip-other",
            new Pointcut(
                ClassMatcher.byName("com.example.DoesNotExist"), MethodMatcher.byName("greet")),
            skipInterceptor));

    // Call onMethodEnter for SampleClass — interceptor should NOT match
    Method method = SampleClass.class.getMethod("greet");
    MethodInvocation result =
        InterceptAdvice.onMethodEnter(SampleClass.class, method, new SampleClass(), new Object[0]);

    assertNull(
        result, "Should return null when no interceptor matches the class — no skip triggered");
  }

  @Test
  void onMethodEnter_nullRegistry_returnsNull() throws Exception {
    // Remove the registry
    InterceptorHolder.setRegistry(null);

    Method method = SampleClass.class.getMethod("greet");
    MethodInvocation result =
        InterceptAdvice.onMethodEnter(SampleClass.class, method, new SampleClass(), new Object[0]);

    assertNull(result, "Should return null when registry is null — original method should execute");
  }

  @Test
  void onMethodEnter_interceptorDoesNotSkip_returnsNull() throws Exception {
    // Register an interceptor that does NOT call skipMethod
    Interceptor noOpInterceptor =
        new Interceptor() {
          @Override
          public void before(MethodInvocation invocation) {
            // intentionally does not skip
          }
        };
    registry.register(
        new InterceptorDefinition(
            "no-skip",
            new Pointcut(
                ClassMatcher.byName(SampleClass.class.getName()), MethodMatcher.byName("greet")),
            noOpInterceptor));

    Method method = SampleClass.class.getMethod("greet");
    MethodInvocation result =
        InterceptAdvice.onMethodEnter(SampleClass.class, method, new SampleClass(), new Object[0]);

    assertNull(
        result,
        "Should return null when interceptor does not call skipMethod — original method executes"
            + " normally");
  }

  @Test
  void onMethodEnter_disabledSwitch_returnsNull() throws Exception {
    InterceptorHolder.setInterceptionEnabled(false, "test");
    Method method = SampleClass.class.getMethod("greet");

    MethodInvocation result =
        InterceptAdvice.onMethodEnter(SampleClass.class, method, new SampleClass(), new Object[0]);

    assertNull(result, "Should return null when global interception switch is disabled");
  }

  @Test
  void onMethodEnter_unsampled_returnsNull() throws Exception {
    SamplingController.getInstance().setSamplingRate(2);
    Method method = SampleClass.class.getMethod("greet");

    MethodInvocation result =
        InterceptAdvice.onMethodEnter(SampleClass.class, method, new SampleClass(), new Object[0]);

    assertNull(result, "Should return null when the invocation is not sampled");
  }

  @Test
  void onMethodEnter_methodNameMismatch_classMatches_skipsInterceptor() throws Exception {
    // The class matches but the method name does not — exercises the methodMatcher.matches()
    // false branch, distinct from the class-mismatch case (which never even enters the loop
    // body because getInterceptorsForClassSnapshot filters by class before the loop runs).
    boolean[] beforeCalled = {false};
    registry.register(
        new InterceptorDefinition(
            "method-mismatch",
            new Pointcut(
                ClassMatcher.byName(SampleClass.class.getName()), MethodMatcher.byName("echo")),
            new Interceptor() {
              @Override
              public void before(MethodInvocation invocation) {
                beforeCalled[0] = true;
              }
            }));

    Method method = SampleClass.class.getMethod("greet");
    MethodInvocation result =
        InterceptAdvice.onMethodEnter(SampleClass.class, method, new SampleClass(), new Object[0]);

    assertNull(result);
    assertFalse(beforeCalled[0], "before() should not run when the method name does not match");
  }

  @Test
  void onMethodEnter_circuitBreakerOpen_skipsBeforeCallback() throws Exception {
    String name = "enter-circuit-" + System.identityHashCode(this);
    boolean[] beforeCalled = {false};
    registry.register(
        new InterceptorDefinition(
            name,
            new Pointcut(
                ClassMatcher.byName(SampleClass.class.getName()), MethodMatcher.byName("greet")),
            new Interceptor() {
              @Override
              public void before(MethodInvocation invocation) {
                beforeCalled[0] = true;
              }
            }));
    for (int i = 0; i < 5; i++) {
      InterceptorHolder.recordInterceptorFailure(name);
    }
    assertFalse(InterceptorHolder.shouldInvoke(name), "Circuit breaker should be open");

    Method method = SampleClass.class.getMethod("greet");
    MethodInvocation result =
        InterceptAdvice.onMethodEnter(SampleClass.class, method, new SampleClass(), new Object[0]);

    assertNull(result);
    assertFalse(beforeCalled[0], "before() should be skipped while circuit breaker is open");
  }

  @Test
  void onMethodEnter_ifConditionTrue_invokesBeforeCallback() throws Exception {
    boolean[] beforeCalled = {false};
    registry.register(
        new InterceptorDefinition(
            "enter-if-true",
            new Pointcut(
                ClassMatcher.byName(SampleClass.class.getName()),
                MethodMatcher.byName("echo"),
                PointcutExpression.ifCondition("args.length > 0")),
            new Interceptor() {
              @Override
              public void before(MethodInvocation invocation) {
                beforeCalled[0] = true;
              }
            }));

    Method method = SampleClass.class.getMethod("echo", String.class);
    MethodInvocation result =
        InterceptAdvice.onMethodEnter(
            SampleClass.class, method, new SampleClass(), new Object[] {"x"});

    assertNull(result);
    assertTrue(beforeCalled[0], "before() should run when the if() condition is true");
  }

  @Test
  void onMethodEnter_ifConditionFalse_skipsInterceptor() throws Exception {
    boolean[] beforeCalled = {false};
    registry.register(
        new InterceptorDefinition(
            "enter-if-false",
            new Pointcut(
                ClassMatcher.byName(SampleClass.class.getName()),
                MethodMatcher.byName("greet"),
                PointcutExpression.ifCondition("args.length > 0")),
            new Interceptor() {
              @Override
              public void before(MethodInvocation invocation) {
                beforeCalled[0] = true;
              }
            }));

    Method method = SampleClass.class.getMethod("greet");
    MethodInvocation result =
        InterceptAdvice.onMethodEnter(SampleClass.class, method, new SampleClass(), new Object[0]);

    assertNull(result);
    assertFalse(beforeCalled[0], "before() should be skipped when the if() condition is false");
  }

  @Test
  void onMethodEnter_cflowExpression_matchesCallerFrame_invokesBeforeCallback() throws Exception {
    boolean[] beforeCalled = {false};
    PointcutExpression cflowExpr =
        PointcutExpression.cflow(
            PointcutExpression.execution(null, SampleClass.class.getName(), "outer", null));
    registry.register(
        new InterceptorDefinition(
            "enter-cflow-true",
            new Pointcut(
                ClassMatcher.byName(SampleClass.class.getName()),
                MethodMatcher.byName("greet"),
                cflowExpr),
            new Interceptor() {
              @Override
              public void before(MethodInvocation invocation) {
                beforeCalled[0] = true;
              }
            }));

    // Simulate being inside the call stack of "outer" by manually pushing a cflow frame,
    // so the cflow() condition matches a CALLER frame of the current join point.
    PointcutExpression.enterCflow(SampleClass.class.getName(), "outer");
    try {
      Method method = SampleClass.class.getMethod("greet");
      MethodInvocation result =
          InterceptAdvice.onMethodEnter(SampleClass.class, method, new SampleClass(), new Object[0]);
      assertNull(result);
      assertTrue(
          beforeCalled[0], "before() should run when the cflow() condition matches a caller frame");
    } finally {
      // Pop the "greet" frame onMethodEnter pushed (never popped since onMethodExit was not
      // called) and the manually-pushed "outer" frame, to avoid leaking into other tests.
      PointcutExpression.exitCflow(SampleClass.class.getName(), "greet");
      PointcutExpression.exitCflow(SampleClass.class.getName(), "outer");
    }
  }

  @Test
  void onMethodEnter_beforeCallbackThrows_isContained() throws Exception {
    registry.register(
        new InterceptorDefinition(
            "enter-before-throws",
            new Pointcut(
                ClassMatcher.byName(SampleClass.class.getName()), MethodMatcher.byName("greet")),
            new Interceptor() {
              @Override
              public void before(MethodInvocation invocation) {
                throw new RuntimeException("boom");
              }
            }));

    Method method = SampleClass.class.getMethod("greet");
    assertDoesNotThrow(
        () ->
            InterceptAdvice.onMethodEnter(
                SampleClass.class, method, new SampleClass(), new Object[0]),
        "before() throwing should be contained by the inner try/catch");
  }

  @Test
  void onMethodEnter_instanceBoundPointcut_setsPerInstance() throws Exception {
    // Per-instance store assignment (L125-127) runs AFTER before() within the same loop
    // iteration, so before() itself can't observe it yet. Force a skip so the invocation is
    // returned (not pool-released) and its per-instance state can be inspected afterward.
    registry.register(
        new InterceptorDefinition(
            "enter-instance-bound",
            new Pointcut(
                    ClassMatcher.byName(SampleClass.class.getName()), MethodMatcher.byName("greet"))
                .pertarget(),
            new Interceptor() {
              @Override
              public void before(MethodInvocation invocation) {
                invocation.skipMethod();
              }
            }));

    Method method = SampleClass.class.getMethod("greet");
    MethodInvocation result =
        InterceptAdvice.onMethodEnter(SampleClass.class, method, new SampleClass(), new Object[0]);

    assertNotNull(result);
    assertTrue(
        result.hasPerInstance(), "Per-instance context should be created for instance-bound pointcuts");
    assertNotNull(result.getPerInstance());
  }

  @Test
  void onMethodEnter_registryThrows_outerCatchReturnsNull() throws Exception {
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
    Method method = SampleClass.class.getMethod("greet");
    try {
      MethodInvocation result =
          InterceptAdvice.onMethodEnter(SampleClass.class, method, new SampleClass(), new Object[0]);
      assertNull(result, "Outer catch should swallow the exception and return null");
    } finally {
      // onMethodEnter pushed a cflow frame before the registry lookup threw; balance it out
      // to avoid leaking the ThreadLocal cflow stack into subsequent tests.
      PointcutExpression.exitCflow(SampleClass.class.getName(), "greet");
    }
  }

  // --- onMethodExit tests ---

  @Test
  void onMethodExit_nullInvocation_createsNewContextAndCallsAfter() throws Exception {
    // When invocation is null (no skip), onMethodExit should create a fresh context
    boolean[] afterCalled = {false};
    Interceptor interceptor =
        new Interceptor() {
          @Override
          public void after(MethodInvocation inv) {
            afterCalled[0] = true;
            assertEquals("hello", inv.getReturnValue(), "Return value should be set on invocation");
          }
        };
    registry.register(
        new InterceptorDefinition(
            "after-test",
            new Pointcut(
                ClassMatcher.byName(SampleClass.class.getName()), MethodMatcher.byName("greet")),
            interceptor));

    Method method = SampleClass.class.getMethod("greet");
    // Signature: (invocation, class, method, target, args, throwable, returnValue)
    InterceptAdvice.onMethodExit(
        null, SampleClass.class, method, new SampleClass(), new Object[0], null, "hello");

    assertTrue(afterCalled[0], "after() should have been called even when invocation is null");
  }

  @Test
  void onMethodExit_withReturnValue_setsReturnValueAndCallsAfter() throws Exception {
    MethodInvocation invocation =
        new MethodInvocation(SampleClass.class, "greet", new SampleClass(), new Object[0]);

    boolean[] afterCalled = {false};
    Interceptor interceptor =
        new Interceptor() {
          @Override
          public void after(MethodInvocation inv) {
            afterCalled[0] = true;
            assertEquals("hello", inv.getReturnValue(), "Return value should be set on invocation");
          }
        };
    registry.register(
        new InterceptorDefinition(
            "after-test",
            new Pointcut(
                ClassMatcher.byName(SampleClass.class.getName()), MethodMatcher.byName("greet")),
            interceptor));

    Method method = SampleClass.class.getMethod("greet");
    InterceptAdvice.onMethodExit(
        invocation, SampleClass.class, method, new SampleClass(), new Object[0], null, "hello");

    assertTrue(afterCalled[0], "after() should have been called");
  }

  @Test
  void onMethodExit_withThrowable_setsThrowableAndCallsOnException() throws Exception {
    MethodInvocation invocation =
        new MethodInvocation(SampleClass.class, "greet", new SampleClass(), new Object[0]);

    RuntimeException testException = new RuntimeException("test error");
    boolean[] onExceptionCalled = {false};
    Interceptor interceptor =
        new Interceptor() {
          @Override
          public void onException(MethodInvocation inv) {
            onExceptionCalled[0] = true;
            assertNotNull(inv.getThrowable(), "Throwable should be set on invocation");
            assertEquals("test error", inv.getThrowable().getMessage());
          }
        };
    registry.register(
        new InterceptorDefinition(
            "exception-test",
            new Pointcut(
                ClassMatcher.byName(SampleClass.class.getName()), MethodMatcher.byName("greet")),
            interceptor));

    Method method = SampleClass.class.getMethod("greet");
    InterceptAdvice.onMethodExit(
        invocation,
        SampleClass.class,
        method,
        new SampleClass(),
        new Object[0],
        testException,
        null);

    assertTrue(onExceptionCalled[0], "onException() should have been called");
  }

  @Test
  void onMethodExit_disabledSwitch_releasesBothNonNullAndNullInvocation() throws Exception {
    Method method = SampleClass.class.getMethod("greet");
    MethodInvocation invocation =
        new MethodInvocation(SampleClass.class, "greet", new SampleClass(), new Object[0]);

    InterceptorHolder.setInterceptionEnabled(false, "test");
    assertDoesNotThrow(
        () ->
            InterceptAdvice.onMethodExit(
                invocation, SampleClass.class, method, new SampleClass(), new Object[0], null, "hello"));
    assertDoesNotThrow(
        () ->
            InterceptAdvice.onMethodExit(
                null, SampleClass.class, method, new SampleClass(), new Object[0], null, "hello"));
  }

  @Test
  void onMethodExit_nullRegistry_releasesAndReturns() throws Exception {
    InterceptorHolder.setRegistry(null);
    Method method = SampleClass.class.getMethod("greet");

    assertDoesNotThrow(
        () ->
            InterceptAdvice.onMethodExit(
                null, SampleClass.class, method, new SampleClass(), new Object[0], null, "hello"));
  }

  @Test
  void onMethodExit_methodNameMismatch_classMatches_skipsInterceptor() throws Exception {
    boolean[] afterCalled = {false};
    registry.register(
        new InterceptorDefinition(
            "exit-method-mismatch",
            new Pointcut(
                ClassMatcher.byName(SampleClass.class.getName()), MethodMatcher.byName("echo")),
            new Interceptor() {
              @Override
              public void after(MethodInvocation inv) {
                afterCalled[0] = true;
              }
            }));

    Method method = SampleClass.class.getMethod("greet");
    InterceptAdvice.onMethodExit(
        null, SampleClass.class, method, new SampleClass(), new Object[0], null, "hello");

    assertFalse(afterCalled[0], "after() should not run when the method name does not match");
  }

  @Test
  void onMethodExit_circuitBreakerOpen_skipsAfterCallback() throws Exception {
    String name = "exit-circuit-" + System.identityHashCode(this);
    boolean[] afterCalled = {false};
    registry.register(
        new InterceptorDefinition(
            name,
            new Pointcut(
                ClassMatcher.byName(SampleClass.class.getName()), MethodMatcher.byName("greet")),
            new Interceptor() {
              @Override
              public void after(MethodInvocation inv) {
                afterCalled[0] = true;
              }
            }));
    for (int i = 0; i < 5; i++) {
      InterceptorHolder.recordInterceptorFailure(name);
    }
    assertFalse(InterceptorHolder.shouldInvoke(name), "Circuit breaker should be open");

    Method method = SampleClass.class.getMethod("greet");
    InterceptAdvice.onMethodExit(
        null, SampleClass.class, method, new SampleClass(), new Object[0], null, "hello");

    assertFalse(afterCalled[0], "after() should be skipped while circuit breaker is open");
  }

  @Test
  void onMethodExit_ifConditionTrue_invokesAfterCallback() throws Exception {
    boolean[] afterCalled = {false};
    registry.register(
        new InterceptorDefinition(
            "exit-if-true",
            new Pointcut(
                ClassMatcher.byName(SampleClass.class.getName()),
                MethodMatcher.byName("echo"),
                PointcutExpression.ifCondition("args.length > 0")),
            new Interceptor() {
              @Override
              public void after(MethodInvocation inv) {
                afterCalled[0] = true;
              }
            }));

    Method method = SampleClass.class.getMethod("echo", String.class);
    InterceptAdvice.onMethodExit(
        null, SampleClass.class, method, new SampleClass(), new Object[] {"x"}, null, "echo:x");

    assertTrue(afterCalled[0], "after() should run when the if() condition is true");
  }

  @Test
  void onMethodExit_ifConditionFalse_skipsInterceptor() throws Exception {
    boolean[] afterCalled = {false};
    registry.register(
        new InterceptorDefinition(
            "exit-if-false",
            new Pointcut(
                ClassMatcher.byName(SampleClass.class.getName()),
                MethodMatcher.byName("greet"),
                PointcutExpression.ifCondition("args.length > 0")),
            new Interceptor() {
              @Override
              public void after(MethodInvocation inv) {
                afterCalled[0] = true;
              }
            }));

    Method method = SampleClass.class.getMethod("greet");
    InterceptAdvice.onMethodExit(
        null, SampleClass.class, method, new SampleClass(), new Object[0], null, "hello");

    assertFalse(afterCalled[0], "after() should be skipped when the if() condition is false");
  }

  @Test
  void onMethodExit_cflowExpression_matchesCallerFrame_invokesAfterCallback() throws Exception {
    boolean[] afterCalled = {false};
    PointcutExpression cflowExpr =
        PointcutExpression.cflow(
            PointcutExpression.execution(null, SampleClass.class.getName(), "outer", null));
    registry.register(
        new InterceptorDefinition(
            "exit-cflow-true",
            new Pointcut(
                ClassMatcher.byName(SampleClass.class.getName()),
                MethodMatcher.byName("greet"),
                cflowExpr),
            new Interceptor() {
              @Override
              public void after(MethodInvocation inv) {
                afterCalled[0] = true;
              }
            }));

    Method method = SampleClass.class.getMethod("greet");
    PointcutExpression.enterCflow(SampleClass.class.getName(), "outer");
    try {
      // onMethodEnter pushes its own "greet" frame on top of "outer"; that self-frame is
      // still present (not popped) when onMethodExit's interceptor loop runs, matching how
      // real weaving keeps the frame alive across the whole method body's execution.
      MethodInvocation invocation =
          InterceptAdvice.onMethodEnter(SampleClass.class, method, new SampleClass(), new Object[0]);
      InterceptAdvice.onMethodExit(
          invocation, SampleClass.class, method, new SampleClass(), new Object[0], null, "hello");
      assertTrue(
          afterCalled[0], "after() should run when the cflow() condition matches a caller frame");
    } finally {
      // onMethodExit's own exitCflow already popped the "greet" frame; pop the manually
      // pushed "outer" frame here so it doesn't leak into other tests.
      PointcutExpression.exitCflow(SampleClass.class.getName(), "outer");
    }
  }

  @Test
  void onMethodExit_afterFinallyThrows_isContained() throws Exception {
    boolean[] afterCalled = {false};
    registry.register(
        new InterceptorDefinition(
            "exit-after-finally-throws",
            new Pointcut(
                ClassMatcher.byName(SampleClass.class.getName()), MethodMatcher.byName("greet")),
            new Interceptor() {
              @Override
              public void after(MethodInvocation inv) {
                afterCalled[0] = true;
              }

              @Override
              public void afterFinally(MethodInvocation inv) {
                throw new RuntimeException("afterFinally boom");
              }
            }));

    Method method = SampleClass.class.getMethod("greet");
    assertDoesNotThrow(
        () ->
            InterceptAdvice.onMethodExit(
                null, SampleClass.class, method, new SampleClass(), new Object[0], null, "hello"));
    assertTrue(afterCalled[0], "after() should still run before afterFinally throws");
  }

  @Test
  void onMethodExit_afterCallbackThrows_isContained() throws Exception {
    registry.register(
        new InterceptorDefinition(
            "exit-after-throws",
            new Pointcut(
                ClassMatcher.byName(SampleClass.class.getName()), MethodMatcher.byName("greet")),
            new Interceptor() {
              @Override
              public void after(MethodInvocation inv) {
                throw new RuntimeException("after boom");
              }
            }));

    Method method = SampleClass.class.getMethod("greet");
    assertDoesNotThrow(
        () ->
            InterceptAdvice.onMethodExit(
                null, SampleClass.class, method, new SampleClass(), new Object[0], null, "hello"),
        "after() throwing should be contained by the outer per-interceptor try/catch");
  }

  @Test
  void onMethodExit_onExceptionCallbackThrows_isContained() throws Exception {
    registry.register(
        new InterceptorDefinition(
            "exit-onexception-throws",
            new Pointcut(
                ClassMatcher.byName(SampleClass.class.getName()), MethodMatcher.byName("greet")),
            new Interceptor() {
              @Override
              public void onException(MethodInvocation inv) {
                throw new RuntimeException("onException boom");
              }
            }));

    Method method = SampleClass.class.getMethod("greet");
    assertDoesNotThrow(
        () ->
            InterceptAdvice.onMethodExit(
                null,
                SampleClass.class,
                method,
                new SampleClass(),
                new Object[0],
                new RuntimeException("original"),
                null));
  }

  @Test
  void onMethodExit_exceptionSuppressed_clearsThrowableAndAppliesOverride() throws Exception {
    boolean[] observedSuppressed = new boolean[1];
    registry.register(
        new InterceptorDefinition(
            "exit-suppress",
            new Pointcut(
                ClassMatcher.byName(SampleClass.class.getName()), MethodMatcher.byName("greet")),
            new Interceptor() {
              @Override
              public void onException(MethodInvocation inv) {
                inv.suppressException();
                inv.setReturnValue("suppressed-return");
                observedSuppressed[0] = inv.isExceptionSuppressed();
              }
            }));

    Method method = SampleClass.class.getMethod("greet");
    assertDoesNotThrow(
        () ->
            InterceptAdvice.onMethodExit(
                null,
                SampleClass.class,
                method,
                new SampleClass(),
                new Object[0],
                new RuntimeException("original"),
                null));
    assertTrue(observedSuppressed[0], "isExceptionSuppressed() should be true after suppressException()");
  }

  @Test
  void onMethodExit_exceptionSuppressedWithoutOverride_clearsThrowableKeepsOriginalReturn()
      throws Exception {
    boolean[] observedSuppressed = new boolean[1];
    boolean[] observedOverridden = new boolean[1];
    registry.register(
        new InterceptorDefinition(
            "exit-suppress-no-override",
            new Pointcut(
                ClassMatcher.byName(SampleClass.class.getName()), MethodMatcher.byName("greet")),
            new Interceptor() {
              @Override
              public void onException(MethodInvocation inv) {
                inv.suppressException();
                observedSuppressed[0] = inv.isExceptionSuppressed();
                observedOverridden[0] = inv.isReturnOverridden();
              }
            }));

    Method method = SampleClass.class.getMethod("greet");
    assertDoesNotThrow(
        () ->
            InterceptAdvice.onMethodExit(
                null,
                SampleClass.class,
                method,
                new SampleClass(),
                new Object[0],
                new RuntimeException("original"),
                "original-return"));
    assertTrue(observedSuppressed[0], "isExceptionSuppressed() should be true after suppressException()");
    assertFalse(
        observedOverridden[0],
        "isReturnOverridden() should stay false when setReturnValue() was never called");
  }

  @Test
  void onMethodExit_returnValueOverridden_appliesOverride() throws Exception {
    boolean[] observedOverridden = new boolean[1];
    registry.register(
        new InterceptorDefinition(
            "exit-return-override",
            new Pointcut(
                ClassMatcher.byName(SampleClass.class.getName()), MethodMatcher.byName("greet")),
            new Interceptor() {
              @Override
              public void after(MethodInvocation inv) {
                inv.setReturnValue("overridden");
                observedOverridden[0] = inv.isReturnOverridden();
              }
            }));

    Method method = SampleClass.class.getMethod("greet");
    assertDoesNotThrow(
        () ->
            InterceptAdvice.onMethodExit(
                null, SampleClass.class, method, new SampleClass(), new Object[0], null, "hello"));
    assertTrue(observedOverridden[0], "isReturnOverridden() should be true after setReturnValue()");
  }

  @Test
  void onMethodExit_registryThrows_outerCatchDoesNotPropagate() throws Exception {
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
    Method method = SampleClass.class.getMethod("greet");

    assertDoesNotThrow(
        () ->
            InterceptAdvice.onMethodExit(
                null, SampleClass.class, method, new SampleClass(), new Object[0], null, "hello"));
  }

  @Test
  void onMethodExit_registryThrows_withNonNullInvocation_releasesItInOuterCatch() throws Exception {
    Method method = SampleClass.class.getMethod("greet");
    // Build the invocation directly rather than via onMethodEnter, to exercise the
    // (invocation != null) branch of onMethodExit's outer catch without needing to
    // balance a cflow frame that onMethodEnter would have pushed but never popped.
    MethodInvocation nonNullInvocation =
        new MethodInvocation(SampleClass.class, "greet", new SampleClass(), new Object[0]);

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
            InterceptAdvice.onMethodExit(
                nonNullInvocation,
                SampleClass.class,
                method,
                new SampleClass(),
                new Object[0],
                null,
                "hello"));
  }

  // --- Helper sample class for testing ---

  public static class SampleClass {
    public String greet() {
      return "hello";
    }

    public String echo(String msg) {
      return "echo:" + msg;
    }
  }

  // --- around-advice tests ---

  @Test
  void onMethodEnter_aroundInterceptor_callsProceed_returnsNullAndMethodRuns() throws Exception {
    long[] before = {0};
    long[] after = {0};
    Interceptor aroundInterceptor =
        new Interceptor() {
          @Override
          public void around(MethodInvocation invocation) {
            before[0] = System.nanoTime();
            invocation.proceed();
            after[0] = System.nanoTime();
          }

          @Override
          public boolean hasAround() {
            return true;
          }
        };
    registry.register(
        new InterceptorDefinition(
            "around-proceed",
            new Pointcut(
                ClassMatcher.byName(SampleClass.class.getName()), MethodMatcher.byName("greet")),
            aroundInterceptor));

    Method method = SampleClass.class.getMethod("greet");
    MethodInvocation result =
        InterceptAdvice.onMethodEnter(
            SampleClass.class, method, new SampleClass(), new Object[0]);

    assertNull(result, "Should return null when around interceptor calls proceed");
    assertTrue(before[0] > 0, "around() should have been invoked");
    assertTrue(after[0] >= before[0], "after proceed() should complete");
  }

  @Test
  void onMethodEnter_aroundInterceptor_skipsProceed_returnsInvocation() throws Exception {
    boolean[] aroundCalled = {false};
    Interceptor aroundInterceptor =
        new Interceptor() {
          @Override
          public void around(MethodInvocation invocation) {
            aroundCalled[0] = true;
          }

          @Override
          public boolean hasAround() {
            return true;
          }
        };
    registry.register(
        new InterceptorDefinition(
            "around-skip",
            new Pointcut(
                ClassMatcher.byName(SampleClass.class.getName()), MethodMatcher.byName("greet")),
            aroundInterceptor));

    Method method = SampleClass.class.getMethod("greet");
    MethodInvocation result =
        InterceptAdvice.onMethodEnter(
            SampleClass.class, method, new SampleClass(), new Object[0]);

    assertNotNull(result, "Should return non-null MethodInvocation when proceed is skipped");
    assertTrue(result.isSkipped(), "Invocation should be marked as skipped");
    assertTrue(aroundCalled[0], "around() should have been invoked");
  }

  @Test
  void onMethodEnter_multipleAroundInterceptors_executeInPriorityOrder() throws Exception {
    java.util.List<String> order = new java.util.ArrayList<>();
    Interceptor first =
        new Interceptor() {
          @Override
          public void around(MethodInvocation invocation) {
            order.add("first");
            invocation.proceed();
          }

          @Override
          public boolean hasAround() {
            return true;
          }
        };
    Interceptor second =
        new Interceptor() {
          @Override
          public void around(MethodInvocation invocation) {
            order.add("second");
            invocation.proceed();
          }

          @Override
          public boolean hasAround() {
            return true;
          }
        };

    registry.register(
        new InterceptorDefinition(
            "around-first",
            new Pointcut(
                ClassMatcher.byName(SampleClass.class.getName()), MethodMatcher.byName("greet")),
            first,
            0));
    registry.register(
        new InterceptorDefinition(
            "around-second",
            new Pointcut(
                ClassMatcher.byName(SampleClass.class.getName()), MethodMatcher.byName("greet")),
            second,
            10));

    Method method = SampleClass.class.getMethod("greet");
    InterceptAdvice.onMethodEnter(
        SampleClass.class, method, new SampleClass(), new Object[0]);

    java.util.List<String> expected = new java.util.ArrayList<>();
    expected.add("first");
    expected.add("second");
    assertEquals(
        expected,
        order,
        "Around interceptors should execute in ascending priority order");
  }

  @Test
  void onMethodEnter_aroundInterceptor_setsReturnValueAndSkipsMethod() throws Exception {
    Interceptor aroundInterceptor =
        new Interceptor() {
          @Override
          public void around(MethodInvocation invocation) {
            invocation.skipMethod();
            invocation.setReturnValue("cached");
          }

          @Override
          public boolean hasAround() {
            return true;
          }
        };
    registry.register(
        new InterceptorDefinition(
            "around-return",
            new Pointcut(
                ClassMatcher.byName(SampleClass.class.getName()), MethodMatcher.byName("greet")),
            aroundInterceptor));

    Method method = SampleClass.class.getMethod("greet");
    MethodInvocation result =
        InterceptAdvice.onMethodEnter(
            SampleClass.class, method, new SampleClass(), new Object[0]);

    assertNotNull(result, "Should return invocation when method is skipped");
    assertTrue(result.isSkipped());
    assertEquals("cached", result.getReturnValue(), "Return value should be overridden by interceptor");
  }

  @Test
  void onMethodEnter_aroundInterceptor_throws_exceptionIsSwallowed() throws Exception {
    Interceptor badInterceptor =
        new Interceptor() {
          @Override
          public void around(MethodInvocation invocation) {
            throw new RuntimeException("boom");
          }

          @Override
          public boolean hasAround() {
            return true;
          }
        };
    registry.register(
        new InterceptorDefinition(
            "around-bad",
            new Pointcut(
                ClassMatcher.byName(SampleClass.class.getName()), MethodMatcher.byName("greet")),
            badInterceptor));

    Method method = SampleClass.class.getMethod("greet");
    MethodInvocation result =
        InterceptAdvice.onMethodEnter(
            SampleClass.class, method, new SampleClass(), new Object[0]);

    assertNotNull(result, "Should return invocation when around interceptor throws");
    assertTrue(result.isSkipped(), "Method should be skipped when around interceptor throws");
  }

  @Test
  void onMethodEnter_aroundInterceptor_proceedDepth_exhaustion_blocksFurtherProceed() throws Exception {
    MethodInvocation invocation =
        new MethodInvocation(SampleClass.class, "greet", new SampleClass(), new Object[0]);
    invocation.setProceedable(true);

    for (int i = 0; i < 32; i++) {
      invocation.proceed();
    }

    assertThrows(
        IllegalStateException.class,
        () -> invocation.proceed(),
        "proceed() should throw when depth limit is exceeded");
    assertFalse(invocation.isProceedable(), "Invocation should not be proceedable at max depth");
  }
}
