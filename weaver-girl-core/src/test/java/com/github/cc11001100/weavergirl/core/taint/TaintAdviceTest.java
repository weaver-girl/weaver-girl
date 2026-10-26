package com.github.cc11001100.weavergirl.core.taint;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.github.cc11001100.weavergirl.api.taint.TaintBridge;
import com.github.cc11001100.weavergirl.core.taint.TaintInstrumentation.ProcessBuilderStartAdvice;
import com.github.cc11001100.weavergirl.core.taint.TaintInstrumentation.RuntimeExecAdvice;
import com.github.cc11001100.weavergirl.core.taint.TaintInstrumentation.StringBuilderAppendCharSequenceAdvice;
import com.github.cc11001100.weavergirl.core.taint.TaintInstrumentation.StringBuilderAppendStringAdvice;
import com.github.cc11001100.weavergirl.core.taint.TaintInstrumentation.StringBuilderToStringAdvice;
import com.github.cc11001100.weavergirl.core.taint.TaintInstrumentation.StringConcatAdvice;
import com.github.cc11001100.weavergirl.core.taint.TaintInstrumentation.StringSubstringAdvice;
import com.github.cc11001100.weavergirl.core.taint.TaintInstrumentation.StringSubstringBeginAdvice;
import java.lang.instrument.Instrumentation;
import java.lang.reflect.Method;
import java.util.Arrays;
import net.bytebuddy.agent.ByteBuddyAgent;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

/**
 * The taint advice is inlined into {@code java.lang.String} and friends, so JaCoCo counts the probes
 * that fire there against those JDK classes. The advice methods are ordinary {@code public static}
 * methods; calling them directly runs the bytecode the report measures. Each one guards on {@link
 * TaintBridge#tryEnter()}, which stays stuck after a call that returns true, so every test releases
 * it again.
 */
class TaintAdviceTest {

  @AfterEach
  void releaseTheReentrancyGuard() {
    TaintBridge.leave();
    TaintBridge.endExecObservation();
  }

  @Test
  void stringAdvicePropagatesWhenActiveAndSkipsWhenItIsNot() {
    assertTrue(StringConcatAdvice.enter());
    // The guard is now held, so a nested entry bails out.
    assertFalse(StringConcatAdvice.enter());
    TaintBridge.leave();

    StringConcatAdvice.exit("ab", "cd", "abcd", false);
    StringConcatAdvice.exit("ab", "cd", "abcd", true);

    assertTrue(StringSubstringAdvice.enter());
    TaintBridge.leave();
    StringSubstringAdvice.exit("abcd", 1, 3, "bc", false);
    StringSubstringAdvice.exit("abcd", 1, 3, "bc", true);

    assertTrue(StringSubstringBeginAdvice.enter());
    TaintBridge.leave();
    StringSubstringBeginAdvice.exit("abcd", 2, "cd", false);
    StringSubstringBeginAdvice.exit("abcd", 2, "cd", true);

    assertTrue(StringBuilderToStringAdvice.enter());
    TaintBridge.leave();
    StringBuilder builder = new StringBuilder("ab");
    StringBuilderToStringAdvice.exit(builder, "ab", false);
    StringBuilderToStringAdvice.exit(builder, "ab", true);
  }

  @Test
  void appendAdviceRecordsTheLengthBeforeTheAppendAndSkipsANestedCall() {
    StringBuilder builder = new StringBuilder("ab");

    int offset = StringBuilderAppendStringAdvice.enter(builder);
    assertEquals(2, offset);
    // Held by the entry above, so a second one reports "not entered".
    assertEquals(Integer.MIN_VALUE, StringBuilderAppendStringAdvice.enter(builder));
    TaintBridge.leave();

    StringBuilderAppendStringAdvice.exit(builder, "cd", Integer.MIN_VALUE);
    StringBuilderAppendStringAdvice.exit(builder, "cd", 2);

    // append(CharSequence) does nothing for a String; that overload forwards to append(String).
    assertEquals(Integer.MIN_VALUE, StringBuilderAppendCharSequenceAdvice.enter(builder, "cd"));
    int charSequenceOffset =
        StringBuilderAppendCharSequenceAdvice.enter(builder, new StringBuilder("cd"));
    assertEquals(2, charSequenceOffset);
    TaintBridge.leave();

    StringBuilderAppendCharSequenceAdvice.exit(builder, "cd", Integer.MIN_VALUE);
    StringBuilderAppendCharSequenceAdvice.exit(builder, new StringBuilder("cd"), 2);
  }

  @Test
  void commandAdviceObservesEachArgumentShapeAndANestedCallObservesNothing() {
    ProcessBuilder process = new ProcessBuilder("echo", "hi");
    assertFalse(ProcessBuilderStartAdvice.enter(process));
    // The outer frame still owns the guard, so the nested call does not observe again.
    assertFalse(ProcessBuilderStartAdvice.enter(process));
    ProcessBuilderStartAdvice.exit();
    ProcessBuilderStartAdvice.exit();

    assertFalse(RuntimeExecAdvice.enter(null));
    RuntimeExecAdvice.exit();
    assertFalse(
        RuntimeExecAdvice.enter(new Object[] {"ls", new String[] {"-l"}, Arrays.asList("a", "b")}));
    RuntimeExecAdvice.exit();
  }

  @Test
  void aBridgeClassThatIsAlreadyLoadedIsRecognized() throws Exception {
    Instrumentation instrumentation = ByteBuddyAgent.install();
    Method alreadyLoaded =
        TaintInstrumentation.class.getDeclaredMethod(
            "alreadyLoaded", Instrumentation.class, String.class);
    alreadyLoaded.setAccessible(true);
    assertTrue((Boolean) alreadyLoaded.invoke(null, instrumentation, "java.lang.String"));
    assertFalse((Boolean) alreadyLoaded.invoke(null, instrumentation, "no.such.Missing"));
  }
}
