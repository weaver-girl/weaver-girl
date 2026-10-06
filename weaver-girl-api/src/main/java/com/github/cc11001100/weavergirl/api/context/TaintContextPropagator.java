package com.github.cc11001100.weavergirl.api.context;

import com.github.cc11001100.weavergirl.api.taint.TaintBridge;

/**
 * Propagates the current thread's taint scope stack across async boundaries. Cleanup restores the
 * worker thread's previous scopes, including the case where the worker had none.
 */
public final class TaintContextPropagator implements ContextPropagator {

  public static final String NAME = "taint-scope";

  static final class TaintSnapshot implements Snapshot {
    static final TaintSnapshot EMPTY = new TaintSnapshot(TaintBridge.emptySnapshot());

    final Object data;

    TaintSnapshot(Object data) {
      this.data = data;
    }
  }

  @Override
  public String name() {
    return NAME;
  }

  @Override
  public Snapshot capture() {
    return new TaintSnapshot(TaintBridge.capture());
  }

  @Override
  public void restore(Snapshot snapshot) {
    if (snapshot instanceof TaintSnapshot) {
      TaintBridge.restore(((TaintSnapshot) snapshot).data);
    }
  }

  @Override
  public void cleanup(Snapshot previous) {
    restore(previous);
  }

  @Override
  public int priority() {
    return 20;
  }
}
