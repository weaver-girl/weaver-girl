package com.github.cc11001100.weavergirl.api.taint;

/** Half-open character range {@code [start, end)} inside a string, tied to one source label. */
public final class TaintRange {

  private final int start;
  private final int end;
  private final String source;

  public TaintRange(int start, int end, String source) {
    this.start = start;
    this.end = end;
    this.source = source;
  }

  public int getStart() {
    return start;
  }

  public int getEnd() {
    return end;
  }

  public String getSource() {
    return source;
  }
}
