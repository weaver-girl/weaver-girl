package com.github.cc11001100.weavergirl.api.taint;

import java.util.ArrayList;
import java.util.List;

/**
 * Propagation entry used by string and sink advice. Range math lives here so hooks and tests share
 * one implementation: concatenation, substring, and {@code StringBuilder} append / {@code toString}.
 *
 * @since 1.0.0
 */
public final class TaintPropagation {

  private TaintPropagation() {}

  /** Shift each part's ranges by the running length. Clean parts advance the offset only. */
  public static void propagateConcat(String result, CharSequence... parts) {
    if (result == null || parts == null) {
      return;
    }
    ArrayList<TaintBridge.Range> out = new ArrayList<TaintBridge.Range>();
    int offset = 0;
    for (CharSequence part : parts) {
      if (part == null) {
        continue;
      }
      copyShifted(out, part, offset, 0, part.length());
      offset += part.length();
    }
    TaintBridge.setRanges(result, out);
  }

  public static void propagateSubstring(String result, CharSequence source, int begin, int end) {
    if (result == null || source == null || begin < 0 || end < begin) {
      return;
    }
    ArrayList<TaintBridge.Range> out = new ArrayList<TaintBridge.Range>();
    copyShifted(out, source, -begin, begin, end);
    TaintBridge.setRanges(result, out);
  }

  public static void propagateSubstring(String result, CharSequence source, int begin) {
    if (source == null) {
      return;
    }
    propagateSubstring(result, source, begin, source.length());
  }

  /** {@code offset} is the builder length before the append. */
  public static void propagateAppend(CharSequence builder, CharSequence appended, int offset) {
    if (builder == null || appended == null || offset < 0) {
      return;
    }
    ArrayList<TaintBridge.Range> out = new ArrayList<TaintBridge.Range>();
    copyShifted(out, appended, offset, 0, appended.length());
    TaintBridge.addRanges(builder, out);
  }

  public static void propagateAppendSlice(
      CharSequence builder, CharSequence appended, int start, int end, int offset) {
    if (builder == null || appended == null || offset < 0 || start < 0 || end < start) {
      return;
    }
    ArrayList<TaintBridge.Range> out = new ArrayList<TaintBridge.Range>();
    copyShifted(out, appended, offset - start, start, end);
    TaintBridge.addRanges(builder, out);
  }

  /**
   * {@code StringBuilder.append(Object)} / {@code StringBuffer.append(Object)}. The appended text
   * is not a string the advice saw, so only its length is known; a mark on the object covers that
   * whole appended window.
   *
   * @param builder the builder that received the value
   * @param appended the appended value, possibly not a {@link CharSequence}
   * @param offset the builder length before the append
   * @param appendedLength how many characters the append added
   * @since 1.0.0
   */
  public static void propagateAppendObject(
      CharSequence builder, Object appended, int offset, int appendedLength) {
    if (builder == null || appended == null || offset < 0 || appendedLength <= 0) {
      return;
    }
    ArrayList<TaintBridge.Range> out = new ArrayList<TaintBridge.Range>();
    if (appended instanceof CharSequence) {
      copyShifted(out, appended, offset, 0, appendedLength);
    } else {
      copyLenient(out, appended, offset, 0, appendedLength);
    }
    TaintBridge.addRanges(builder, out);
  }

  /**
   * {@code append(char[])}, {@code append(char[], int, int)} and the same pair on
   * {@code StringBuffer}: slices of the array (usually from {@code String.toCharArray()}) move to
   * the appended window; an object-level mark covers that window whole.
   *
   * @param builder the builder that received the array
   * @param array the appended array
   * @param offset the builder length before the append
   * @param arrayOffset first copied index of the array
   * @param count copied characters
   * @since 1.0.0
   */
  public static void propagateAppendArray(
      CharSequence builder, Object array, int offset, int arrayOffset, int count) {
    if (builder == null || array == null || offset < 0) {
      return;
    }
    int from = Math.max(0, arrayOffset);
    int to = from + Math.max(0, count);
    ArrayList<TaintBridge.Range> out = new ArrayList<TaintBridge.Range>();
    copyLenient(out, array, offset - from, from, to);
    TaintBridge.addRanges(builder, out);
  }

  /**
   * {@code String.toCharArray()}: the array mirrors the string one character per element, so the
   * ranges can be copied unchanged.
   *
   * @param result the new character array
   * @param source the string it was copied from
   * @since 1.0.0
   */
  public static void propagateToCharArray(char[] result, CharSequence source) {
    if (result == null || source == null) {
      return;
    }
    ArrayList<TaintBridge.Range> out = new ArrayList<TaintBridge.Range>();
    copyShifted(out, source, 0, 0, Math.min(result.length, source.length()));
    TaintBridge.setRanges(result, out);
  }

  /**
   * {@code String.getChars(int, int, char[], int)}: the copied window lands at {@code destOffset}
   * in a caller-owned array. Marks that already covered the written window are dropped (the new
   * characters replaced them) and everything outside it is kept.
   *
   * @param dest the destination array
   * @param source the string the characters came from
   * @param srcBegin first copied index of the source
   * @param srcEnd one past the last copied source index
   * @param destOffset first written index of the destination
   * @since 1.0.0
   */
  public static void propagateToCharArray(
      char[] dest, CharSequence source, int srcBegin, int srcEnd, int destOffset) {
    if (dest == null || source == null || destOffset < 0) {
      return;
    }
    int from = Math.max(0, srcBegin);
    int to = Math.min(source.length(), Math.max(from, srcEnd));
    int length = Math.min(to - from, dest.length - destOffset);
    if (length <= 0) {
      return;
    }
    int windowEnd = destOffset + length;
    ArrayList<TaintBridge.Range> out = new ArrayList<TaintBridge.Range>();
    ArrayList<TaintBridge.Range> existing = TaintBridge.lookup(dest);
    if (existing != null) {
      for (int i = 0; i < existing.size(); i++) {
        TaintBridge.Range range = existing.get(i);
        boolean objectLevel = range.end <= range.start;
        if (objectLevel || range.end <= destOffset || range.start >= windowEnd) {
          out.add(range);
        }
      }
    }
    copyShifted(out, source, destOffset - from, from, from + length);
    TaintBridge.setRanges(dest, out);
  }

  /**
   * {@code new String(char[])} and {@code new String(char[], int, int)}: the string inherits the
   * slice the copied window covered.
   *
   * @param result the new string
   * @param source the character array
   * @param offset first copied index
   * @param count copied characters
   * @since 1.0.0
   */
  public static void propagateFromCharArray(String result, char[] source, int offset, int count) {
    if (result == null || source == null) {
      return;
    }
    int from = Math.max(0, offset);
    int to = Math.min(source.length, from + Math.max(0, count));
    ArrayList<TaintBridge.Range> out = new ArrayList<TaintBridge.Range>();
    copyLenient(out, source, -from, from, to);
    TaintBridge.setRanges(result, out);
  }

  /**
   * Dispatches a {@code String}-constructor argument list: the first argument is the array, and an
   * optional {@code (int offset, int count)} pair follows. A {@code char[]} keeps slices; a
   * {@code byte[]} may have a different length after decoding, so the whole result inherits the
   * marks.
   *
   * @param result the new string
   * @param args the constructor arguments as seen by the advice
   * @since 1.0.0
   */
  public static void propagateFromArray(String result, Object[] args) {
    if (result == null || args == null || args.length == 0) {
      return;
    }
    Object source = args[0];
    if (source instanceof char[]) {
      char[] chars = (char[]) source;
      int offset = 0;
      int count = chars.length;
      if (args.length >= 3 && args[1] instanceof Integer && args[2] instanceof Integer) {
        offset = ((Integer) args[1]).intValue();
        count = ((Integer) args[2]).intValue();
      }
      propagateFromCharArray(result, chars, offset, count);
    } else if (source instanceof byte[]) {
      ArrayList<TaintBridge.Range> out = new ArrayList<TaintBridge.Range>();
      copyWhole(out, source, result.length());
      TaintBridge.setRanges(result, out);
    }
  }

  /**
   * {@code StringBuilder.setCharAt(int, char)}: the replaced character is a plain primitive, so the
   * mark covering that index is removed and the rest of the range is kept on both sides.
   *
   * @param builder the mutated builder
   * @param index the replaced index
   * @since 1.0.0
   */
  public static void propagateSetCharAt(CharSequence builder, int index) {
    if (builder == null || index < 0 || index >= builder.length()) {
      return;
    }
    ArrayList<TaintBridge.Range> ranges = TaintBridge.lookup(builder);
    ArrayList<TaintBridge.Range> out = new ArrayList<TaintBridge.Range>();
    if (ranges != null) {
      for (TaintBridge.Range range : ranges) {
        if (index < range.start || index >= range.end) {
          out.add(range);
          continue;
        }
        if (range.start < index) {
          out.add(new TaintBridge.Range(range.start, index, range.source));
        }
        if (index + 1 < range.end) {
          out.add(new TaintBridge.Range(index + 1, range.end, range.source));
        }
      }
    }
    TaintBridge.rewriteRanges(builder, out);
  }

  public static void propagateToString(String result, CharSequence builder) {
    if (result == null || builder == null) {
      return;
    }
    int limit = result.length();
    ArrayList<TaintBridge.Range> out = new ArrayList<TaintBridge.Range>();
    copyShifted(out, builder, 0, 0, limit);
    TaintBridge.setRanges(result, out);
  }

  /**
   * Generic propagation for application code a plugin cannot hook precisely: every source label on
   * {@code source} is copied onto the whole of {@code target}. For a non-{@link CharSequence}
   * target the mark is object-level ({@code [0,0)}), the same shape {@link Taint#tag} uses.
   *
   * @param source the value whose labels are copied
   * @param target the value that becomes tainted
   * @since 1.0.0
   */
  public static void propagate(Object source, Object target) {
    if (source == null || target == null) {
      return;
    }
    int length = target instanceof CharSequence ? ((CharSequence) target).length() : 0;
    ArrayList<TaintBridge.Range> out = new ArrayList<TaintBridge.Range>();
    copyWhole(out, source, length);
    TaintBridge.setRanges(target, out);
  }

  /**
   * Whole-result propagation for transforms whose output offsets cannot be reconstructed cheaply
   * ({@code trim}, case conversion, {@code replace}, {@code replaceAll}, {@code replaceFirst}). The
   * result is tainted if any source was, sacrificing slice precision for zero false negatives.
   *
   * @param result the new value
   * @param sources the receiver and arguments
   * @since 1.0.0
   */
  public static void propagateTransform(CharSequence result, Object... sources) {
    if (result == null) {
      return;
    }
    int length = result.length();
    ArrayList<TaintBridge.Range> out = new ArrayList<TaintBridge.Range>();
    if (sources != null) {
      for (Object source : sources) {
        collectSources(out, source, length);
      }
    }
    TaintBridge.setRanges(result, out);
  }

  /**
   * Whole-result propagation for {@code String.join}, whose arguments arrive as two values (a
   * delimiter and an array or {@link Iterable} of parts).
   *
   * @param result the joined value
   * @param parts delimiter and parts, possibly nested one level
   * @since 1.0.0
   */
  public static void propagateJoin(CharSequence result, Object... parts) {
    if (result == null || parts == null) {
      return;
    }
    int length = result.length();
    ArrayList<TaintBridge.Range> out = new ArrayList<TaintBridge.Range>();
    for (Object part : parts) {
      collectSources(out, part, length);
    }
    TaintBridge.setRanges(result, out);
  }

  /**
   * {@code StringBuilder.delete(int, int)} and {@code deleteCharAt(int)}. {@code before} is the
   * builder length captured before the mutation; ranges after {@code end} move left by the actual
   * removed length and a range straddling the cut is split, so no stale slice survives.
   *
   * @since 1.0.0
   */
  public static void propagateDelete(CharSequence builder, int start, int end, int before) {
    if (builder == null || start < 0 || end <= start) {
      return;
    }
    int from = Math.min(start, before);
    int to = Math.min(Math.max(end, from), before);
    if (from >= to) {
      return;
    }
    ArrayList<TaintBridge.Range> out = new ArrayList<TaintBridge.Range>();
    copyAfterEdit(out, builder, from, to, builder.length() - before);
    TaintBridge.rewriteRanges(builder, out);
  }

  /**
   * {@code StringBuilder.insert(int, String)} / {@code insert(int, CharSequence)}. {@code before}
   * is the builder length captured before the mutation.
   *
   * @since 1.0.0
   */
  public static void propagateInsert(
      CharSequence builder, int offset, CharSequence inserted, int before) {
    propagateInsertValue(builder, offset, inserted, before);
  }

  /**
   * {@code insert(int, Object)} / {@code insert(int, char[])} and their overloads: the window the
   * insert added inherits the marks of the inserted value, and the text after it shifts right.
   *
   * @param builder the mutated builder
   * @param offset where the value was inserted
   * @param inserted the inserted value, possibly not a {@link CharSequence}
   * @param before the builder length captured before the mutation
   * @since 1.0.0
   */
  public static void propagateInsertValue(
      CharSequence builder, int offset, Object inserted, int before) {
    if (builder == null || inserted == null || offset < 0) {
      return;
    }
    int at = Math.min(offset, before);
    int insertedLength = builder.length() - before;
    ArrayList<TaintBridge.Range> out = new ArrayList<TaintBridge.Range>();
    copyAfterEdit(out, builder, at, at, insertedLength);
    if (insertedLength > 0) {
      if (inserted instanceof CharSequence) {
        copyShifted(
            out,
            inserted,
            at,
            0,
            Math.min(insertedLength, ((CharSequence) inserted).length()));
      } else {
        copyLenient(out, inserted, at, 0, insertedLength);
      }
    }
    TaintBridge.rewriteRanges(builder, out);
  }

  /**
   * {@code insert(int, char[], int, int)}: only the copied window of the array is inserted, so the
   * source offsets are mapped through {@code start} and {@code count} instead of the whole array.
   *
   * @param builder the mutated builder
   * @param offset where the array was inserted
   * @param inserted the inserted array
   * @param start first copied index of the array
   * @param count copied characters
   * @param before the builder length captured before the mutation
   * @since 1.0.0
   */
  public static void propagateInsertArray(
      CharSequence builder, int offset, char[] inserted, int start, int count, int before) {
    if (builder == null || inserted == null || offset < 0) {
      return;
    }
    int at = Math.min(offset, before);
    int insertedLength = builder.length() - before;
    ArrayList<TaintBridge.Range> out = new ArrayList<TaintBridge.Range>();
    copyAfterEdit(out, builder, at, at, insertedLength);
    if (insertedLength > 0) {
      int from = Math.max(0, start);
      int to = Math.min(inserted.length, from + Math.max(0, count));
      copyLenient(out, inserted, at - from, from, to);
    }
    TaintBridge.rewriteRanges(builder, out);
  }

  /**
   * {@code StringBuilder.replace(int, int, String)}. {@code before} is the builder length captured
   * before the mutation.
   *
   * @since 1.0.0
   */
  public static void propagateReplace(
      CharSequence builder, int start, int end, CharSequence inserted, int before) {
    if (builder == null || start < 0 || end < start) {
      return;
    }
    int from = Math.min(start, before);
    int to = Math.min(Math.max(end, from), before);
    int insertedLength = builder.length() - (before - (to - from));
    ArrayList<TaintBridge.Range> out = new ArrayList<TaintBridge.Range>();
    copyAfterEdit(out, builder, from, to, insertedLength - (to - from));
    if (inserted != null && insertedLength > 0) {
      copyShifted(out, inserted, from, 0, Math.min(insertedLength, inserted.length()));
    }
    TaintBridge.rewriteRanges(builder, out);
  }

  /**
   * {@code StringBuilder.setLength(int)}: ranges are clipped to the new length.
   *
   * @since 1.0.0
   */
  public static void propagateSetLength(CharSequence builder, int newLength) {
    if (builder == null || newLength < 0) {
      return;
    }
    ArrayList<TaintBridge.Range> ranges = TaintBridge.lookup(builder);
    ArrayList<TaintBridge.Range> out = new ArrayList<TaintBridge.Range>();
    if (ranges != null) {
      for (TaintBridge.Range range : ranges) {
        int end = Math.min(range.end, newLength);
        if (range.start < end) {
          out.add(new TaintBridge.Range(range.start, end, range.source));
        }
      }
    }
    TaintBridge.rewriteRanges(builder, out);
  }

  /**
   * {@code StringBuilder.reverse()}: each range is mirrored inside the unchanged length.
   *
   * @since 1.0.0
   */
  public static void propagateReverse(CharSequence builder) {
    if (builder == null) {
      return;
    }
    int length = builder.length();
    ArrayList<TaintBridge.Range> ranges = TaintBridge.lookup(builder);
    ArrayList<TaintBridge.Range> out = new ArrayList<TaintBridge.Range>();
    if (ranges != null) {
      for (TaintBridge.Range range : ranges) {
        int start = Math.max(0, length - range.end);
        int end = Math.min(length, length - range.start);
        if (start < end) {
          out.add(new TaintBridge.Range(start, end, range.source));
        }
      }
    }
    TaintBridge.rewriteRanges(builder, out);
  }

  /**
   * Generic sink check: emits one finding per tainted slice of every value and reports whether any
   * value carried taint. This is the entry a plugin uses for a sink the built-in advice does not
   * know about.
   *
   * @param sinkKind the sink label carried in the finding
   * @param values the values reaching the sink
   * @return true when at least one value was tainted
   * @since 1.0.0
   */
  public static boolean observeSink(String sinkKind, Object... values) {
    if (sinkKind == null || values == null) {
      return false;
    }
    boolean tainted = false;
    for (Object value : values) {
      if (observeSinkValue(sinkKind, value, 0)) {
        tainted = true;
      }
    }
    return tainted;
  }

  /** Flattens arrays, {@link Iterable} and {@link java.util.Map} values so a collection sink works. */
  private static boolean observeSinkValue(String sinkKind, Object value, int depth) {
    if (value == null) {
      return false;
    }
    if (depth < 8 && value instanceof Object[]) {
      boolean tainted = false;
      for (Object element : (Object[]) value) {
        if (observeSinkValue(sinkKind, element, depth + 1)) {
          tainted = true;
        }
      }
      return tainted;
    }
    if (depth < 8 && value instanceof Iterable) {
      boolean tainted = false;
      for (Object element : (Iterable<?>) value) {
        if (observeSinkValue(sinkKind, element, depth + 1)) {
          tainted = true;
        }
      }
      return tainted;
    }
    if (depth < 8 && value instanceof java.util.Map) {
      boolean tainted = false;
      for (Object element : ((java.util.Map<?, ?>) value).values()) {
        if (observeSinkValue(sinkKind, element, depth + 1)) {
          tainted = true;
        }
      }
      return tainted;
    }
    boolean tainted = TaintBridge.isTainted(value);
    TaintBridge.emitValue(sinkKind, value);
    return tainted;
  }

  public static void observeCommand(String argument) {
    TaintBridge.emit(TaintBridge.SINK_COMMAND, argument);
  }

  public static void observeCommandList(List<?> command) {
    if (command == null) {
      return;
    }
    for (Object value : command) {
      observeCommandValue(value);
    }
  }

  public static void observeCommandValue(Object value) {
    if (value instanceof String) {
      observeCommand((String) value);
    } else if (value instanceof String[]) {
      String[] array = (String[]) value;
      for (String element : array) {
        observeCommand(element);
      }
    } else if (value instanceof List) {
      observeCommandList((List<?>) value);
    }
  }

  public static void observeSql(String sql) {
    TaintBridge.emit(TaintBridge.SINK_SQL, sql);
  }

  /** Copy every distinct source label of {@code source} as a mark covering {@code [0,length)}. */
  private static void copyWhole(
      ArrayList<TaintBridge.Range> out, Object source, int length) {
    ArrayList<TaintBridge.Range> ranges = TaintBridge.lookup(source);
    if (ranges == null) {
      return;
    }
    for (TaintBridge.Range range : ranges) {
      if (range.source == null || hasSource(out, range.source)) {
        continue;
      }
      out.add(new TaintBridge.Range(0, length, range.source));
    }
  }

  /** Flattens an array or {@link Iterable} one level, then copies whole marks from each element. */
  private static void collectSources(
      ArrayList<TaintBridge.Range> out, Object value, int length) {
    if (value == null) {
      return;
    }
    if (value instanceof Object[]) {
      for (Object element : (Object[]) value) {
        collectSources(out, element, length);
      }
    } else if (value instanceof Iterable) {
      for (Object element : (Iterable<?>) value) {
        collectSources(out, element, length);
      }
    } else {
      copyWhole(out, value, length);
    }
  }

  /**
   * Recompute builder ranges around the edit {@code [start, end)} whose replacement changes the
   * length by {@code delta}: ranges before stay, ranges after shift by {@code delta}, a straddling
   * range is split. {@code lookup} sees pre-edit coordinates because only this class rewrites them.
   */
  private static void copyAfterEdit(
      ArrayList<TaintBridge.Range> out,
      CharSequence builder,
      int start,
      int end,
      int delta) {
    ArrayList<TaintBridge.Range> ranges = TaintBridge.lookup(builder);
    if (ranges == null) {
      return;
    }
    for (TaintBridge.Range range : ranges) {
      if (range.end <= start) {
        out.add(range);
      } else if (range.start >= end) {
        addShifted(out, range.start + delta, range.end + delta, range.source);
      } else {
        int leftEnd = Math.min(range.end, start);
        if (range.start < leftEnd) {
          out.add(new TaintBridge.Range(range.start, leftEnd, range.source));
        }
        int rightStart = Math.max(range.start, end);
        if (rightStart < range.end) {
          addShifted(out, rightStart + delta, range.end + delta, range.source);
        }
      }
    }
  }

  private static void addShifted(
      ArrayList<TaintBridge.Range> out, int start, int end, String source) {
    if (start >= 0 && start < end) {
      out.add(new TaintBridge.Range(start, end, source));
    }
  }

  private static boolean hasSource(ArrayList<TaintBridge.Range> ranges, String source) {
    for (TaintBridge.Range range : ranges) {
      if (source.equals(range.source)) {
        return true;
      }
    }
    return false;
  }

  /**
   * Copy ranges of {@code source} that overlap {@code [from, to)} into {@code out}, shifted so that
   * index {@code from} lands at {@code shift + from}.
   */
  /**
   * Like {@link #copyShifted}, but a zero-length mark on {@code source} (how {@link
   * Taint#tag(Object, String)} stores a non-{@link CharSequence} value) is read as covering the
   * whole requested window instead of being ignored.
   */
  private static void copyLenient(
      ArrayList<TaintBridge.Range> out, Object source, int shift, int from, int to) {
    ArrayList<TaintBridge.Range> ranges = TaintBridge.lookup(source);
    if (ranges == null || from >= to) {
      return;
    }
    int size = ranges.size();
    for (int i = 0; i < size; i++) {
      TaintBridge.Range range = ranges.get(i);
      int start;
      int end;
      if (range.end > range.start) {
        start = Math.max(range.start, from);
        end = Math.min(range.end, to);
      } else {
        start = from;
        end = to;
      }
      if (start < end) {
        out.add(new TaintBridge.Range(start + shift, end + shift, range.source));
      }
    }
  }

  private static void copyShifted(
      ArrayList<TaintBridge.Range> out, Object source, int shift, int from, int to) {
    ArrayList<TaintBridge.Range> ranges = TaintBridge.lookup(source);
    if (ranges == null || from >= to) {
      return;
    }
    // Copy first: source may be the builder whose list addRanges will mutate.
    int size = ranges.size();
    for (int i = 0; i < size; i++) {
      TaintBridge.Range range = ranges.get(i);
      int start = range.start > from ? range.start : from;
      int end = range.end < to ? range.end : to;
      if (start < end) {
        out.add(new TaintBridge.Range(start + shift, end + shift, range.source));
      }
    }
  }
}
