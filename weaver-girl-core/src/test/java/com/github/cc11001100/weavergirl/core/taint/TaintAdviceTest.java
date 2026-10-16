package com.github.cc11001100.weavergirl.core.taint;

import static org.junit.jupiter.api.Assertions.*;

import com.github.cc11001100.weavergirl.api.taint.Taint;
import com.github.cc11001100.weavergirl.api.taint.TaintBridge;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * Tests for TaintInstrumentation advice classes' enter/exit logic.
 *
 * <p>These tests exercise the advice methods directly (without JDK instrumentation)
 * to verify re-entrancy guards, propagation calls, and edge cases.
 */
class TaintAdviceTest {

  @BeforeEach
  void openScope() {
    Taint.openScope();
  }

  @AfterEach
  void closeScope() {
    Taint.closeScope();
  }

  // ========== StringConcatAdvice ==========

  @Test
  void stringConcatAdvice_enter_returnsTrue() {
    boolean active = TaintInstrumentation.StringConcatAdvice.enter();
    assertTrue(active, "First enter should succeed");
    TaintBridge.leave();
  }

  @Test
  void stringConcatAdvice_enter_returnsFalseOnReentry() {
    boolean first = TaintInstrumentation.StringConcatAdvice.enter();
    assertTrue(first);

    boolean second = TaintInstrumentation.StringConcatAdvice.enter();
    assertFalse(second, "Re-entrant enter should return false");

    TaintBridge.leave();
    TaintBridge.leave();
  }

  @Test
  void stringConcatAdvice_exit_doesNothingWhenInactive() {
    // Should not throw even without matching enter
    assertDoesNotThrow(
        () ->
            TaintInstrumentation.StringConcatAdvice.exit("result", "left", "right", false));
  }

  // ========== StringSubstringAdvice ==========

  @Test
  void stringSubstringAdvice_enter_returnsTrue() {
    boolean active = TaintInstrumentation.StringSubstringAdvice.enter();
    assertTrue(active);
    TaintBridge.leave();
  }

  @Test
  void stringSubstringAdvice_exit_doesNothingWhenInactive() {
    assertDoesNotThrow(
        () ->
            TaintInstrumentation.StringSubstringAdvice.exit("result", 0, 5, "source", false));
  }

  // ========== StringSubstringBeginAdvice ==========

  @Test
  void stringSubstringBeginAdvice_enter_returnsTrue() {
    boolean active = TaintInstrumentation.StringSubstringBeginAdvice.enter();
    assertTrue(active);
    TaintBridge.leave();
  }

  @Test
  void stringSubstringBeginAdvice_exit_doesNothingWhenInactive() {
    assertDoesNotThrow(
        () ->
            TaintInstrumentation.StringSubstringBeginAdvice.exit("result", 0, "source", false));
  }

  // ========== StringBuilderAppendStringAdvice ==========

  @Test
  void stringBuilderAppendStringAdvice_enter_returnsOffset() {
    StringBuilder sb = new StringBuilder("hello");
    int offset = TaintInstrumentation.StringBuilderAppendStringAdvice.enter(sb);
    assertEquals(5, offset);
    TaintBridge.leave();
  }

  @Test
  void stringBuilderAppendStringAdvice_enter_returnsMinValueOnReentry() {
    StringBuilder sb = new StringBuilder("test");
    int first = TaintInstrumentation.StringBuilderAppendStringAdvice.enter(sb);
    assertEquals(4, first);

    int second = TaintInstrumentation.StringBuilderAppendStringAdvice.enter(sb);
    assertEquals(Integer.MIN_VALUE, second, "Re-entrant should return sentinel");

    TaintBridge.leave();
    TaintBridge.leave();
  }

  @Test
  void stringBuilderAppendStringAdvice_exit_doesNothingWhenSentinel() {
    StringBuilder sb = new StringBuilder("base");
    assertDoesNotThrow(
        () ->
            TaintInstrumentation.StringBuilderAppendStringAdvice.exit(sb, "appended", Integer.MIN_VALUE));
  }

  // ========== StringBuilderAppendCharSequenceAdvice ==========

  @Test
  void stringBuilderAppendCharSequenceAdvice_skipsString() {
    StringBuilder sb = new StringBuilder("hello");
    // String should be skipped (delegates to append(String))
    int offset =
        TaintInstrumentation.StringBuilderAppendCharSequenceAdvice.enter(sb, "world");
    assertEquals(Integer.MIN_VALUE, offset);
  }

  @Test
  void stringBuilderAppendCharSequenceAdvice_entersForNonString() {
    StringBuilder sb = new StringBuilder("hello");
    CharSequence cs = new StringBuilder("world");
    int offset =
        TaintInstrumentation.StringBuilderAppendCharSequenceAdvice.enter(sb, cs);
    assertEquals(5, offset);
    TaintBridge.leave();
  }

  @Test
  void stringBuilderAppendCharSequenceAdvice_exit_doesNothingWhenSentinel() {
    StringBuilder sb = new StringBuilder("base");
    assertDoesNotThrow(
        () ->
            TaintInstrumentation.StringBuilderAppendCharSequenceAdvice.exit(
                sb, "appended", Integer.MIN_VALUE));
  }

  // ========== StringBuilderToStringAdvice ==========

  @Test
  void stringBuilderToStringAdvice_enter_returnsTrue() {
    boolean active = TaintInstrumentation.StringBuilderToStringAdvice.enter();
    assertTrue(active);
    TaintBridge.leave();
  }

  @Test
  void stringBuilderToStringAdvice_exit_doesNothingWhenInactive() {
    StringBuilder sb = new StringBuilder("content");
    assertDoesNotThrow(
        () ->
            TaintInstrumentation.StringBuilderToStringAdvice.exit(sb, "result", false));
  }

  // ========== ProcessBuilderStartAdvice ==========

  @Test
  void processBuilderStartAdvice_enter_observesCommand() {
    ProcessBuilder pb = new ProcessBuilder("echo", "hello");
    boolean shouldSuppress =
        TaintInstrumentation.ProcessBuilderStartAdvice.enter(pb);
    // suppressProcessStart defaults to false
    assertFalse(shouldSuppress);
    TaintBridge.endExecObservation();
  }

  @Test
  void processBuilderStartAdvice_exit_endsObservation() {
    assertDoesNotThrow(
        () -> TaintInstrumentation.ProcessBuilderStartAdvice.exit());
  }

  // ========== RuntimeExecAdvice ==========

  @Test
  void runtimeExecAdvice_enter_observesCommandArgs() {
    Object[] args = {"ls", "-la"};
    boolean shouldSuppress =
        TaintInstrumentation.RuntimeExecAdvice.enter(args);
    assertFalse(shouldSuppress);
    TaintBridge.endExecObservation();
  }

  @Test
  void runtimeExecAdvice_enter_handlesNullArgs() {
    boolean shouldSuppress =
        TaintInstrumentation.RuntimeExecAdvice.enter(null);
    assertFalse(shouldSuppress);
    TaintBridge.endExecObservation();
  }

  @Test
  void runtimeExecAdvice_exit_endsObservation() {
    assertDoesNotThrow(() -> TaintInstrumentation.RuntimeExecAdvice.exit());
  }

  @Test
  void runtimeExecAdvice_enter_observesStringArrayArg() {
    Object[] args = {new String[] {"cmd", "arg1"}};
    boolean shouldSuppress =
        TaintInstrumentation.RuntimeExecAdvice.enter(args);
    assertFalse(shouldSuppress);
    TaintBridge.endExecObservation();
  }
}
