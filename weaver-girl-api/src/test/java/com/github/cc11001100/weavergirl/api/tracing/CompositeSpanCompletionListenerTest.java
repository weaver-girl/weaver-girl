package com.github.cc11001100.weavergirl.api.tracing;

import static org.junit.jupiter.api.Assertions.*;

import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;

class CompositeSpanCompletionListenerTest {

  @Test
  void notifiesListenersInOrderAndContinuesAfterFailure() {
    CompositeSpanCompletionListener composite = new CompositeSpanCompletionListener();
    AtomicInteger calls = new AtomicInteger();
    SpanCompletionListener first = (span, duration) -> calls.incrementAndGet();
    SpanCompletionListener failing = (span, duration) -> { throw new IllegalStateException("boom"); };
    SpanCompletionListener last = (span, duration) -> calls.addAndGet(10);
    composite.addListener(null);
    composite.addListener(first);
    composite.addListener(failing);
    composite.addListener(last);
    assertEquals(3, composite.size());
    composite.onSpanComplete(null, 12);
    assertEquals(11, calls.get());
    assertEquals(3, composite.getListeners().size());
    assertThrows(UnsupportedOperationException.class, () -> composite.getListeners().clear());
    composite.removeListener(first);
    assertEquals(2, composite.size());
    composite.removeListener(null);
    assertEquals(2, composite.size());
  }
}
