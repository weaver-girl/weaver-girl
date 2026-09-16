package com.github.cc11001100.weavergirl.core;

import static org.junit.jupiter.api.Assertions.*;

import com.github.cc11001100.weavergirl.api.interceptor.CatchInterceptor;
import com.github.cc11001100.weavergirl.api.interceptor.CatchInvocation;
import com.github.cc11001100.weavergirl.api.interceptor.Interceptor;
import com.github.cc11001100.weavergirl.api.interceptor.InterceptorDefinition;
import com.github.cc11001100.weavergirl.api.matcher.ClassMatcher;
import com.github.cc11001100.weavergirl.api.matcher.MethodMatcher;
import com.github.cc11001100.weavergirl.api.pointcut.CatchPointcut;
import com.github.cc11001100.weavergirl.api.pointcut.Pointcut;
import com.github.cc11001100.weavergirl.core.interceptor.CatchInvocationPool;
import com.github.cc11001100.weavergirl.core.registry.DefaultInterceptorRegistry;
import com.github.cc11001100.weavergirl.core.sampling.SamplingController;
import com.github.cc11001100.weavergirl.core.switches.GlobalInterceptionSwitch;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class CatchAdviceTest {

  private DefaultInterceptorRegistry registry;

  @BeforeEach
  void setUp() {
    registry = new DefaultInterceptorRegistry();
    InterceptorHolder.setRegistry(registry);
    resetGlobalInterceptionSwitch();
    SamplingController.getInstance().setSamplingRate(1);
    SamplingController.getInstance().resetCounter();
  }

  @AfterEach
  void tearDown() {
    InterceptorHolder.setRegistry(null);
    resetGlobalInterceptionSwitch();
    SamplingController.getInstance().setSamplingRate(1);
    SamplingController.getInstance().resetCounter();
  }

  /**
   * {@code GlobalInterceptionSwitch.resetForTest()} is package-private to the {@code switches}
   * package, so tests outside it reset both fields via reflection to avoid leaking {@code
   * enabled}/{@code toggleCount} state into other test classes sharing the same JVM fork.
   */
  private static void resetGlobalInterceptionSwitch() {
    try {
      java.lang.reflect.Field enabledField = GlobalInterceptionSwitch.class.getDeclaredField("enabled");
      enabledField.setAccessible(true);
      enabledField.setBoolean(null, true);
      java.lang.reflect.Field toggleCountField =
          GlobalInterceptionSwitch.class.getDeclaredField("toggleCount");
      toggleCountField.setAccessible(true);
      ((java.util.concurrent.atomic.AtomicLong) toggleCountField.get(null)).set(0);
    } catch (ReflectiveOperationException e) {
      throw new IllegalStateException(e);
    }
  }

  /**
   * {@code InterceptorDefinition} only accepts {@link Interceptor}, but {@code CatchAdvice} only
   * invokes interceptors that are also {@link CatchInterceptor}. Production catch-interceptor
   * implementations satisfy both interfaces; this test double does the same.
   */
  private abstract static class AbstractCatchInterceptor implements Interceptor, CatchInterceptor {}

  private static void registerCatchInterceptor(
      DefaultInterceptorRegistry registry, String name, AbstractCatchInterceptor interceptor) {
    registry.register(
        new InterceptorDefinition(
            name, new Pointcut(ClassMatcher.any(), MethodMatcher.any()), interceptor));
  }

  private static Method method(String name) throws NoSuchMethodException {
    return SampleClass.class.getDeclaredMethod(name);
  }

  private static CatchPointcut anyExceptionInSampleClass() {
    return CatchPointcut.inClass(ClassMatcher.byName(SampleClass.class.getName()), Throwable.class);
  }

  // --- onCatchEnter: gating checks ---

  @Test
  void onCatchEnter_interceptionDisabled_returnsNull() throws Exception {
    GlobalInterceptionSwitch.setEnabled(false, "test");

    CatchInvocation result =
        CatchAdvice.onCatchEnter(SampleClass.class, method("doWork"), new RuntimeException("boom"));

    assertNull(result);
  }

  @Test
  void onCatchEnter_nullRegistry_returnsNull() throws Exception {
    InterceptorHolder.setRegistry(null);

    CatchInvocation result =
        CatchAdvice.onCatchEnter(SampleClass.class, method("doWork"), new RuntimeException("boom"));

    assertNull(result);
  }

  @Test
  void onCatchEnter_samplingSkipsInvocation_returnsNull() throws Exception {
    SamplingController.getInstance().setSamplingRate(2);
    SamplingController.getInstance().resetCounter();

    // First invocation with rate=2: counter=1, 1 % 2 != 0 -> shouldSample() is false
    CatchInvocation result =
        CatchAdvice.onCatchEnter(SampleClass.class, method("doWork"), new RuntimeException("boom"));

    assertNull(result);
  }

  @Test
  void onCatchEnter_nullCaughtException_returnsNull() throws Exception {
    CatchInvocation result = CatchAdvice.onCatchEnter(SampleClass.class, method("doWork"), null);

    assertNull(result);
  }

  @Test
  void onCatchEnter_noMatchingInterceptor_returnsNullAndReleasesInvocation() throws Exception {
    CatchInvocation result =
        CatchAdvice.onCatchEnter(SampleClass.class, method("doWork"), new RuntimeException("boom"));

    assertNull(result, "Unlike FieldAdvice, CatchAdvice releases and returns null when nothing matches");
  }

  @Test
  void onCatchEnter_nonCatchInterceptorsAreIgnored() throws Exception {
    Interceptor plainInterceptor =
        new Interceptor() {
          @Override
          public void before(com.github.cc11001100.weavergirl.api.interceptor.MethodInvocation invocation) {}
        };
    registry.register(
        new InterceptorDefinition(
            "plain", new Pointcut(ClassMatcher.any(), MethodMatcher.any()), plainInterceptor));

    CatchInvocation result =
        CatchAdvice.onCatchEnter(SampleClass.class, method("doWork"), new RuntimeException("boom"));

    assertNull(result);
  }

  // --- onCatchEnter: matching invokes onCatch ---

  @Test
  void onCatchEnter_matchingInterceptor_invokesOnCatch_andReturnsInvocation() throws Exception {
    List<Throwable> seen = new ArrayList<>();
    AbstractCatchInterceptor interceptor =
        new AbstractCatchInterceptor() {
          @Override
          public void onCatch(CatchInvocation invocation) {
            seen.add(invocation.getCaughtException());
          }

          @Override
          public CatchPointcut[] catchPointcuts() {
            return new CatchPointcut[] {anyExceptionInSampleClass()};
          }
        };
    registerCatchInterceptor(registry, "match-test", interceptor);

    RuntimeException ex = new RuntimeException("boom");
    CatchInvocation result = CatchAdvice.onCatchEnter(SampleClass.class, method("doWork"), ex);

    assertEquals(1, seen.size());
    assertSame(ex, seen.get(0));
    assertNotNull(result, "A matched interceptor should cause the invocation to be returned (not released)");
    assertEquals(SampleClass.class, result.getTargetClass());
    assertEquals("doWork", result.getMethodName());
    assertSame(ex, result.getCaughtException());
  }

  @Test
  void onCatchEnter_nonMatchingExceptionType_interceptorNotInvoked() throws Exception {
    boolean[] called = {false};
    AbstractCatchInterceptor interceptor =
        new AbstractCatchInterceptor() {
          @Override
          public void onCatch(CatchInvocation invocation) {
            called[0] = true;
          }

          @Override
          public CatchPointcut[] catchPointcuts() {
            return new CatchPointcut[] {
              CatchPointcut.inClass(ClassMatcher.byName(SampleClass.class.getName()), IllegalStateException.class)
            };
          }
        };
    registerCatchInterceptor(registry, "no-match", interceptor);

    CatchInvocation result =
        CatchAdvice.onCatchEnter(SampleClass.class, method("doWork"), new RuntimeException("boom"));

    assertFalse(called[0]);
    assertNull(result);
  }

  @Test
  void onCatchEnter_nonMatchingClass_interceptorNotInvoked() throws Exception {
    boolean[] called = {false};
    AbstractCatchInterceptor interceptor =
        new AbstractCatchInterceptor() {
          @Override
          public void onCatch(CatchInvocation invocation) {
            called[0] = true;
          }

          @Override
          public CatchPointcut[] catchPointcuts() {
            return new CatchPointcut[] {
              CatchPointcut.inClass(ClassMatcher.byName("com.example.SomeOtherClass"), Throwable.class)
            };
          }
        };
    registerCatchInterceptor(registry, "no-class-match", interceptor);

    CatchInvocation result =
        CatchAdvice.onCatchEnter(SampleClass.class, method("doWork"), new RuntimeException("boom"));

    assertFalse(called[0]);
    assertNull(result);
  }

  @Test
  void onCatchEnter_subclassExceptionMatches() throws Exception {
    boolean[] called = {false};
    AbstractCatchInterceptor interceptor =
        new AbstractCatchInterceptor() {
          @Override
          public void onCatch(CatchInvocation invocation) {
            called[0] = true;
          }

          @Override
          public CatchPointcut[] catchPointcuts() {
            return new CatchPointcut[] {
              CatchPointcut.inClass(ClassMatcher.byName(SampleClass.class.getName()), Exception.class)
            };
          }
        };
    registerCatchInterceptor(registry, "subclass-match", interceptor);

    CatchInvocation result =
        CatchAdvice.onCatchEnter(SampleClass.class, method("doWork"), new IllegalArgumentException("boom"));

    assertTrue(called[0]);
    assertNotNull(result);
  }

  @Test
  void onCatchEnter_multipleCatchPointcutsOnSameInterceptor_matchesEither() throws Exception {
    List<String> matches = new ArrayList<>();
    AbstractCatchInterceptor interceptor =
        new AbstractCatchInterceptor() {
          @Override
          public void onCatch(CatchInvocation invocation) {
            matches.add(invocation.getCaughtException().getClass().getName());
          }

          @Override
          public CatchPointcut[] catchPointcuts() {
            return new CatchPointcut[] {
              CatchPointcut.inClass(ClassMatcher.byName(SampleClass.class.getName()), IllegalStateException.class),
              CatchPointcut.inClass(ClassMatcher.byName(SampleClass.class.getName()), IllegalArgumentException.class)
            };
          }
        };
    registerCatchInterceptor(registry, "multi-pointcut", interceptor);

    CatchAdvice.onCatchEnter(SampleClass.class, method("doWork"), new IllegalArgumentException("boom"));

    assertEquals(1, matches.size());
    assertEquals("java.lang.IllegalArgumentException", matches.get(0));
  }

  @Test
  void onCatchEnter_multipleInterceptorsMatchingSameCatch_bothInvoked() throws Exception {
    List<String> invoked = new ArrayList<>();
    AbstractCatchInterceptor first =
        new AbstractCatchInterceptor() {
          @Override
          public void onCatch(CatchInvocation invocation) {
            invoked.add("first");
          }

          @Override
          public CatchPointcut[] catchPointcuts() {
            return new CatchPointcut[] {anyExceptionInSampleClass()};
          }
        };
    AbstractCatchInterceptor second =
        new AbstractCatchInterceptor() {
          @Override
          public void onCatch(CatchInvocation invocation) {
            invoked.add("second");
          }

          @Override
          public CatchPointcut[] catchPointcuts() {
            return new CatchPointcut[] {anyExceptionInSampleClass()};
          }
        };
    registerCatchInterceptor(registry, "first", first);
    registerCatchInterceptor(registry, "second", second);

    CatchAdvice.onCatchEnter(SampleClass.class, method("doWork"), new RuntimeException("boom"));

    assertEquals(2, invoked.size());
    assertTrue(invoked.contains("first"));
    assertTrue(invoked.contains("second"));
  }

  // --- onCatchEnter: interceptor behavior controls are recorded on the invocation ---

  @Test
  void onCatchEnter_interceptorSuppressesCatch_invocationReflectsSuppression() throws Exception {
    AbstractCatchInterceptor interceptor =
        new AbstractCatchInterceptor() {
          @Override
          public void onCatch(CatchInvocation invocation) {
            invocation.suppressCatch();
          }

          @Override
          public CatchPointcut[] catchPointcuts() {
            return new CatchPointcut[] {anyExceptionInSampleClass()};
          }
        };
    registerCatchInterceptor(registry, "suppress-test", interceptor);

    CatchInvocation result =
        CatchAdvice.onCatchEnter(SampleClass.class, method("doWork"), new RuntimeException("boom"));

    assertNotNull(result);
    assertTrue(result.isCatchSuppressed());
  }

  @Test
  void onCatchEnter_interceptorReplacesException_invocationReflectsReplacement() throws Exception {
    IllegalStateException replacement = new IllegalStateException("replacement");
    AbstractCatchInterceptor interceptor =
        new AbstractCatchInterceptor() {
          @Override
          public void onCatch(CatchInvocation invocation) {
            invocation.setCaughtException(replacement);
          }

          @Override
          public CatchPointcut[] catchPointcuts() {
            return new CatchPointcut[] {anyExceptionInSampleClass()};
          }
        };
    registerCatchInterceptor(registry, "replace-test", interceptor);

    CatchInvocation result =
        CatchAdvice.onCatchEnter(SampleClass.class, method("doWork"), new RuntimeException("boom"));

    assertNotNull(result);
    assertTrue(result.isExceptionOverridden());
    assertSame(replacement, result.getReplacementException());
  }

  @Test
  void onCatchEnter_interceptorSetsCatchReturnValue_invocationReflectsOverride() throws Exception {
    AbstractCatchInterceptor interceptor =
        new AbstractCatchInterceptor() {
          @Override
          public void onCatch(CatchInvocation invocation) {
            invocation.setCatchReturnValue("fallback");
          }

          @Override
          public CatchPointcut[] catchPointcuts() {
            return new CatchPointcut[] {anyExceptionInSampleClass()};
          }
        };
    registerCatchInterceptor(registry, "return-override-test", interceptor);

    CatchInvocation result =
        CatchAdvice.onCatchEnter(SampleClass.class, method("doWork"), new RuntimeException("boom"));

    assertNotNull(result);
    assertTrue(result.isReturnOverridden());
    assertEquals("fallback", result.getCatchReturnValue());
  }

  // --- onCatchEnter: interceptor exceptions are swallowed ---

  @Test
  void onCatchEnter_interceptorThrows_exceptionSwallowed_invocationStillReturned() throws Exception {
    AbstractCatchInterceptor interceptor =
        new AbstractCatchInterceptor() {
          @Override
          public void onCatch(CatchInvocation invocation) {
            throw new RuntimeException("interceptor boom");
          }

          @Override
          public CatchPointcut[] catchPointcuts() {
            return new CatchPointcut[] {anyExceptionInSampleClass()};
          }
        };
    registerCatchInterceptor(registry, "throwing", interceptor);

    CatchInvocation result =
        assertDoesNotThrow(
            () -> CatchAdvice.onCatchEnter(SampleClass.class, method("doWork"), new RuntimeException("boom")));

    assertNotNull(result, "Invocation should still be returned even when an interceptor throws");
    assertFalse(result.isCatchSuppressed());
  }

  @Test
  void onCatchEnter_oneInterceptorThrows_othersStillInvoked() throws Exception {
    List<String> invoked = new ArrayList<>();
    AbstractCatchInterceptor throwing =
        new AbstractCatchInterceptor() {
          @Override
          public void onCatch(CatchInvocation invocation) {
            invoked.add("throwing");
            throw new RuntimeException("boom");
          }

          @Override
          public CatchPointcut[] catchPointcuts() {
            return new CatchPointcut[] {anyExceptionInSampleClass()};
          }
        };
    AbstractCatchInterceptor healthy =
        new AbstractCatchInterceptor() {
          @Override
          public void onCatch(CatchInvocation invocation) {
            invoked.add("healthy");
          }

          @Override
          public CatchPointcut[] catchPointcuts() {
            return new CatchPointcut[] {anyExceptionInSampleClass()};
          }
        };
    registerCatchInterceptor(registry, "throwing", throwing);
    registerCatchInterceptor(registry, "healthy", healthy);

    CatchAdvice.onCatchEnter(SampleClass.class, method("doWork"), new RuntimeException("boom"));

    assertEquals(2, invoked.size());
    assertTrue(invoked.contains("throwing"));
    assertTrue(invoked.contains("healthy"));
  }

  // --- onCatchExit ---

  @Test
  void onCatchExit_nullInvocation_doesNotThrow() {
    assertDoesNotThrow(() -> CatchAdvice.onCatchExit(null));
  }

  @Test
  void onCatchExit_releasesInvocationBackToPool() throws Exception {
    CatchInvocation invocation =
        CatchInvocationPool.acquire(SampleClass.class, "doWork", new RuntimeException("boom"));

    assertDoesNotThrow(() -> CatchAdvice.onCatchExit(invocation));

    // Pool releases clear() the instance; a freshly-acquired instance afterward is the same
    // cleared object, confirming it was actually returned to the (thread-local) pool.
    CatchInvocation reacquired =
        CatchInvocationPool.acquire(SampleClass.class, "otherMethod", new IllegalStateException("x"));
    assertSame(invocation, reacquired);
  }

  @Test
  void onCatchExit_clearsCatchInvocationState() throws Exception {
    CatchInvocation invocation =
        CatchInvocationPool.acquire(SampleClass.class, "doWork", new RuntimeException("boom"));
    invocation.suppressCatch();
    invocation.setCatchReturnValue("value");

    CatchAdvice.onCatchExit(invocation);

    assertFalse(invocation.isCatchSuppressed(), "release() clears the invocation for pool reuse");
    assertFalse(invocation.isReturnOverridden());
  }

  // --- full round trip ---

  @Test
  void fullRoundTrip_enterThenExit_doesNotThrow() throws Exception {
    AbstractCatchInterceptor interceptor =
        new AbstractCatchInterceptor() {
          @Override
          public void onCatch(CatchInvocation invocation) {
            invocation.setCatchReturnValue("handled");
          }

          @Override
          public CatchPointcut[] catchPointcuts() {
            return new CatchPointcut[] {anyExceptionInSampleClass()};
          }
        };
    registerCatchInterceptor(registry, "roundtrip", interceptor);

    CatchInvocation invocation =
        CatchAdvice.onCatchEnter(SampleClass.class, method("doWork"), new RuntimeException("boom"));
    assertNotNull(invocation);
    assertTrue(invocation.isReturnOverridden());
    assertEquals("handled", invocation.getCatchReturnValue());

    assertDoesNotThrow(() -> CatchAdvice.onCatchExit(invocation));
  }

  // --- Helper sample class for testing ---

  public static class SampleClass {
    public void doWork() {}
  }
}
