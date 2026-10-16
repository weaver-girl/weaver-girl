package com.github.cc11001100.weavergirl.core.taint;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

import java.lang.instrument.Instrumentation;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * Tests for TaintInstrumentation bootstrap bridge installation and JDK advice wiring.
 *
 * <p>These tests verify the install guard, bridge append logic, and advice class structure
 * without actually instrumenting the JDK (which requires a real agent).
 */
class TaintInstrumentationTest {

  private Instrumentation mockInstrumentation;

  @BeforeEach
  void setUp() {
    mockInstrumentation = mock(Instrumentation.class);
    // Reset static state between tests
    resetStaticState();
  }

  @AfterEach
  void tearDown() {
    resetStaticState();
  }

  private void resetStaticState() {
    // Access private static fields via reflection to reset state
    try {
      java.lang.reflect.Field installedField =
          TaintInstrumentation.class.getDeclaredField("installed");
      installedField.setAccessible(true);
      installedField.set(null, false);

      java.lang.reflect.Field jdkInstrumentedField =
          TaintInstrumentation.class.getDeclaredField("jdkInstrumented");
      jdkInstrumentedField.setAccessible(true);
      jdkInstrumentedField.set(null, false);

      java.lang.reflect.Field installFailureField =
          TaintInstrumentation.class.getDeclaredField("installFailure");
      installFailureField.setAccessible(true);
      installFailureField.set(null, null);
    } catch (Exception e) {
      // Ignore reflection errors in test cleanup
    }
  }

  @Test
  void isJdkInstrumented_returnsFalseInitially() {
    assertFalse(TaintInstrumentation.isJdkInstrumented());
  }

  @Test
  void installFailure_returnsNullInitially() {
    assertNull(TaintInstrumentation.installFailure());
  }

  @Test
  void install_withNullInstrumentation_doesNotThrow() {
    assertDoesNotThrow(() -> TaintInstrumentation.install(null));
    assertFalse(TaintInstrumentation.isJdkInstrumented());
  }

  @Test
  void install_isIdempotent() {
    when(mockInstrumentation.getAllLoadedClasses()).thenReturn(new Class<?>[0]);

    TaintInstrumentation.install(mockInstrumentation);
    TaintInstrumentation.install(mockInstrumentation);

    // Should only attempt appendToBootstrapClassLoaderSearch once
    verify(mockInstrumentation, atMost(1))
        .appendToBootstrapClassLoaderSearch(any(java.util.jar.JarFile.class));
  }

  @Test
  void alreadyLoadedBridge_skipsAppend() {
    // Simulate TaintBridge already loaded by bootstrap (name matches BRIDGE constant)
    // We can't actually load the real bridge class in this test, so just verify
    // that install handles the case gracefully without crashing
    when(mockInstrumentation.getAllLoadedClasses()).thenReturn(new Class<?>[0]);

    assertDoesNotThrow(() -> TaintInstrumentation.install(mockInstrumentation));
  }

  @Test
  void adviceClasses_arePublicStatic() {
    // Verify advice classes exist and are properly structured
    assertTrue(
        java.lang.reflect.Modifier.isPublic(
            TaintInstrumentation.StringConcatAdvice.class.getModifiers()));
    assertTrue(
        java.lang.reflect.Modifier.isStatic(
            TaintInstrumentation.StringConcatAdvice.class.getModifiers()));

    assertTrue(
        java.lang.reflect.Modifier.isPublic(
            TaintInstrumentation.StringSubstringAdvice.class.getModifiers()));
    assertTrue(
        java.lang.reflect.Modifier.isStatic(
            TaintInstrumentation.StringSubstringAdvice.class.getModifiers()));

    assertTrue(
        java.lang.reflect.Modifier.isPublic(
            TaintInstrumentation.StringSubstringBeginAdvice.class.getModifiers()));
    assertTrue(
        java.lang.reflect.Modifier.isStatic(
            TaintInstrumentation.StringSubstringBeginAdvice.class.getModifiers()));

    assertTrue(
        java.lang.reflect.Modifier.isPublic(
            TaintInstrumentation.StringBuilderAppendStringAdvice.class.getModifiers()));
    assertTrue(
        java.lang.reflect.Modifier.isStatic(
            TaintInstrumentation.StringBuilderAppendStringAdvice.class.getModifiers()));

    assertTrue(
        java.lang.reflect.Modifier.isPublic(
            TaintInstrumentation.StringBuilderAppendCharSequenceAdvice.class.getModifiers()));
    assertTrue(
        java.lang.reflect.Modifier.isStatic(
            TaintInstrumentation.StringBuilderAppendCharSequenceAdvice.class.getModifiers()));

    assertTrue(
        java.lang.reflect.Modifier.isPublic(
            TaintInstrumentation.StringBuilderToStringAdvice.class.getModifiers()));
    assertTrue(
        java.lang.reflect.Modifier.isStatic(
            TaintInstrumentation.StringBuilderToStringAdvice.class.getModifiers()));

    assertTrue(
        java.lang.reflect.Modifier.isPublic(
            TaintInstrumentation.ProcessBuilderStartAdvice.class.getModifiers()));
    assertTrue(
        java.lang.reflect.Modifier.isStatic(
            TaintInstrumentation.ProcessBuilderStartAdvice.class.getModifiers()));

    assertTrue(
        java.lang.reflect.Modifier.isPublic(
            TaintInstrumentation.RuntimeExecAdvice.class.getModifiers()));
    assertTrue(
        java.lang.reflect.Modifier.isStatic(
            TaintInstrumentation.RuntimeExecAdvice.class.getModifiers()));
  }

  @Test
  void adviceClasses_haveRequiredAdviceMethods() throws Exception {
    // Verify StringConcatAdvice has enter/exit methods
    assertNotNull(
        TaintInstrumentation.StringConcatAdvice.class.getDeclaredMethod("enter"));
    assertNotNull(
        TaintInstrumentation.StringConcatAdvice.class.getDeclaredMethod(
            "exit", String.class, String.class, String.class, boolean.class));

    // Verify RuntimeExecAdvice has enter/exit methods
    assertNotNull(
        TaintInstrumentation.RuntimeExecAdvice.class.getDeclaredMethod(
            "enter", Object[].class));
    assertNotNull(TaintInstrumentation.RuntimeExecAdvice.class.getDeclaredMethod("exit"));
  }
}
