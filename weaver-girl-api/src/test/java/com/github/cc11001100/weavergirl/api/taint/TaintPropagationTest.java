package com.github.cc11001100.weavergirl.api.taint;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;

import java.util.List;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/** Propagation through the entry the string hooks call. */
class TaintPropagationTest {

  @BeforeEach
  void open() {
    Taint.openScope();
  }

  @AfterEach
  void close() {
    Taint.closeScope();
  }

  @Test
  void concatKeepsCleanEdgesAndTwoSources() {
    String prefix = "pre:";
    String left = "AAA";
    String right = "BBB";
    String suffix = ":suf";
    Taint.tag(left, "source-left");
    Taint.tag(right, "source-right");

    String combined = prefix + left + right + suffix;
    TaintPropagation.propagateConcat(combined, prefix, left, right, suffix);

    List<TaintRange> ranges = Taint.ranges(combined);
    assertEquals(2, ranges.size());
    assertEquals(prefix.length(), ranges.get(0).getStart());
    assertEquals(prefix.length() + left.length(), ranges.get(0).getEnd());
    assertEquals("source-left", ranges.get(0).getSource());
    assertEquals(prefix.length() + left.length(), ranges.get(1).getStart());
    assertEquals(prefix.length() + left.length() + right.length(), ranges.get(1).getEnd());
    assertEquals("source-right", ranges.get(1).getSource());
    assertTrue(ranges.get(0).getStart() > 0, "clean prefix stays outside every tainted range");
    assertTrue(ranges.get(1).getEnd() < combined.length(), "clean suffix stays clean");
    assertFalse(Taint.isTainted(prefix));
    assertFalse(Taint.isTainted(suffix));

    // Null and empty inputs are dropped rather than recorded.
    assertDoesNotThrow(() -> TaintPropagation.propagateConcat(null, left));
    assertDoesNotThrow(() -> TaintPropagation.propagateConcat(combined, (CharSequence[]) null));
    assertDoesNotThrow(() -> TaintPropagation.propagateConcat("x", (CharSequence) null));
    assertDoesNotThrow(() -> TaintPropagation.propagateSubstring(null, left, 0, 1));
    assertDoesNotThrow(() -> TaintPropagation.propagateSubstring("x", null, 0));
    assertDoesNotThrow(() -> TaintPropagation.propagateAppend(null, left, 0));
    assertDoesNotThrow(() -> TaintPropagation.propagateAppendSlice(new StringBuilder(), left, 2, 1, 0));
    assertDoesNotThrow(() -> TaintPropagation.propagateToString(null, left));
    System.out.println(
        "IAST propagate concat source="
            + ranges.get(0).getSource()
            + " source="
            + ranges.get(1).getSource());
  }

  @Test
  void substringKeepsOnlyOverlappingTaintedCharacters() {
    String clean = "CLEAN";
    String dirty = "DIRTY";
    String tail = "TAIL";
    Taint.tag(dirty, "origin");
    String all = clean + dirty + tail;
    TaintPropagation.propagateConcat(all, clean, dirty, tail);

    int begin = 3;
    int end = 8;
    String sub = all.substring(begin, end);
    TaintPropagation.propagateSubstring(sub, all, begin, end);

    List<TaintRange> ranges = Taint.ranges(sub);
    assertEquals(1, ranges.size());
    assertEquals("origin", ranges.get(0).getSource());
    assertEquals(2, ranges.get(0).getStart());
    assertEquals(5, ranges.get(0).getEnd());
    assertEquals(sub.length(), end - begin);
    System.out.println("IAST propagate substring source=" + ranges.get(0).getSource());
  }

  @Test
  void stringBuilderAppendAndToStringCarryTheOrigin() {
    String prefix = "pre:";
    String dirty = "IN";
    String suffix = ":post";
    Taint.tag(dirty, "builder-origin");

    StringBuilder builder = new StringBuilder();
    builder.append(prefix);
    TaintPropagation.propagateAppend(builder, prefix, 0);
    int offset = builder.length();
    builder.append(dirty);
    TaintPropagation.propagateAppend(builder, dirty, offset);
    builder.append(suffix);
    TaintPropagation.propagateAppend(builder, suffix, offset + dirty.length());

    String result = builder.toString();
    TaintPropagation.propagateToString(result, builder);

    List<TaintRange> ranges = Taint.ranges(result);
    assertEquals(1, ranges.size());
    assertEquals("builder-origin", ranges.get(0).getSource());
    assertEquals(prefix.length(), ranges.get(0).getStart());
    assertEquals(prefix.length() + dirty.length(), ranges.get(0).getEnd());
    assertTrue(ranges.get(0).getEnd() < result.length());

    StringBuilder sliced = new StringBuilder();
    TaintPropagation.propagateAppendSlice(sliced, dirty, 0, dirty.length(), 0);
    assertEquals("builder-origin", Taint.ranges(sliced).get(0).getSource());
    System.out.println("IAST propagate stringbuilder source=" + ranges.get(0).getSource());
  }
}
