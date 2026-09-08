package com.github.cc11001100.weavergirl.api.tracing;

import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * Composite SpanCompletionListener that delegates to multiple listeners. Each listener is called in
 * order; exceptions in one listener do not prevent subsequent listeners from being notified.
 *
 * @since 1.2.0
 */
public class CompositeSpanCompletionListener implements SpanCompletionListener {

  private final CopyOnWriteArrayList<SpanCompletionListener> listeners =
      new CopyOnWriteArrayList<>();

  public void addListener(SpanCompletionListener listener) {
    if (listener != null) {
      listeners.add(listener);
    }
  }

  public void removeListener(SpanCompletionListener listener) {
    listeners.remove(listener);
  }

  public int size() {
    return listeners.size();
  }

  public List<SpanCompletionListener> getListeners() {
    return java.util.Collections.unmodifiableList(listeners);
  }

  @Override
  public void onSpanComplete(SpanContext span, long durationMs) {
    for (SpanCompletionListener listener : listeners) {
      try {
        listener.onSpanComplete(span, durationMs);
      } catch (Exception e) {
        // java.util.logging (not System.Logger, which is JDK 9+) keeps the API
        // module dependency-free AND runnable on JDK 8.
        java.util.logging.Logger.getLogger(CompositeSpanCompletionListener.class.getName())
            .warning("SpanCompletionListener threw exception: " + e.getMessage());
      }
    }
  }
}
