package com.github.cc11001100.weavergirl.api.taint;

import java.util.ArrayList;
import java.util.List;

/**
 * Runtime half of the {@code invokedynamic} concatenation hook. Java 9+ compiles {@code a + b} to
 * {@code StringConcatFactory.makeConcatWithConstants} instead of a {@code StringBuilder}, so the
 * string advice never sees it. The agent rewrites each such call site to pass the runtime values and
 * a template to this class after the concat has produced its result.
 *
 * <p>The template is the concat recipe with {@code \u0002} constants resolved at transform time and
 * one {@code \u0001} placeholder for every dynamic argument, so argument positions in the result can
 * be reconstructed without touching the call site again.
 *
 * <p>Bootstrap-safe: only JDK types and {@link TaintBridge}, so the same class can be appended to
 * the bootstrap loader and resolved from every application class.
 *
 * @since 1.5.0
 */
public final class TaintConcat {

  private TaintConcat() {}

  /**
   * Copies taint from the concat arguments onto the result. Returns the same result so the rewritten
   * call site stays a drop-in replacement.
   *
   * @param result the string the concat produced
   * @param template recipe text with {@code \u0001} placeholders, constants already inlined
   * @param args the dynamic arguments in descriptor order
   * @return {@code result}, unchanged
   */
  public static String propagate(String result, String template, Object[] args) {
    if (result == null || template == null || args == null || !TaintBridge.hasScope()) {
      return result;
    }
    ArrayList<TaintBridge.Range> out = new ArrayList<TaintBridge.Range>();
    int position = 0;
    int argIndex = 0;
    boolean aligned = true;
    for (int i = 0; i < template.length(); i++) {
      char current = template.charAt(i);
      if (current != '\u0001') {
        position++;
        continue;
      }
      if (argIndex >= args.length) {
        aligned = false;
        break;
      }
      Object value = args[argIndex++];
      String text = String.valueOf(value);
      copyValue(out, value, text, position);
      position += text.length();
    }
    if (!aligned) {
      // A malformed recipe must not silently drop the flow. Fall back to a whole-result mark.
      ArrayList<TaintBridge.Range> fallback = new ArrayList<TaintBridge.Range>();
      wholeResult(fallback, args, result.length());
      out = fallback;
    }
    if (!out.isEmpty()) {
      TaintBridge.setRanges(result, out);
    }
    return result;
  }

  private static void copyValue(
      ArrayList<TaintBridge.Range> out, Object value, String text, int offset) {
    List<TaintBridge.Range> ranges = TaintBridge.lookup(value);
    if (ranges == null) {
      return;
    }
    for (TaintBridge.Range range : ranges) {
      int start = range.start;
      int end = range.end;
      if (start >= end) {
        // A non-CharSequence was tagged as a whole object: its textual form is the whole slice.
        start = 0;
        end = text.length();
      }
      start = Math.max(0, Math.min(start, text.length()));
      end = Math.max(0, Math.min(end, text.length()));
      if (start < end) {
        out.add(new TaintBridge.Range(offset + start, offset + end, range.source));
      }
    }
  }

  private static void wholeResult(
      ArrayList<TaintBridge.Range> out, Object[] args, int resultLength) {
    if (resultLength <= 0) {
      return;
    }
    for (Object value : args) {
      List<TaintBridge.Range> ranges = TaintBridge.lookup(value);
      if (ranges == null) {
        continue;
      }
      for (TaintBridge.Range range : ranges) {
        String source = range.source;
        if (source == null || containsSource(out, source)) {
          continue;
        }
        out.add(new TaintBridge.Range(0, resultLength, source));
      }
    }
  }

  private static boolean containsSource(List<TaintBridge.Range> ranges, String source) {
    for (TaintBridge.Range range : ranges) {
      if (source.equals(range.source)) {
        return true;
      }
    }
    return false;
  }
}
