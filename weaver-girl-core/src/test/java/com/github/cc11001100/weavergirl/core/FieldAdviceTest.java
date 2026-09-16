package com.github.cc11001100.weavergirl.core;

import static org.junit.jupiter.api.Assertions.*;

import com.github.cc11001100.weavergirl.api.interceptor.FieldInterceptor;
import com.github.cc11001100.weavergirl.api.interceptor.FieldInvocation;
import com.github.cc11001100.weavergirl.api.interceptor.Interceptor;
import com.github.cc11001100.weavergirl.api.interceptor.InterceptorDefinition;
import com.github.cc11001100.weavergirl.api.matcher.ClassMatcher;
import com.github.cc11001100.weavergirl.api.matcher.FieldMatcher;
import com.github.cc11001100.weavergirl.api.matcher.MethodMatcher;
import com.github.cc11001100.weavergirl.api.pointcut.FieldPointcut;
import com.github.cc11001100.weavergirl.api.pointcut.Pointcut;
import com.github.cc11001100.weavergirl.core.interceptor.FieldInvocationPool;
import com.github.cc11001100.weavergirl.core.registry.DefaultInterceptorRegistry;
import com.github.cc11001100.weavergirl.core.sampling.SamplingController;
import com.github.cc11001100.weavergirl.core.switches.GlobalInterceptionSwitch;
import java.lang.reflect.Field;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class FieldAdviceTest {

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
      Field enabledField = GlobalInterceptionSwitch.class.getDeclaredField("enabled");
      enabledField.setAccessible(true);
      enabledField.setBoolean(null, true);
      Field toggleCountField = GlobalInterceptionSwitch.class.getDeclaredField("toggleCount");
      toggleCountField.setAccessible(true);
      ((java.util.concurrent.atomic.AtomicLong) toggleCountField.get(null)).set(0);
    } catch (ReflectiveOperationException e) {
      throw new IllegalStateException(e);
    }
  }

  /**
   * {@code InterceptorDefinition} only accepts {@link Interceptor}, but {@code FieldAdvice} only
   * invokes interceptors that are also {@link FieldInterceptor}. Production field-interceptor
   * implementations satisfy both interfaces; this test double does the same.
   */
  private abstract static class AbstractFieldInterceptor implements Interceptor, FieldInterceptor {}

  /**
   * {@code FieldInvocationPool.release()} calls {@code FieldInvocation.clear()}, which nulls out
   * {@code returnSnapshot} immediately after {@code FieldAdvice.onFieldExit} sets it and releases
   * the invocation back to the pool in the same call. That means the snapshot can never be
   * observed by reading it back off the (pooled, cleared) invocation after {@code onFieldExit}
   * returns. This subclass captures the snapshot at the moment it is set so tests can verify the
   * value {@code onFieldExit} actually computed.
   */
  private static class CapturingFieldInvocation extends FieldInvocation {
    private com.github.cc11001100.weavergirl.api.interceptor.ReturnSnapshot capturedSnapshot;

    CapturingFieldInvocation(
        Class<?> targetClass, String fieldName, String fieldTypeName, Object target) {
      super(targetClass, fieldName, fieldTypeName, target);
    }

    @Override
    public void setReturnSnapshot(
        com.github.cc11001100.weavergirl.api.interceptor.ReturnSnapshot snapshot) {
      super.setReturnSnapshot(snapshot);
      if (snapshot != null) {
        this.capturedSnapshot = snapshot;
      }
    }
  }

  private static Field field(String name) throws NoSuchFieldException {
    return SampleClass.class.getDeclaredField(name);
  }

  private static void registerFieldInterceptor(
      DefaultInterceptorRegistry registry, String name, AbstractFieldInterceptor interceptor) {
    registry.register(
        new InterceptorDefinition(
            name, new Pointcut(ClassMatcher.any(), MethodMatcher.any()), interceptor));
  }

  // --- onFieldEnter tests ---

  @Test
  void onFieldEnter_interceptionDisabled_returnsNull() throws Exception {
    GlobalInterceptionSwitch.setEnabled(false, "test");
    Field f = field("value");

    FieldInvocation result = FieldAdvice.onFieldEnter(SampleClass.class, f, new SampleClass(), null);

    assertNull(result);
  }

  @Test
  void onFieldEnter_nullRegistry_returnsNull() throws Exception {
    InterceptorHolder.setRegistry(null);
    Field f = field("value");

    FieldInvocation result = FieldAdvice.onFieldEnter(SampleClass.class, f, new SampleClass(), null);

    assertNull(result);
  }

  @Test
  void onFieldEnter_samplingSkipsInvocation_returnsNull() throws Exception {
    SamplingController.getInstance().setSamplingRate(2);
    SamplingController.getInstance().resetCounter();
    Field f = field("value");

    // First invocation with rate=2: counter=1, 1 % 2 != 0 -> shouldSample() is false
    FieldInvocation result = FieldAdvice.onFieldEnter(SampleClass.class, f, new SampleClass(), null);

    assertNull(result);
  }

  @Test
  void onFieldEnter_noMatchingInterceptor_returnsNonNullInvocation() throws Exception {
    Field f = field("value");

    FieldInvocation result = FieldAdvice.onFieldEnter(SampleClass.class, f, new SampleClass(), null);

    assertNotNull(
        result, "Should still acquire and return an invocation even when nothing matches");
    assertEquals("value", result.getFieldName());
    assertEquals(SampleClass.class, result.getTargetClass());
  }

  @Test
  void onFieldEnter_nonFieldInterceptorsAreIgnored() throws Exception {
    Interceptor plainInterceptor =
        new Interceptor() {
          @Override
          public void before(com.github.cc11001100.weavergirl.api.interceptor.MethodInvocation invocation) {}
        };
    registry.register(
        new InterceptorDefinition(
            "plain",
            new Pointcut(ClassMatcher.any(), MethodMatcher.any()),
            plainInterceptor));

    Field f = field("value");
    FieldInvocation result = FieldAdvice.onFieldEnter(SampleClass.class, f, new SampleClass(), null);

    assertNotNull(result);
  }

  @Test
  void onFieldEnter_getAccess_invokesOnFieldGet_withOverride() throws Exception {
    boolean[] getCalled = {false};
    AbstractFieldInterceptor interceptor =
        new AbstractFieldInterceptor() {
          @Override
          public void onFieldGet(FieldInvocation invocation, Object originalValue) {
            getCalled[0] = true;
            assertNull(originalValue, "onFieldGet is always invoked with a null placeholder value");
            invocation.setReturnValue("overridden");
          }

          @Override
          public FieldPointcut[] fieldPointcuts() {
            return new FieldPointcut[] {FieldPointcut.byField(FieldMatcher.byName("value"))};
          }
        };
    registerFieldInterceptor(registry, "get-test", interceptor);

    Field f = field("value");
    // writeValue == null => getfield access path
    FieldInvocation result = FieldAdvice.onFieldEnter(SampleClass.class, f, new SampleClass(), null);

    assertTrue(getCalled[0]);
    assertNotNull(result);
    assertTrue(result.isReturnOverridden());
    assertEquals("overridden", result.getReturnValue());
  }

  @Test
  void onFieldEnter_setAccess_invokesOnFieldSet_withOverride() throws Exception {
    boolean[] setCalled = {false};
    Object[] seenTarget = new Object[1];
    Object[] seenOriginal = new Object[1];
    AbstractFieldInterceptor interceptor =
        new AbstractFieldInterceptor() {
          @Override
          public void onFieldSet(FieldInvocation invocation, Object target, Object originalValue) {
            setCalled[0] = true;
            seenTarget[0] = target;
            seenOriginal[0] = originalValue;
            invocation.setWriteValue("rewritten");
          }

          @Override
          public FieldPointcut[] fieldPointcuts() {
            return new FieldPointcut[] {FieldPointcut.byField(FieldMatcher.byName("value"))};
          }
        };
    registerFieldInterceptor(registry, "set-test", interceptor);

    Field f = field("value");
    SampleClass target = new SampleClass();
    FieldInvocation result = FieldAdvice.onFieldEnter(SampleClass.class, f, target, "newValue");

    assertTrue(setCalled[0]);
    assertSame(target, seenTarget[0]);
    assertEquals("newValue", seenOriginal[0]);
    assertNotNull(result);
    assertTrue(result.isWriteOverridden());
    assertEquals("rewritten", result.getWriteValue());
  }

  @Test
  void onFieldEnter_setAccess_skipWrite_marksAttachment() throws Exception {
    AbstractFieldInterceptor interceptor =
        new AbstractFieldInterceptor() {
          @Override
          public void onFieldSet(FieldInvocation invocation, Object target, Object originalValue) {
            invocation.skipWrite();
          }

          @Override
          public FieldPointcut[] fieldPointcuts() {
            return new FieldPointcut[] {FieldPointcut.byField(FieldMatcher.byName("value"))};
          }
        };
    registerFieldInterceptor(registry, "skip-test", interceptor);

    Field f = field("value");
    FieldInvocation result = FieldAdvice.onFieldEnter(SampleClass.class, f, new SampleClass(), "x");

    assertNotNull(result);
    assertTrue(result.isWriteSkipped());
    assertEquals(Boolean.TRUE, result.getAttachment("__field_write_skipped__"));
  }

  @Test
  void onFieldEnter_nonMatchingFieldPointcut_interceptorNotInvoked() throws Exception {
    boolean[] called = {false};
    AbstractFieldInterceptor interceptor =
        new AbstractFieldInterceptor() {
          @Override
          public void onFieldGet(FieldInvocation invocation, Object originalValue) {
            called[0] = true;
          }

          @Override
          public FieldPointcut[] fieldPointcuts() {
            return new FieldPointcut[] {FieldPointcut.byField(FieldMatcher.byName("otherField"))};
          }
        };
    registerFieldInterceptor(registry, "no-match", interceptor);

    Field f = field("value");
    FieldAdvice.onFieldEnter(SampleClass.class, f, new SampleClass(), null);

    assertFalse(called[0]);
  }

  @Test
  void onFieldEnter_interceptorThrows_exceptionSwallowed() throws Exception {
    AbstractFieldInterceptor interceptor =
        new AbstractFieldInterceptor() {
          @Override
          public void onFieldGet(FieldInvocation invocation, Object originalValue) {
            throw new RuntimeException("boom");
          }

          @Override
          public FieldPointcut[] fieldPointcuts() {
            return new FieldPointcut[] {FieldPointcut.byField(FieldMatcher.byName("value"))};
          }
        };
    registerFieldInterceptor(registry, "throwing", interceptor);

    Field f = field("value");
    FieldInvocation result =
        assertDoesNotThrow(
            () -> FieldAdvice.onFieldEnter(SampleClass.class, f, new SampleClass(), null));

    assertNotNull(result, "Invocation should still be returned even when an interceptor throws");
    assertFalse(result.isReturnOverridden());
  }

  @Test
  void onFieldEnter_multipleFieldPointcutsOnSameInterceptor() throws Exception {
    java.util.List<String> matchedFields = new java.util.ArrayList<>();
    AbstractFieldInterceptor interceptor =
        new AbstractFieldInterceptor() {
          @Override
          public void onFieldGet(FieldInvocation invocation, Object originalValue) {
            matchedFields.add(invocation.getFieldName());
          }

          @Override
          public FieldPointcut[] fieldPointcuts() {
            return new FieldPointcut[] {
              FieldPointcut.byField(FieldMatcher.byName("value")),
              FieldPointcut.byField(FieldMatcher.byName("counter"))
            };
          }
        };
    registerFieldInterceptor(registry, "multi", interceptor);

    FieldAdvice.onFieldEnter(SampleClass.class, field("value"), new SampleClass(), null);

    assertEquals(1, matchedFields.size());
    assertEquals("value", matchedFields.get(0));
  }

  @Test
  void onFieldEnter_staticField_nullTarget_isStaticTrue() throws Exception {
    AbstractFieldInterceptor interceptor =
        new AbstractFieldInterceptor() {
          @Override
          public FieldPointcut[] fieldPointcuts() {
            return new FieldPointcut[] {FieldPointcut.byField(FieldMatcher.byName("counter"))};
          }
        };
    registerFieldInterceptor(registry, "static-test", interceptor);

    Field f = field("counter");
    // static access: @Advice.This is optional and absent -> target == null
    FieldInvocation result = FieldAdvice.onFieldEnter(SampleClass.class, f, null, null);

    assertNotNull(result);
    assertTrue(result.isStatic());
  }

  // --- onFieldExit tests ---

  @Test
  void onFieldExit_interceptionDisabled_releasesInvocationAndReturns() throws Exception {
    Field f = field("value");
    FieldInvocation invocation =
        FieldInvocationPool.acquire(SampleClass.class, "value", "java.lang.String", new SampleClass());

    GlobalInterceptionSwitch.setEnabled(false, "test");

    assertDoesNotThrow(
        () ->
            FieldAdvice.onFieldExit(
                invocation, SampleClass.class, f, new SampleClass(), "result", null));
  }

  @Test
  void onFieldExit_interceptionDisabled_nullInvocation_doesNotThrow() throws Exception {
    Field f = field("value");
    GlobalInterceptionSwitch.setEnabled(false, "test");

    assertDoesNotThrow(
        () -> FieldAdvice.onFieldExit(null, SampleClass.class, f, new SampleClass(), null, null));
  }

  @Test
  void onFieldExit_nullInvocation_returnsImmediately() throws Exception {
    Field f = field("value");

    assertDoesNotThrow(
        () -> FieldAdvice.onFieldExit(null, SampleClass.class, f, new SampleClass(), "x", null));
  }

  @Test
  void onFieldExit_writeSkipped_releasesWithoutSnapshot() throws Exception {
    Field f = field("value");
    FieldInvocation invocation =
        FieldInvocationPool.acquire(SampleClass.class, "value", "java.lang.String", new SampleClass());
    invocation.setAttachment("__field_write_skipped__", Boolean.TRUE);

    assertDoesNotThrow(
        () ->
            FieldAdvice.onFieldExit(
                invocation, SampleClass.class, f, new SampleClass(), null, null));
  }

  @Test
  void onFieldExit_normalGetAccess_setsReturnSnapshot() throws Exception {
    // NB: onFieldExit releases the invocation back to the pool before returning, which clears
    // returnSnapshot (see CapturingFieldInvocation javadoc), so we capture it as it's set rather
    // than reading it back off the invocation afterward.
    Field f = field("value");
    CapturingFieldInvocation invocation =
        new CapturingFieldInvocation(SampleClass.class, "value", "java.lang.String", new SampleClass());

    FieldAdvice.onFieldExit(invocation, SampleClass.class, f, new SampleClass(), "hello", null);

    assertNotNull(invocation.capturedSnapshot);
    assertEquals("hello", invocation.capturedSnapshot.getValue());
    assertNull(invocation.getReturnSnapshot(), "released invocation is cleared by the pool");
  }

  @Test
  void onFieldExit_returnOverridden_usesOverrideValueInSnapshot() throws Exception {
    Field f = field("value");
    CapturingFieldInvocation invocation =
        new CapturingFieldInvocation(SampleClass.class, "value", "java.lang.String", new SampleClass());
    invocation.setReturnValue("overridden");

    FieldAdvice.onFieldExit(invocation, SampleClass.class, f, new SampleClass(), "original", null);

    assertNotNull(invocation.capturedSnapshot);
    assertEquals("overridden", invocation.capturedSnapshot.getValue());
  }

  @Test
  void onFieldExit_withThrowable_doesNotSetReturnSnapshot() throws Exception {
    Field f = field("value");
    CapturingFieldInvocation invocation =
        new CapturingFieldInvocation(SampleClass.class, "value", "java.lang.String", new SampleClass());

    FieldAdvice.onFieldExit(
        invocation, SampleClass.class, f, new SampleClass(), null, new RuntimeException("boom"));

    assertNull(invocation.capturedSnapshot, "no snapshot should be computed when a throwable is present");
  }

  @Test
  void onFieldExit_nullTargetClass_exceptionCaught_invocationStillReleased() throws Exception {
    Field f = field("value");
    FieldInvocation invocation =
        FieldInvocationPool.acquire(SampleClass.class, "value", "java.lang.String", new SampleClass());

    // targetClass.getName() will NPE inside onFieldExit; the outer catch must swallow it and
    // still release the invocation back to the pool without propagating.
    assertDoesNotThrow(
        () -> FieldAdvice.onFieldExit(invocation, null, f, new SampleClass(), "value", null));
  }

  @Test
  void onFieldExit_afterEnter_fullRoundTrip_getAccess() throws Exception {
    AbstractFieldInterceptor interceptor =
        new AbstractFieldInterceptor() {
          @Override
          public void onFieldGet(FieldInvocation invocation, Object originalValue) {
            invocation.setReturnValue("from-interceptor");
          }

          @Override
          public FieldPointcut[] fieldPointcuts() {
            return new FieldPointcut[] {FieldPointcut.byField(FieldMatcher.byName("value"))};
          }
        };
    registerFieldInterceptor(registry, "roundtrip", interceptor);

    Field f = field("value");
    SampleClass target = new SampleClass();
    FieldInvocation invocation = FieldAdvice.onFieldEnter(SampleClass.class, f, target, null);
    assertNotNull(invocation);
    assertTrue(invocation.isReturnOverridden());
    assertEquals("from-interceptor", invocation.getReturnValue());

    // onFieldExit releases the invocation back to the pool, which clears its state; verify it
    // completes without error rather than reading the (now-cleared) invocation afterward.
    assertDoesNotThrow(
        () -> FieldAdvice.onFieldExit(invocation, SampleClass.class, f, target, "original", null));
  }

  // --- Helper sample class for testing ---

  public static class SampleClass {
    public String value = "initial";
    public static int counter = 0;
  }
}
