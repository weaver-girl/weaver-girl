package com.github.cc11001100.weavergirl.api.taint;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.IdentityHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Callable;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * Bootstrap-safe taint store. No dependencies outside the JDK, so the same class can be appended to
 * the bootstrap class loader and shared by {@code java.lang.String} advice and application hooks.
 *
 * <p>Tags live on a per-thread stack of request scopes. Capture copies that stack; restore installs
 * the copy; a later restore of the worker's previous snapshot puts the worker's own scopes back.
 *
 * @since 1.0.0
 */
public final class TaintBridge {

  public static final String SINK_COMMAND = "command-execution";
  public static final String SINK_SQL = "sql";
  public static final String PARAMETER_PREFIX = "http.parameter:";
  public static final String HEADER_PREFIX = "http.header:";
  public static final String COOKIE_PREFIX = "http.cookie:";
  public static final String URI_LABEL = "http.uri";
  public static final String PATH_LABEL = "http.path";
  public static final String QUERY_LABEL = "http.query";

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

  /**
   * One tainted slice. Public because the bridge is appended to the bootstrap loader while {@link
   * Taint} stays with the application: a package-private type would make {@link
   * Taint#ranges(Object)} fail with {@code IllegalAccessError} across those two loaders.
   */
  public static final class Range {
    public final int start;
    public final int end;
    public final String source;

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
   * Releases the guard only when this advice frame acquired it. Used on the exception path, where
   * {@code @Advice.Enter} still says whether {@link #tryEnter()} succeeded but nothing else runs.
   *
   * @param entered the value {@link #tryEnter()} returned for this frame
   */
  public static void release(boolean entered) {
    if (entered) {
      leave();
    }
  }

  /**
   * Reference-identity comparison for advice code.
   *
   * <p>Lives here so the inlined advice never compares a {@code String} parameter with {@code ==}
   * directly (a static analyser cannot tell that {@code String.valueOf(Object)} returning the very
   * same instance is exactly what is being tested). {@code Object} parameters keep the identity
   * semantics explicit.
   *
   * @param a first value
   * @param b second value
   * @return true when both refer to the same instance
   */
  public static boolean isSameInstance(Object a, Object b) {
    return a == b;
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

  /** Whether this thread currently has a request scope. A cheap gate before doing any work. */
  public static boolean hasScope() {
    ArrayDeque<Scope> stack = STACK.get();
    return stack != null && !stack.isEmpty();
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

  /**
   * Drop every mark for {@code value} on this thread. This is what a sanitiser hook calls after it
   * has proved the value can no longer carry an injection payload.
   *
   * @return true when at least one mark was removed
   */
  public static boolean clear(Object value) {
    if (value == null) {
      return false;
    }
    ArrayDeque<Scope> stack = STACK.get();
    if (stack == null || stack.isEmpty()) {
      return false;
    }
    boolean removed = false;
    for (Scope scope : stack) {
      if (scope.marks.remove(value) != null) {
        removed = true;
      }
    }
    return removed;
  }

  /**
   * Drop the marks for {@code value} that carry {@code source}, leaving other sources in place.
   *
   * @return true when at least one mark was removed
   */
  public static boolean clear(Object value, String source) {
    if (value == null || source == null) {
      return false;
    }
    ArrayDeque<Scope> stack = STACK.get();
    if (stack == null || stack.isEmpty()) {
      return false;
    }
    boolean removed = false;
    for (Scope scope : stack) {
      ArrayList<Range> ranges = scope.marks.get(value);
      if (ranges == null) {
        continue;
      }
      for (int i = ranges.size() - 1; i >= 0; i--) {
        if (source.equals(ranges.get(i).source)) {
          ranges.remove(i);
          removed = true;
        }
      }
      if (ranges.isEmpty()) {
        scope.marks.remove(value);
      }
    }
    return removed;
  }

  /**
   * Merged ranges of {@code value} across this thread's scopes. Public for the same reason {@link
   * Range} is: {@link Taint} may live in a different loader from this bootstrap copy.
   */
  public static ArrayList<Range> lookup(Object value) {
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

  /**
   * Replace the ranges of a mutable value (a {@code StringBuilder} after {@code delete},
   * {@code insert}, {@code replace}, {@code setLength} or {@code reverse}) with a recomputed set.
   * The old entry is dropped from every scope first, so an inherited range cannot survive in an
   * outer scope and reappear with stale offsets.
   */
  static void rewriteRanges(Object value, ArrayList<Range> ranges) {
    ArrayDeque<Scope> stack = STACK.get();
    if (stack == null || stack.isEmpty() || value == null) {
      return;
    }
    for (Scope scope : stack) {
      scope.marks.remove(value);
    }
    if (ranges != null && !ranges.isEmpty()) {
      stack.peekFirst().marks.put(value, ranges);
    }
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
    emitValue(sinkKind, argument);
  }

  /**
   * Sink entry for any value, not just strings. Non-{@link CharSequence} values are tagged as a
   * whole object at capture time ({@code [0,0)}), so their rendered text is reported as the slice.
   */
  public static void emitValue(String sinkKind, Object value) {
    if (sinkKind == null || value == null) {
      return;
    }
    ArrayList<Range> ranges = lookup(value);
    if (ranges == null || ranges.isEmpty() || OBSERVERS.isEmpty()) {
      return;
    }
    String argument = String.valueOf(value);
    int length = argument.length();
    boolean textual = value instanceof CharSequence;
    ArrayList<Range> slices = new ArrayList<Range>();
    for (int i = 0; i < ranges.size(); i++) {
      Range range = ranges.get(i);
      int start = textual ? range.start : 0;
      int end = textual ? range.end : length;
      start = Math.max(0, Math.min(start, length));
      end = Math.max(0, Math.min(end, length));
      if (range.source == null || start >= end) {
        continue;
      }
      Range slice = new Range(start, end, range.source);
      if (!containsSlice(slices, slice)) {
        slices.add(slice);
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

  /**
   * Wrap a task so the scopes captured on the submitting thread are active while it runs and the
   * worker thread's previous scopes are put back afterwards. Returns {@code task} unchanged when
   * the submitting thread has no scope or the task is already wrapped, so a clean submission pays
   * nothing.
   *
   * <p>This is the API entry for manual carries (a custom thread, a reactor callback). JDK
   * executors are wrapped automatically when the agent's taint instrumentation is installed.
   *
   * @param task the task to wrap
   * @return the wrapping task, or the original when no wrap is needed
   * @since 1.6.0
   */
  public static Runnable wrap(Runnable task) {
    if (task == null || !hasScope() || task instanceof WrappedRunnable) {
      return task;
    }
    return new WrappedRunnable(task, capture());
  }

  /**
   * {@link Callable} variant of {@link #wrap(Runnable)}.
   *
   * <p>The returned value is computed with the submitting thread's scopes active. Marks written to
   * that value live in the worker's temporary scopes and are dropped when the task finishes, so a
   * caller that needs the result tainted must transfer it explicitly (for example via {@link
   * #capture()} inside the task).
   *
   * @param task the task to wrap
   * @param <V> the result type
   * @return the wrapping task, or the original when no wrap is needed
   * @since 1.6.0
   */
  public static <V> Callable<V> wrap(Callable<V> task) {
    if (task == null || !hasScope() || task instanceof WrappedCallable) {
      return task;
    }
    return new WrappedCallable<V>(task, capture());
  }

  /** Runnable wrapper that activates a captured taint snapshot around {@link Runnable#run()}. */
  public static final class WrappedRunnable implements Runnable {
    private final Runnable delegate;
    private final Object snapshot;

    WrappedRunnable(Runnable delegate, Object snapshot) {
      this.delegate = delegate;
      this.snapshot = snapshot;
    }

    @Override
    public void run() {
      Object previous = capture();
      restore(snapshot);
      try {
        delegate.run();
      } finally {
        restore(previous);
      }
    }

    @Override
    public String toString() {
      return "TaintWrapped[" + delegate + "]";
    }
  }

  /** Callable wrapper that activates a captured taint snapshot around {@link Callable#call()}. */
  public static final class WrappedCallable<V> implements Callable<V> {
    private final Callable<V> delegate;
    private final Object snapshot;

    WrappedCallable(Callable<V> delegate, Object snapshot) {
      this.delegate = delegate;
      this.snapshot = snapshot;
    }

    @Override
    public V call() throws Exception {
      Object previous = capture();
      restore(snapshot);
      try {
        return delegate.call();
      } finally {
        restore(previous);
      }
    }

    @Override
    public String toString() {
      return "TaintWrapped[" + delegate + "]";
    }
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
