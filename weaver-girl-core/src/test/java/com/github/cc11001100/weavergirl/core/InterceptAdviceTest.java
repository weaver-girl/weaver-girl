// weaver-girl-core/src/test/java/com/github/cc11001100/weavergirl/core/InterceptAdviceTest.java
package com.github.cc11001100.weavergirl.core;

import static org.junit.jupiter.api.Assertions.*;

import com.github.cc11001100.weavergirl.api.interceptor.Interceptor;
import com.github.cc11001100.weavergirl.api.interceptor.InterceptorDefinition;
import com.github.cc11001100.weavergirl.api.interceptor.MethodInvocation;
import com.github.cc11001100.weavergirl.api.matcher.ClassMatcher;
import com.github.cc11001100.weavergirl.api.matcher.MethodMatcher;
import com.github.cc11001100.weavergirl.api.pointcut.Pointcut;
import com.github.cc11001100.weavergirl.core.registry.DefaultInterceptorRegistry;
import java.lang.reflect.Method;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class InterceptAdviceTest {

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
