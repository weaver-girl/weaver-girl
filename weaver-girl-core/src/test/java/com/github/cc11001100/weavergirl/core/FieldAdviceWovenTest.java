package com.github.cc11001100.weavergirl.core;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.github.cc11001100.weavergirl.api.interceptor.FieldInterceptor;
import com.github.cc11001100.weavergirl.api.interceptor.FieldInvocation;
import com.github.cc11001100.weavergirl.api.interceptor.Interceptor;
import com.github.cc11001100.weavergirl.api.interceptor.InterceptorDefinition;
import com.github.cc11001100.weavergirl.api.matcher.ClassMatcher;
import com.github.cc11001100.weavergirl.api.matcher.FieldMatcher;
import com.github.cc11001100.weavergirl.api.matcher.MethodMatcher;
import com.github.cc11001100.weavergirl.api.pointcut.FieldPointcut;
import com.github.cc11001100.weavergirl.api.pointcut.Pointcut;
import com.github.cc11001100.weavergirl.core.registry.DefaultInterceptorRegistry;
import com.github.cc11001100.weavergirl.core.sampling.SamplingController;
import com.github.cc11001100.weavergirl.core.switches.GlobalInterceptionSwitch;
import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.lang.reflect.Field;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * {@link FieldAdvice#onWovenEnter} and {@link FieldAdvice#onWovenExit} are the methods ByteBuddy
 * actually inlines, but JaCoCo counts the probes that fire inside a woven target against that
 * target, not against {@code FieldAdvice}. Both are ordinary {@code public static} methods, so
 * calling them directly covers the accessor-name resolution and the argument write-back that the
 * direct {@code onFieldEnter}/{@code onFieldExit} entry points never reach.
 */
class FieldAdviceWovenTest {

  @BeforeEach
  void setUp() {
    InterceptorHolder.setRegistry(new DefaultInterceptorRegistry());
    resetSwitch();
    SamplingController.getInstance().setMaxRate(Integer.MAX_VALUE);
    SamplingController.getInstance().setSamplingRate(1);
    SamplingController.getInstance().resetCounter();
  }

  @AfterEach
  void tearDown() {
    InterceptorHolder.setRegistry(null);
    resetSwitch();
    SamplingController.getInstance().setMaxRate(100);
    SamplingController.getInstance().setSamplingRate(1);
    SamplingController.getInstance().resetCounter();
  }

  private static void resetSwitch() {
    try {
      Field enabled = GlobalInterceptionSwitch.class.getDeclaredField("enabled");
      enabled.setAccessible(true);
      enabled.setBoolean(null, true);
    } catch (ReflectiveOperationException e) {
      throw new IllegalStateException(e);
    }
  }

  /** A field declared on a superclass, so resolution has to walk past the subclass. */
  static class Parent {
    String inherited;
  }

  static class Child extends Parent {
    boolean active;
    String URL;
  }

  private abstract static class RecordingFieldInterceptor implements Interceptor, FieldInterceptor {}

  private static void register(String fieldName, RecordingFieldInterceptor interceptor) {
    registerNamed("woven-" + fieldName, interceptor);
  }

  private static void registerNamed(String name, RecordingFieldInterceptor interceptor) {
    ((DefaultInterceptorRegistry) InterceptorHolder.getRegistry())
        .register(
            new InterceptorDefinition(
                name, new Pointcut(ClassMatcher.any(), MethodMatcher.any()), interceptor));
  }

  @Test
  void resolveFieldForAccessorRejectsNamesThatAreNotAccessors() {
    assertNull(FieldAdvice.resolveFieldForAccessor(Child.class, null));
    assertNull(FieldAdvice.resolveFieldForAccessor(Child.class, ""));
    // No getter/setter/access$ prefix.
    assertNull(FieldAdvice.resolveFieldForAccessor(Child.class, "run"));
    // A prefix with nothing after it.
    assertNull(FieldAdvice.resolveFieldForAccessor(Child.class, "get"));
    assertNull(FieldAdvice.resolveFieldForAccessor(Child.class, "is"));
    // A well formed accessor whose field exists on neither the class nor its parents.
    assertNull(FieldAdvice.resolveFieldForAccessor(Child.class, "getMissing"));
  }

  @Test
  void resolveFieldForAccessorStripsEveryAccessorPrefix() {
    assertField(Child.class, "active", FieldAdvice.resolveFieldForAccessor(Child.class, "isActive"));
    assertField(Child.class, "active", FieldAdvice.resolveFieldForAccessor(Child.class, "setActive"));
    // The synthetic accessor prefix used for inner-class field access.
    assertField(Child.class, "active", FieldAdvice.resolveFieldForAccessor(Child.class, "access$active"));
    // A suffix whose first letter is already lower case yields a single candidate name.
    assertField(Child.class, "active", FieldAdvice.resolveFieldForAccessor(Child.class, "getactive"));
  }

  private static void assertField(Class<?> declaring, String name, Field resolved) {
    assertEquals(declaring, resolved.getDeclaringClass());
    assertEquals(name, resolved.getName());
  }

  @Test
  void resolveFieldForAccessorWalksTheSuperclassAndKeepsTheUndecapitalizedFallback() {
    // Declared on the parent, so the search misses Child and continues up.
    assertField(Parent.class, "inherited", FieldAdvice.resolveFieldForAccessor(Child.class, "getInherited"));
    // getURL decapitalises to uRL, which misses, then falls back to the raw suffix URL.
    assertField(Child.class, "URL", FieldAdvice.resolveFieldForAccessor(Child.class, "getURL"));
  }

  @Test
  void onWovenEnterReturnsNullWhenInterceptionIsOffOrTheMethodIsNotAnAccessor() {
    GlobalInterceptionSwitch.setEnabled(false, "test");
    assertNull(FieldAdvice.onWovenEnter(Child.class, "getActive", new Child(), null));

    resetSwitch();
    assertNull(FieldAdvice.onWovenEnter(Child.class, "run", new Child(), null));
  }

  @Test
  void onWovenEnterWritesTheInterceptorDecisionBackIntoTheBoundArgument() {
    register(
        "inherited",
        new RecordingFieldInterceptor() {
          @Override
          public void onFieldSet(FieldInvocation invocation, Object target, Object originalValue) {
            invocation.setWriteValue("rewritten");
          }

          @Override
          public FieldPointcut[] fieldPointcuts() {
            return new FieldPointcut[] {FieldPointcut.byField(FieldMatcher.byName("inherited"))};
          }
        });

    FieldInvocation overridden =
        FieldAdvice.onWovenEnter(Child.class, "setInherited", new Child(), "original");
    assertTrue(overridden.isWriteOverridden());
    assertEquals("rewritten", overridden.getWriteValue());
    assertEquals(
        "inherited", ((Field) overridden.getAttachment("__field_origin__", Field.class)).getName());
  }

  @Test
  void onWovenEnterRestoresTheOriginalValueWhenTheWriteIsSkipped() throws Exception {
    register(
        "inherited",
        new RecordingFieldInterceptor() {
          @Override
          public void onFieldSet(FieldInvocation invocation, Object target, Object originalValue) {
            invocation.skipWrite();
          }

          @Override
          public FieldPointcut[] fieldPointcuts() {
            return new FieldPointcut[] {FieldPointcut.byField(FieldMatcher.byName("inherited"))};
          }
        });

    FieldInvocation skipped =
        FieldAdvice.onWovenEnter(Child.class, "setInherited", new Child(), "original");
    assertTrue(skipped.isWriteSkipped());
    assertEquals(Boolean.TRUE, skipped.getAttachment("__field_write_skipped__"));
    assertField(
        Parent.class, "inherited", (Field) skipped.getAttachment("__field_origin__", Field.class));
  }

  @Test
  void onWovenEnterLeavesAReadUntouched() {
    FieldInvocation read = FieldAdvice.onWovenEnter(Child.class, "getInherited", new Child(), null);
    assertEquals("inherited", ((Field) read.getAttachment("__field_origin__", Field.class)).getName());
  }

  @Test
  void onWovenExitAppliesAReturnOverrideAndDispatches() {
    FieldInvocation invocation =
        FieldAdvice.onWovenEnter(Child.class, "getInherited", new Child(), null);
    invocation.setReturnValue("override");

    // Nothing observable escapes the exit advice except that it must not throw and must consume the
    // invocation. The return override is assigned to the bound local, which only ByteBuddy reads.
    FieldAdvice.onWovenExit(invocation, "computed", null);
    FieldAdvice.onWovenExit(null, "computed", null);
  }

  @Test
  void anOpenCircuitBreakerSkipsTheFieldInterceptor() throws Exception {
    boolean[] called = {false};
    registerNamed(
        "woven-inherited-tripped",
        new RecordingFieldInterceptor() {
          @Override
          public void onFieldSet(FieldInvocation invocation, Object target, Object originalValue) {
            called[0] = true;
          }

          @Override
          public FieldPointcut[] fieldPointcuts() {
            return new FieldPointcut[] {FieldPointcut.byField(FieldMatcher.byName("inherited"))};
          }
        });
    for (int i = 0; i < 5; i++) {
      InterceptorHolder.recordOutcome("woven-inherited-tripped", false, 0L);
    }

    FieldInvocation result =
        FieldAdvice.onFieldEnter(
            Child.class, Parent.class.getDeclaredField("inherited"), new Child(), "x");

    assertTrue(result != null);
    assertTrue(!called[0]);
  }

  @Test
  void aNullFieldIsSwallowedByTheEnterAdvice() {
    assertNull(FieldAdvice.onFieldEnter(Child.class, null, new Child(), "x"));
  }

  @Test
  void aReleaseFailureWhileInterceptionIsOffIsSwallowed() {
    GlobalInterceptionSwitch.setEnabled(false, "test");
    FieldInvocation broken =
        new FieldInvocation(Child.class, "inherited", "java.lang.String", new Child()) {
          @Override
          public void clear() {
            throw new IllegalStateException("pool rejected it");
          }
        };

    FieldAdvice.dispatchExit(broken, Child.class, null, null, null);
  }

  @Test
  void aSnapshotThatCannotBeRecordedDoesNotBreakTheFieldAccess() throws Exception {
    FieldInvocation broken =
        new FieldInvocation(Child.class, "inherited", "java.lang.String", new Child()) {
          @Override
          public void setReturnSnapshot(
              com.github.cc11001100.weavergirl.api.interceptor.ReturnSnapshot snapshot) {
            throw new IllegalStateException("snapshot rejected");
          }
        };

    FieldAdvice.dispatchExit(
        broken, Child.class, Parent.class.getDeclaredField("inherited"), "v", null);
  }

  @Test
  void aPoolThatRejectsTheInvocationIsReportedRatherThanThrown() {
    FieldInvocation broken =
        new FieldInvocation(Child.class, "inherited", "java.lang.String", new Child()) {
          @Override
          public void clear() {
            throw new IllegalStateException("pool rejected it");
          }
        };
    broken.setAttachment("__field_origin__", Child.class.getDeclaredFields()[0]);

    PrintStream original = System.err;
    ByteArrayOutputStream captured = new ByteArrayOutputStream();
    System.setErr(new PrintStream(captured));
    try {
      FieldAdvice.dispatchExit(broken, Child.class, Child.class.getDeclaredFields()[0], "v", null);
    } finally {
      System.setErr(original);
    }

    assertTrue(captured.toString().contains("Failed to release FieldInvocation to pool"));
  }
}
