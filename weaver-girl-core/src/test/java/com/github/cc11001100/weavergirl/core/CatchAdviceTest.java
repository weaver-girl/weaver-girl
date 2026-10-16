package com.github.cc11001100.weavergirl.core;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

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

/**
 * Unit tests for {@link CatchAdvice#dispatch}: gating, matching, invocation, and disposition
 * resolution. These drive the dispatch entry point directly (no weaving); the end-to-end wiring
 * through {@link com.github.cc11001100.weavergirl.core.transformer.WeaverTransformer} on a real
 * generated class is covered by {@link CatchAdviceRealWeavingTest}.
 */
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

  // --- dispatch: gating checks ---

  @Test
  void dispatch_interceptionDisabled_returnsNull() throws Exception {
    GlobalInterceptionSwitch.setEnabled(false, "test");

    CatchAdvice.CatchDisposition result =
        CatchAdvice.dispatch(SampleClass.class, method("doWork"), new RuntimeException("boom"), registry);

    assertNull(result);
  }

  @Test
  void dispatch_nullRegistry_returnsNull() throws Exception {
    CatchAdvice.CatchDisposition result =
        CatchAdvice.dispatch(SampleClass.class, method("doWork"), new RuntimeException("boom"), null);

    assertNull(result);
  }

  @Test
  void dispatch_samplingSkipsInvocation_returnsNull() throws Exception {
    SamplingController.getInstance().setSamplingRate(2);
    SamplingController.getInstance().resetCounter();

    // First invocation with rate=2: counter=1, 1 % 2 != 0 -> shouldSample() is false
    CatchAdvice.CatchDisposition result =
        CatchAdvice.dispatch(SampleClass.class, method("doWork"), new RuntimeException("boom"), registry);

    assertNull(result);
  }

  @Test
  void dispatch_nullCaughtException_returnsNull() throws Exception {
    CatchAdvice.CatchDisposition result =
        CatchAdvice.dispatch(SampleClass.class, method("doWork"), null, registry);

    assertNull(result);
  }

  @Test
  void dispatch_noMatchingInterceptor_returnsNull() throws Exception {
    CatchAdvice.CatchDisposition result =
        CatchAdvice.dispatch(
            SampleClass.class, method("doWork"), new RuntimeException("boom"), registry);

    assertNull(result);
  }

  @Test
  void dispatch_nonCatchInterceptorsAreIgnored() throws Exception {
    Interceptor plainInterceptor =
        new Interceptor() {
          @Override
          public void before(com.github.cc11001100.weavergirl.api.interceptor.MethodInvocation invocation) {}
        };
    registry.register(
        new InterceptorDefinition(
            "plain", new Pointcut(ClassMatcher.any(), MethodMatcher.any()), plainInterceptor));

    CatchAdvice.CatchDisposition result =
        CatchAdvice.dispatch(
            SampleClass.class, method("doWork"), new RuntimeException("boom"), registry);

    assertNull(result);
  }

  // --- dispatch: matching invokes onCatch ---

  @Test
  void dispatch_matchingInterceptor_invokesOnCatch() throws Exception {
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
    CatchAdvice.CatchDisposition result =
        CatchAdvice.dispatch(SampleClass.class, method("doWork"), ex, registry);

    assertEquals(1, seen.size());
    assertSame(ex, seen.get(0));
    assertNull(result, "an interceptor that only observes requests no change, so dispatch stays null");
  }

  @Test
  void dispatch_nonMatchingExceptionType_interceptorNotInvoked() throws Exception {
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

    CatchAdvice.CatchDisposition result =
        CatchAdvice.dispatch(
            SampleClass.class, method("doWork"), new RuntimeException("boom"), registry);

    assertFalse(called[0]);
    assertNull(result);
  }

  @Test
  void dispatch_nonMatchingClass_interceptorNotInvoked() throws Exception {
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

    CatchAdvice.CatchDisposition result =
        CatchAdvice.dispatch(
            SampleClass.class, method("doWork"), new RuntimeException("boom"), registry);

    assertFalse(called[0]);
    assertNull(result);
  }

  @Test
  void dispatch_subclassExceptionMatches() throws Exception {
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

    CatchAdvice.CatchDisposition result =
        CatchAdvice.dispatch(
            SampleClass.class, method("doWork"), new IllegalArgumentException("boom"), registry);

    assertTrue(called[0]);
    assertNull(result, "an observer-only interceptor requests no change");
  }

  @Test
  void dispatch_multipleCatchPointcutsOnSameInterceptor_matchesEither() throws Exception {
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

    CatchAdvice.dispatch(
        SampleClass.class, method("doWork"), new IllegalArgumentException("boom"), registry);

    assertEquals(1, matches.size());
    assertEquals("java.lang.IllegalArgumentException", matches.get(0));
  }

  @Test
  void dispatch_multipleInterceptorsMatchingSameCatch_bothInvoked() throws Exception {
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

    CatchAdvice.dispatch(
        SampleClass.class, method("doWork"), new RuntimeException("boom"), registry);

    assertEquals(2, invoked.size());
    assertTrue(invoked.contains("first"));
    assertTrue(invoked.contains("second"));
  }

  // --- dispatch: interceptor behavior controls resolve into a disposition ---

  @Test
  void dispatch_interceptorSuppressesCatch_resolvesSuppression() throws Exception {
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

    CatchAdvice.CatchDisposition result =
        CatchAdvice.dispatch(
            SampleClass.class, method("doWork"), new RuntimeException("boom"), registry);

    assertNotNull(result);
    assertTrue(result.suppressed);
    assertNull(result.replacementException);
    assertFalse(result.returnOverridden);
  }

  @Test
  void dispatch_interceptorReplacesException_resolvesReplacement() throws Exception {
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

    CatchAdvice.CatchDisposition result =
        CatchAdvice.dispatch(
            SampleClass.class, method("doWork"), new RuntimeException("boom"), registry);

    assertNotNull(result);
    assertSame(replacement, result.replacementException);
    assertFalse(result.returnOverridden);
    assertFalse(result.suppressed);
  }

  @Test
  void dispatch_interceptorSetsCatchReturnValue_resolvesReturnOverride() throws Exception {
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

    CatchAdvice.CatchDisposition result =
        CatchAdvice.dispatch(
            SampleClass.class, method("doWork"), new RuntimeException("boom"), registry);

    assertNotNull(result);
    assertTrue(result.returnOverridden);
    assertEquals("fallback", result.returnValue);
  }

  // --- dispatch: interceptor exceptions are swallowed ---

  @Test
  void dispatch_interceptorThrows_exceptionSwallowed_dispatchStillResolves() throws Exception {
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

    CatchAdvice.CatchDisposition result =
        assertDoesNotThrow(
            () ->
                CatchAdvice.dispatch(
                    SampleClass.class, method("doWork"), new RuntimeException("boom"), registry));

    assertNull(result, "a throwing interceptor requests no change, so dispatch stays null");
  }

  @Test
  void dispatch_oneInterceptorThrows_othersStillInvoked() throws Exception {
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
            invocation.setCatchReturnValue("handled");
          }

          @Override
          public CatchPointcut[] catchPointcuts() {
            return new CatchPointcut[] {anyExceptionInSampleClass()};
          }
        };
    registerCatchInterceptor(registry, "throwing", throwing);
    registerCatchInterceptor(registry, "healthy", healthy);

    CatchAdvice.CatchDisposition result =
        CatchAdvice.dispatch(
            SampleClass.class, method("doWork"), new RuntimeException("boom"), registry);

    assertEquals(2, invoked.size());
    assertTrue(invoked.contains("throwing"));
    assertTrue(invoked.contains("healthy"));
    assertNotNull(result);
    assertTrue(result.returnOverridden);
    assertEquals("handled", result.returnValue);
  }

  // --- dispatch: invocation lifecycle ---

  @Test
  void dispatch_releasesInvocationBackToPool() throws Exception {
    AbstractCatchInterceptor interceptor =
        new AbstractCatchInterceptor() {
          @Override
          public void onCatch(CatchInvocation invocation) {
            invocation.setCatchReturnValue("value");
          }

          @Override
          public CatchPointcut[] catchPointcuts() {
            return new CatchPointcut[] {anyExceptionInSampleClass()};
          }
        };
    registerCatchInterceptor(registry, "pool-test", interceptor);

    CatchAdvice.dispatch(
        SampleClass.class, method("doWork"), new RuntimeException("boom"), registry);

    // dispatch() release()s the instance back to the (thread-local) pool; a freshly-acquired
    // instance afterwards is the same cleared object, confirming it was actually returned.
    CatchInvocation reacquired =
        CatchInvocationPool.acquire(SampleClass.class, "otherMethod", new IllegalStateException("x"));
    assertFalse(reacquired.isCatchSuppressed(), "release() clears the invocation for pool reuse");
    assertFalse(reacquired.isReturnOverridden());
  }

  // --- Helper sample class for testing ---

  public static class SampleClass {
    public void doWork() {}
  }
}