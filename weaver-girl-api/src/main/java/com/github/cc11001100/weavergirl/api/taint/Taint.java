package com.github.cc11001100.weavergirl.api.taint;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.Callable;

/**
 * Request-scoped taint tags. A value tagged in one scope is visible to code that captured that
 * scope, and is not tainted after the scope is closed or inside a newer scope.
 *
 * @since 1.0.0
 */
public final class Taint {

  public static final String SINK_COMMAND = TaintBridge.SINK_COMMAND;
  public static final String SINK_SQL = TaintBridge.SINK_SQL;
  public static final String PARAMETER_PREFIX = TaintBridge.PARAMETER_PREFIX;
  public static final String HEADER_PREFIX = TaintBridge.HEADER_PREFIX;
  public static final String COOKIE_PREFIX = TaintBridge.COOKIE_PREFIX;
  public static final String URI_LABEL = TaintBridge.URI_LABEL;
  public static final String PATH_LABEL = TaintBridge.PATH_LABEL;
  public static final String QUERY_LABEL = TaintBridge.QUERY_LABEL;

  private Taint() {}

  /** Push an empty request scope. Tags written afterwards belong to this scope. */
  public static void openScope() {
    TaintBridge.openScope();
    TaintFindings.install();
  }

  /** Pop the current request scope. Tags that lived only there are no longer visible. */
  public static void closeScope() {
    TaintBridge.closeScope();
  }

  /**
   * Whether the current thread has an open scope. A cheap check before paying for propagation, and
   * the signal a task wrapper uses to decide whether a hand-off needs to carry anything.
   *
   * @return true when at least one scope is open on this thread
   * @since 1.0.0
   */
  public static boolean hasScope() {
    return TaintBridge.hasScope();
  }

  public static void tag(Object value, String sourceLabel) {
    TaintBridge.tag(value, sourceLabel);
  }

  /**
   * Remove every mark from {@code value} in the current thread. Call this from the interceptor of a
   * sanitising function (an encoder, a whitelist check, a cast to a number) after it has proved the
   * value cannot carry a payload. The call walks every open scope, so a mark inherited from an
   * outer request scope is cleared too.
   *
   * <p>Only the current thread is affected: snapshots already handed to other threads keep their
   * copies, which is the correct reading — they were taken before the value was sanitised.
   *
   * @param value the value to clean, usually the sanitised function's return value
   * @return true when at least one mark was removed
   * @since 1.0.0
   */
  public static boolean untaint(Object value) {
    return TaintBridge.clear(value);
  }

  /**
   * Remove only the marks carrying {@code sourceLabel} from {@code value} in the current thread,
   * leaving marks from other sources in place.
   *
   * @param value the value to clean
   * @param sourceLabel the source to forget, for example {@code "http.parameter:q"}
   * @return true when at least one mark was removed
   * @since 1.0.0
   */
  public static boolean untaint(Object value, String sourceLabel) {
    return TaintBridge.clear(value, sourceLabel);
  }

  /**
   * Sanitise several values at once: a validator that checks a whole parameter group (an id list, a
   * pair of path segments) clears every value it accepted.
   *
   * @param values the values to clean
   * @return the number of values that carried at least one mark
   * @since 1.0.0
   */
  public static int untaintAll(Object... values) {
    if (values == null) {
      return 0;
    }
    int cleared = 0;
    for (Object value : values) {
      if (TaintBridge.clear(value)) {
        cleared++;
      }
    }
    return cleared;
  }

  /**
   * Capture this thread's taint scopes as an opaque snapshot. Together with {@link
   * #restoreContext(Object)} this is the manual carry for a boundary the framework does not wrap
   * (a custom thread, a third-party callback).
   *
   * @return an immutable snapshot; never null
   * @since 1.0.0
   */
  public static Object captureContext() {
    return TaintBridge.capture();
  }

  /**
   * Install a snapshot captured by {@link #captureContext()} on this thread, replacing its scopes.
   *
   * @param snapshot a snapshot from {@link #captureContext()}; anything else clears the scopes
   * @since 1.0.0
   */
  public static void restoreContext(Object snapshot) {
    TaintBridge.restore(snapshot);
  }

  /**
   * Wrap a task so this thread's scopes are active while it runs and the executing thread's
   * previous scopes are restored afterwards. Returns the task unchanged when this thread has no
   * scope or the task is already wrapped.
   *
   * @param task the task to wrap
   * @return the context-carrying task
   * @since 1.0.0
   */
  public static Runnable wrap(Runnable task) {
    return TaintBridge.wrap(task);
  }

  /**
   * {@link Callable} variant of {@link #wrap(Runnable)}.
   *
   * @param task the task to wrap
   * @param <V> the result type
   * @return the context-carrying task
   * @since 1.0.0
   */
  public static <V> Callable<V> wrap(Callable<V> task) {
    return TaintBridge.wrap(task);
  }

  /**
   * Generic propagation: copy every source label from {@code source} onto {@code target}. Use this
   * from an interceptor for a method whose data flow the built-in string advice cannot see (a JSON
   * encoder, a mapping helper, a template renderer).
   *
   * @param source the value whose labels are copied
   * @param target the value that becomes tainted
   * @since 1.0.0
   */
  public static void propagate(Object source, Object target) {
    TaintPropagation.propagate(source, target);
  }

  /**
   * Generic sink check: report a finding for every tainted slice of {@code values} and say whether
   * any of them carried taint. Use it for a sink without built-in advice.
   *
   * @param sinkKind the sink label recorded in the finding, for example {@code "template"}
   * @param values the values that reach the sink
   * @return true when at least one value was tainted
   * @since 1.0.0
   */
  public static boolean sink(String sinkKind, Object... values) {
    return TaintPropagation.observeSink(sinkKind, values);
  }

  public static boolean isTainted(Object value) {
    return TaintBridge.isTainted(value);
  }

  public static boolean hasSource(Object value, String sourceLabel) {
    return TaintBridge.hasSource(value, sourceLabel);
  }

  public static List<String> sources(Object value) {
    return TaintBridge.sources(value);
  }

  public static List<TaintRange> ranges(Object value) {
    ArrayList<TaintBridge.Range> raw = TaintBridge.lookup(value);
    if (raw == null || raw.isEmpty()) {
      return Collections.emptyList();
    }
    List<TaintRange> ranges = new ArrayList<TaintRange>(raw.size());
    for (int i = 0; i < raw.size(); i++) {
      TaintBridge.Range range = raw.get(i);
      ranges.add(new TaintRange(range.start, range.end, range.source));
    }
    return Collections.unmodifiableList(ranges);
  }
}
