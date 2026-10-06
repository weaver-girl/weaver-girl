package com.github.cc11001100.weavergirl.api.taint;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.IdentityHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * Bootstrap-safe taint store. No dependencies outside the JDK, so the same class can be appended to
 * the bootstrap class loader and shared by {@code java.lang.String} advice and application hooks.
 *
 * <p>Tags live on a per-thread stack of request scopes. Capture copies that stack; restore installs
 * the copy; a later restore of the worker's previous snapshot puts the worker's own scopes back.
 */
public final class TaintBridge {

  public static final String SINK_COMMAND = "command-execution";
  public static final String SINK_SQL = "sql";
  public static final String PARAMETER_PREFIX = "http.parameter:";
  public static final String HEADER_PREFIX = "http.header:";

  /** Set by tests so {@code ProcessBuilder.start} / {@code Runtime.exec} advice does not spawn. */
  public static volatile boolean suppressProcessStart = false;

  static volatile boolean ready = false;

  private static final SnapshotData EMPTY_SNAPSHOT =
      new SnapshotData(new ArrayList<IdentityHashMap<Object, ArrayList<Range>>>());

  private static final ThreadLocal<ArrayDeque<Scope>> STACK = new ThreadLocal<ArrayDeque<Scope>>();
  private static final ThreadLocal<int[]> DEPTH = new ThreadLocal<int[]>();
  /** Frames for command-sink advice. Nested exec/start stay inside the outer frame until exit. */
  private static final ThreadLocal<ArrayDeque<Boolean>> EXEC_FRAMES =
      new ThreadLocal<ArrayDeque<Boolean>>();
  private static final CopyOnWriteArrayList<SinkObserver> OBSERVERS =
      new CopyOnWriteArrayList<SinkObserver>();

  static {
    ready = true;
  }

  private TaintBridge() {}

  /** Receives one detail per tainted slice of a sink argument. */
  public interface SinkObserver {
    void onFinding(String sourceLabel, String sinkKind, String argument, int start, int end);
  }

  static final class Range {
    final int start;
    final int end;
    final String source;

    Range(int start, int end, String source) {
      this.start = start;
      this.end = end;
      this.source = source;
    }
  }

  static final class Scope {
    final IdentityHashMap<Object, ArrayList<Range>> marks =
        new IdentityHashMap<Object, ArrayList<Range>>();
  }

  /** Deep copy of the scope stack, top scope first. Object keys keep identity. */
  static final class SnapshotData {
    final ArrayList<IdentityHashMap<Object, ArrayList<Range>>> scopes;

    SnapshotData(ArrayList<IdentityHashMap<Object, ArrayList<Range>>> scopes) {
      this.scopes = scopes;
    }
  }

  public static void addObserver(SinkObserver observer) {
    if (observer != null) {
      OBSERVERS.add(observer);
    }
  }

  public static void removeObserver(SinkObserver observer) {
    OBSERVERS.remove(observer);
  }

  /** Re-entrancy guard for advice that runs inside {@code String} / {@code StringBuilder}. */
  public static boolean tryEnter() {
    if (!ready) {
      return false;
    }
    int[] depth = DEPTH.get();
    if (depth == null) {
      depth = new int[] {0};
      DEPTH.set(depth);
    }
    if (depth[0] > 0) {
      return false;
    }
    depth[0] = 1;
    return true;
  }

  public static void leave() {
    int[] depth = DEPTH.get();
    if (depth != null) {
      depth[0] = 0;
    }
  }

  /**
   * Opens a command-sink frame and returns whether this frame owns the re-entrancy guard. Nested
   * {@code Runtime.exec} / {@code ProcessBuilder.start} calls see the guard until {@link
   * #endExecObservation()} runs on the way out.
   */
  public static boolean beginExecObservation() {
    boolean entered = tryEnter();
    ArrayDeque<Boolean> frames = EXEC_FRAMES.get();
    if (frames == null) {
      frames = new ArrayDeque<Boolean>();
      EXEC_FRAMES.set(frames);
    }
    frames.addFirst(Boolean.valueOf(entered));
    return entered;
  }

  /** Pops the command-sink frame and releases the guard only if this frame acquired it. */
  public static void endExecObservation() {
    ArrayDeque<Boolean> frames = EXEC_FRAMES.get();
    if (frames == null || frames.isEmpty()) {
      return;
    }
    boolean entered = frames.removeFirst().booleanValue();
    if (frames.isEmpty()) {
      EXEC_FRAMES.remove();
    }
    if (entered) {
      leave();
    }
  }

  public static void openScope() {
    ArrayDeque<Scope> stack = STACK.get();
    if (stack == null) {
      stack = new ArrayDeque<Scope>();
      STACK.set(stack);
    }
    stack.addFirst(new Scope());
  }

  public static void closeScope() {
    ArrayDeque<Scope> stack = STACK.get();
    if (stack == null || stack.isEmpty()) {
      return;
    }
    stack.removeFirst();
    if (stack.isEmpty()) {
      STACK.remove();
    }
  }

  public static void tag(Object value, String source) {
    if (value == null || source == null) {
      return;
    }
    Scope top = top();
    if (top == null) {
      return;
    }
    int len = value instanceof CharSequence ? ((CharSequence) value).length() : 0;
    ArrayList<Range> ranges = top.marks.get(value);
    if (ranges == null) {
      ranges = new ArrayList<Range>();
      top.marks.put(value, ranges);
    }
    ranges.add(new Range(0, len, source));
  }

  public static boolean isTainted(Object value) {
    ArrayList<Range> ranges = lookup(value);
    return ranges != null && !ranges.isEmpty();
  }

  public static boolean hasSource(Object value, String source) {
    if (source == null) {
      return false;
    }
    ArrayList<Range> ranges = lookup(value);
    if (ranges == null) {
      return false;
    }
    for (int i = 0; i < ranges.size(); i++) {
      if (source.equals(ranges.get(i).source)) {
        return true;
      }
    }
    return false;
  }

  public static List<String> sources(Object value) {
    LinkedHashSet<String> labels = new LinkedHashSet<String>();
    ArrayList<Range> ranges = lookup(value);
    if (ranges != null) {
      for (int i = 0; i < ranges.size(); i++) {
        labels.add(ranges.get(i).source);
      }
    }
    return new ArrayList<String>(labels);
  }

  static ArrayList<Range> lookup(Object value) {
    if (value == null) {
      return null;
    }
    ArrayDeque<Scope> stack = STACK.get();
    if (stack == null || stack.isEmpty()) {
      return null;
    }
    ArrayList<Range> merged = null;
    for (Scope scope : stack) {
      ArrayList<Range> found = scope.marks.get(value);
      if (found != null && !found.isEmpty()) {
        if (merged == null) {
          merged = new ArrayList<Range>();
        }
        merged.addAll(found);
      }
    }
    return merged;
  }

  static void setRanges(Object value, ArrayList<Range> ranges) {
    Scope top = top();
    if (top == null || value == null) {
      return;
    }
    if (ranges == null || ranges.isEmpty()) {
      top.marks.remove(value);
      return;
    }
    top.marks.put(value, ranges);
  }

  static void addRanges(Object value, ArrayList<Range> ranges) {
    Scope top = top();
    if (top == null || value == null || ranges == null || ranges.isEmpty()) {
      return;
    }
    ArrayList<Range> existing = top.marks.get(value);
    if (existing == null) {
      top.marks.put(value, ranges);
    } else {
      existing.addAll(ranges);
    }
  }

  public static void emit(String sinkKind, String argument) {
    if (sinkKind == null || argument == null) {
      return;
    }
    ArrayList<Range> ranges = lookup(argument);
    if (ranges == null || ranges.isEmpty() || OBSERVERS.isEmpty()) {
      return;
    }
    ArrayList<Range> slices = new ArrayList<Range>();
    for (int i = 0; i < ranges.size(); i++) {
      Range range = ranges.get(i);
      if (range.source == null || range.start >= range.end) {
        continue;
      }
      if (!containsSlice(slices, range)) {
        slices.add(range);
      }
    }
    for (int s = 0; s < slices.size(); s++) {
      Range slice = slices.get(s);
      for (int i = 0; i < OBSERVERS.size(); i++) {
        try {
          OBSERVERS.get(i)
              .onFinding(slice.source, sinkKind, argument, slice.start, slice.end);
        } catch (Throwable ignored) {
          // A subscriber must not break the sink hook.
        }
      }
    }
  }

  private static boolean containsSlice(ArrayList<Range> slices, Range range) {
    for (int i = 0; i < slices.size(); i++) {
      Range kept = slices.get(i);
      if (kept.start == range.start
          && kept.end == range.end
          && range.source.equals(kept.source)) {
        return true;
      }
    }
    return false;
  }

  public static Object emptySnapshot() {
    return EMPTY_SNAPSHOT;
  }

  public static Object capture() {
    ArrayDeque<Scope> stack = STACK.get();
    ArrayList<IdentityHashMap<Object, ArrayList<Range>>> scopes =
        new ArrayList<IdentityHashMap<Object, ArrayList<Range>>>();
    if (stack != null) {
      for (Scope scope : stack) {
        IdentityHashMap<Object, ArrayList<Range>> copy =
            new IdentityHashMap<Object, ArrayList<Range>>();
        for (Map.Entry<Object, ArrayList<Range>> entry : scope.marks.entrySet()) {
          copy.put(entry.getKey(), cloneRanges(entry.getValue()));
        }
        scopes.add(copy);
      }
    }
    return new SnapshotData(scopes);
  }

  public static void restore(Object snapshot) {
    if (!(snapshot instanceof SnapshotData)) {
      STACK.remove();
      return;
    }
    SnapshotData data = (SnapshotData) snapshot;
    if (data.scopes.isEmpty()) {
      STACK.remove();
      return;
    }
    ArrayDeque<Scope> stack = new ArrayDeque<Scope>();
    for (int i = 0; i < data.scopes.size(); i++) {
      Scope scope = new Scope();
      IdentityHashMap<Object, ArrayList<Range>> saved = data.scopes.get(i);
      for (Map.Entry<Object, ArrayList<Range>> entry : saved.entrySet()) {
        scope.marks.put(entry.getKey(), cloneRanges(entry.getValue()));
      }
      stack.addLast(scope);
    }
    STACK.set(stack);
  }

  private static Scope top() {
    ArrayDeque<Scope> stack = STACK.get();
    if (stack == null || stack.isEmpty()) {
      return null;
    }
    return stack.peekFirst();
  }

  private static ArrayList<Range> cloneRanges(ArrayList<Range> ranges) {
    ArrayList<Range> copy = new ArrayList<Range>(ranges.size());
    for (int i = 0; i < ranges.size(); i++) {
      Range range = ranges.get(i);
      copy.add(new Range(range.start, range.end, range.source));
    }
    return copy;
  }
}
