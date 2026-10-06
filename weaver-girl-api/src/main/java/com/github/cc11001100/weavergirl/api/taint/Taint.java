package com.github.cc11001100.weavergirl.api.taint;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * Request-scoped taint tags. A value tagged in one scope is visible to code that captured that
 * scope, and is not tainted after the scope is closed or inside a newer scope.
 */
public final class Taint {

  public static final String SINK_COMMAND = TaintBridge.SINK_COMMAND;
  public static final String SINK_SQL = TaintBridge.SINK_SQL;
  public static final String PARAMETER_PREFIX = TaintBridge.PARAMETER_PREFIX;
  public static final String HEADER_PREFIX = TaintBridge.HEADER_PREFIX;

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

  public static void tag(Object value, String sourceLabel) {
    TaintBridge.tag(value, sourceLabel);
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
