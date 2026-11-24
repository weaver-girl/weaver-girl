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

  @Test
  void genericPropagateCopiesEveryLabelOntoTheWholeTarget() {
    String source = new String("source");
    Taint.tag(source, "http.header:x-user");
    String target = "derived";
    Taint.propagate(source, target);

    assertTrue(Taint.isTainted(target));
    List<TaintRange> ranges = Taint.ranges(target);
    assertEquals(1, ranges.size());
    assertEquals(0, ranges.get(0).getStart());
    assertEquals(target.length(), ranges.get(0).getEnd());
    assertEquals("http.header:x-user", ranges.get(0).getSource());

    Integer boxed = Integer.valueOf(7);
    Taint.propagate(source, boxed);
    assertTrue(Taint.isTainted(boxed), "a non-CharSequence target keeps an object-level mark");

    String clean = "y";
    Taint.propagate("untagged", clean);
    assertFalse(Taint.isTainted(clean));
  }

  @Test
  void transformPropagationCoversTheWholeResult() {
    String dirty = new String("  PAYLOAD  ");
    Taint.tag(dirty, "http.parameter:q");

    String trimmed = dirty.trim();
    TaintPropagation.propagateTransform(trimmed, dirty);
    assertEquals("http.parameter:q", Taint.ranges(trimmed).get(0).getSource());
    assertEquals(0, Taint.ranges(trimmed).get(0).getStart());
    assertEquals(trimmed.length(), Taint.ranges(trimmed).get(0).getEnd());

    String replaced = trimmed.replace("PAYLOAD", "X");
    TaintPropagation.propagateTransform(replaced, trimmed);
    assertTrue(Taint.isTainted(replaced));

    String cleanResult = "clean".toUpperCase();
    TaintPropagation.propagateTransform(cleanResult, "clean");
    assertFalse(Taint.isTainted(cleanResult));
  }

  @Test
  void joinPropagationFlattensArraysAndIterables() {
    String dirty = new String("b");
    Taint.tag(dirty, "http.parameter:parts");

    String joined = "a,b,c";
    TaintPropagation.propagateJoin(joined, ",", new Object[] {"a", dirty, "c"});
    List<TaintRange> ranges = Taint.ranges(joined);
    assertEquals(1, ranges.size());
    assertEquals(0, ranges.get(0).getStart());
    assertEquals(joined.length(), ranges.get(0).getEnd());

    String joinedIterable = "a-b";
    TaintPropagation.propagateJoin(joinedIterable, "-", java.util.Arrays.asList("a", dirty));
    assertTrue(Taint.isTainted(joinedIterable));
  }

  @Test
  void builderDeleteShiftsAndSplitsRanges() {
    String dirty = new String("payload");
    Taint.tag(dirty, "http.parameter:p");
    StringBuilder builder = new StringBuilder("ab").append(dirty);
    TaintPropagation.propagateAppend(builder, dirty, 2);
    assertEquals(2, Taint.ranges(builder).get(0).getStart());

    int before = builder.length();
    builder.delete(0, 1);
    TaintPropagation.propagateDelete(builder, 0, 1, before);
    List<TaintRange> ranges = Taint.ranges(builder);
    assertEquals(1, ranges.size());
    assertEquals(1, ranges.get(0).getStart());
    assertEquals(1 + dirty.length(), ranges.get(0).getEnd());

    // A cut that lands inside the tainted payload keeps only the surviving half.
    StringBuilder cut = new StringBuilder(dirty);
    TaintPropagation.propagateAppend(cut, dirty, 0);
    int cutBefore = cut.length();
    cut.delete(0, 3);
    TaintPropagation.propagateDelete(cut, 0, 3, cutBefore);
    ranges = Taint.ranges(cut);
    assertEquals(1, ranges.size());
    assertEquals(0, ranges.get(0).getStart());
    assertEquals(dirty.length() - 3, ranges.get(0).getEnd());
  }

  @Test
  void builderInsertShiftsExistingRangesAndCopiesTheInsertedValue() {
    String dirty = new String("payload");
    Taint.tag(dirty, "http.parameter:p");
    StringBuilder shifted = new StringBuilder(dirty);
    TaintPropagation.propagateAppend(shifted, dirty, 0);
    int before = shifted.length();
    shifted.insert(0, "xx");
    TaintPropagation.propagateInsert(shifted, 0, "xx", before);
    assertEquals(2, Taint.ranges(shifted).get(0).getStart());
    assertEquals(2 + dirty.length(), Taint.ranges(shifted).get(0).getEnd());

    StringBuilder grown = new StringBuilder("head");
    int grownBefore = grown.length();
    grown.insert(2, dirty);
    TaintPropagation.propagateInsert(grown, 2, dirty, grownBefore);
    List<TaintRange> ranges = Taint.ranges(grown);
    assertEquals(1, ranges.size());
    assertEquals(2, ranges.get(0).getStart());
    assertEquals(2 + dirty.length(), ranges.get(0).getEnd());
    assertEquals("http.parameter:p", ranges.get(0).getSource());
  }

  @Test
  void builderReplaceMovesTheSurvivingAndInsertedText() {
    String dirty = new String("payload");
    Taint.tag(dirty, "http.parameter:p");
    StringBuilder builder = new StringBuilder("a").append(dirty).append("z");
    TaintPropagation.propagateAppend(builder, dirty, 1);

    int before = builder.length();
    String inserted = new String("inserted");
    Taint.tag(inserted, "http.header:h");
    builder.replace(0, 1, inserted);
    TaintPropagation.propagateReplace(builder, 0, 1, inserted, before);

    TaintRange header = null;
    TaintRange payload = null;
    for (TaintRange range : Taint.ranges(builder)) {
      if ("http.header:h".equals(range.getSource())) {
        header = range;
      } else if ("http.parameter:p".equals(range.getSource())) {
        payload = range;
      }
    }
    assertTrue(header != null && payload != null, "both the inserted and the surviving text keep their labels");
    assertEquals(0, header.getStart());
    assertEquals(inserted.length(), header.getEnd());
    assertEquals(inserted.length(), payload.getStart());
    assertEquals(inserted.length() + dirty.length(), payload.getEnd());
  }

  @Test
  void builderSetLengthClipsAndReverseMirrorsRanges() {
    String dirty = new String("payload");
    Taint.tag(dirty, "http.parameter:p");

    StringBuilder clipped = new StringBuilder(dirty);
    TaintPropagation.propagateAppend(clipped, dirty, 0);
    TaintPropagation.propagateSetLength(clipped, 3);
    List<TaintRange> ranges = Taint.ranges(clipped);
    assertEquals(1, ranges.size());
    assertEquals(0, ranges.get(0).getStart());
    assertEquals(3, ranges.get(0).getEnd());

    StringBuilder reversed = new StringBuilder("0123456789");
    TaintPropagation.propagateAppend(reversed, dirty, 2);
    TaintPropagation.propagateReverse(reversed);
    ranges = Taint.ranges(reversed);
    assertEquals(1, ranges.size());
    assertEquals(10 - (2 + dirty.length()), ranges.get(0).getStart());
    assertEquals(10 - 2, ranges.get(0).getEnd());
  }

  @Test
  void appendObjectAndCharArrayMoveTheMarksToTheAppendedWindow() {
    Integer number = Integer.valueOf(7);
    Taint.tag(number, "numeric-source");

    StringBuilder withObject = new StringBuilder("pre:");
    TaintPropagation.propagateAppendObject(withObject, number, 4, 1);
    List<TaintRange> ranges = Taint.ranges(withObject);
    assertEquals(1, ranges.size());
    assertEquals(4, ranges.get(0).getStart());
    assertEquals(5, ranges.get(0).getEnd());
    assertEquals("numeric-source", ranges.get(0).getSource());

    String dirty = new String("payload");
    Taint.tag(dirty, "http.parameter:p");
    char[] chars = dirty.toCharArray();
    TaintPropagation.propagateToCharArray(chars, dirty);
    List<TaintRange> charRanges = Taint.ranges(chars);
    assertEquals(1, charRanges.size());
    assertEquals(0, charRanges.get(0).getStart());
    assertEquals(dirty.length(), charRanges.get(0).getEnd());

    StringBuilder withArray = new StringBuilder("x:");
    TaintPropagation.propagateAppendArray(withArray, chars, 2, 1, 3);
    ranges = Taint.ranges(withArray);
    assertEquals(1, ranges.size());
    assertEquals(2, ranges.get(0).getStart());
    assertEquals(5, ranges.get(0).getEnd());
    System.out.println(
        "IAST append object-source="
            + ranges.get(0).getSource()
            + " start="
            + ranges.get(0).getStart());
  }

  @Test
  void stringFromCharArrayKeepsOnlyTheCopiedWindow() {
    String dirty = new String("abcdef");
    Taint.tag(dirty, "origin");
    char[] chars = dirty.toCharArray();
    TaintPropagation.propagateToCharArray(chars, dirty);

    String copied = new String(chars, 1, 3);
    TaintPropagation.propagateFromArray(
        copied, new Object[] {chars, Integer.valueOf(1), Integer.valueOf(3)});

    List<TaintRange> ranges = Taint.ranges(copied);
    assertEquals(1, ranges.size());
    assertEquals(0, ranges.get(0).getStart());
    assertEquals(3, ranges.get(0).getEnd());
    assertEquals("origin", ranges.get(0).getSource());
    System.out.println("IAST char-array round-trip " + copied + " range=" + ranges.get(0).getEnd());
  }

  @Test
  void stringFromByteArrayInheritsTheWholeMark() {
    byte[] bytes = new byte[] {1, 2, 3};
    Taint.tag(bytes, "binary-source");
    String decoded = new String(bytes);
    TaintPropagation.propagateFromArray(decoded, new Object[] {bytes});

    List<TaintRange> ranges = Taint.ranges(decoded);
    assertEquals(1, ranges.size());
    assertEquals(0, ranges.get(0).getStart());
    assertEquals(decoded.length(), ranges.get(0).getEnd());
    assertEquals("binary-source", ranges.get(0).getSource());
  }

  @Test
  void setCharAtRemovesTheReplacedIndexFromTheRange() {
    String dirty = new String("abcde");
    Taint.tag(dirty, "origin");
    StringBuilder builder = new StringBuilder(dirty);
    TaintPropagation.propagateAppend(builder, dirty, 0);

    TaintPropagation.propagateSetCharAt(builder, 2);

    List<TaintRange> ranges = Taint.ranges(builder);
    assertEquals(2, ranges.size());
    assertEquals(0, ranges.get(0).getStart());
    assertEquals(2, ranges.get(0).getEnd());
    assertEquals(3, ranges.get(1).getStart());
    assertEquals(5, ranges.get(1).getEnd());
    System.out.println("IAST setCharAt split ranges=" + ranges.size());
  }

  @Test
  void getCharsWritesTheWindowIntoTheDestinationArray() {
    String dirty = new String("abcdef");
    Taint.tag(dirty, "origin");
    char[] dest = new char[10];
    TaintPropagation.propagateToCharArray(dest, dirty, 1, 4, 2);

    List<TaintRange> ranges = Taint.ranges(dest);
    assertEquals(1, ranges.size());
    assertEquals(2, ranges.get(0).getStart());
    assertEquals(5, ranges.get(0).getEnd());
    assertEquals("origin", ranges.get(0).getSource());

    // A second write into the same array replaces only its own window.
    String clean = new String("X");
    TaintPropagation.propagateToCharArray(dest, clean, 0, 1, 2);
    ranges = Taint.ranges(dest);
    assertTrue(ranges.isEmpty(), "the rewritten window drops the mark that covered it");

    TaintPropagation.propagateToCharArray(dest, dirty, 0, 2, 8);
    ranges = Taint.ranges(dest);
    assertEquals(1, ranges.size());
    assertEquals(8, ranges.get(0).getStart());
    assertEquals(10, ranges.get(0).getEnd());
    System.out.println("IAST getChars window start=" + ranges.get(0).getStart());
  }

  @Test
  void insertArrayRangeMapsOnlyTheCopiedElements() {
    String dirty = new String("abcdef");
    Taint.tag(dirty, "origin");
    char[] chars = dirty.toCharArray();
    TaintPropagation.propagateToCharArray(chars, dirty);

    StringBuilder builder = new StringBuilder("ab");
    builder.insert(1, chars, 2, 3);
    TaintPropagation.propagateInsertArray(builder, 1, chars, 2, 3, 2);

    List<TaintRange> ranges = Taint.ranges(builder);
    assertEquals(1, ranges.size());
    assertEquals(1, ranges.get(0).getStart());
    assertEquals(4, ranges.get(0).getEnd());
    assertEquals("origin", ranges.get(0).getSource());
    System.out.println("IAST insert array window=" + builder);
  }

  @Test
  void newHelpersTolerateNullsAndEmptyWindows() {
    assertDoesNotThrow(() -> TaintPropagation.propagateAppendObject(null, "x", 0, 1));
    assertDoesNotThrow(() -> TaintPropagation.propagateAppendObject(new StringBuilder(), null, 0, 1));
    assertDoesNotThrow(() -> TaintPropagation.propagateAppendArray(null, new char[1], 0, 0, 1));
    assertDoesNotThrow(() -> TaintPropagation.propagateAppendArray(new StringBuilder(), null, 0, 0, 1));
    assertDoesNotThrow(() -> TaintPropagation.propagateToCharArray(null, "x"));
    assertDoesNotThrow(() -> TaintPropagation.propagateToCharArray(null, "x", 0, 1, 0));
    assertDoesNotThrow(
        () -> TaintPropagation.propagateToCharArray(new char[1], "x", 5, 9, 0));
    assertDoesNotThrow(
        () -> TaintPropagation.propagateInsertArray(null, 0, new char[1], 0, 1, 0));
    assertDoesNotThrow(
        () -> TaintPropagation.propagateInsertArray(new StringBuilder(), 0, null, 0, 1, 0));
    assertDoesNotThrow(() -> TaintPropagation.propagateFromCharArray(null, new char[1], 0, 1));
    assertDoesNotThrow(() -> TaintPropagation.propagateFromArray(null, new Object[] {"x"}));
    assertDoesNotThrow(() -> TaintPropagation.propagateSetCharAt(null, 0));
  }
}
