package com.github.cc11001100.weavergirl.api.taint;

import java.util.ArrayList;
import java.util.List;

/**
 * Propagation entry used by string and sink advice. Range math lives here so hooks and tests share
 * one implementation: concatenation, substring, and {@code StringBuilder} append / {@code toString}.
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
  public static void propagateAppend(StringBuilder builder, CharSequence appended, int offset) {
    if (builder == null || appended == null || offset < 0) {
      return;
    }
    ArrayList<TaintBridge.Range> out = new ArrayList<TaintBridge.Range>();
    copyShifted(out, appended, offset, 0, appended.length());
    TaintBridge.addRanges(builder, out);
  }

  public static void propagateAppendSlice(
      StringBuilder builder, CharSequence appended, int start, int end, int offset) {
    if (builder == null || appended == null || offset < 0 || start < 0 || end < start) {
      return;
    }
    ArrayList<TaintBridge.Range> out = new ArrayList<TaintBridge.Range>();
    copyShifted(out, appended, offset - start, start, end);
    TaintBridge.addRanges(builder, out);
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

  /**
   * Copy ranges of {@code source} that overlap {@code [from, to)} into {@code out}, shifted so that
   * index {@code from} lands at {@code shift + from}.
   */
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
