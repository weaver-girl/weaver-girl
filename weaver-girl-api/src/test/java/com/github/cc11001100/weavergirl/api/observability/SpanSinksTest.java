package com.github.cc11001100.weavergirl.api.observability;

import static org.junit.jupiter.api.Assertions.*;

import com.github.cc11001100.weavergirl.api.tracing.Tracer;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class SpanSinksTest {

  @BeforeEach
  @AfterEach
  void clearSinks() {
    SpanSinks.clear();
    Tracer.setCurrentSpan(null);
  }

  @Test
  void aCompletedSpanReachesTheSinkAsARecord() {
    AtomicReference<SpanRecord> received = new AtomicReference<>();
    SpanSinks.register(
        new SpanSink() {
          @Override
          public String name() {
            return "capture";
          }

          @Override
          public void onSpanEnd(SpanRecord span) {
            received.set(span);
          }
        });

    Tracer.startSpan("GET /orders");
    Tracer.setAttribute("http.method", "GET");
    Tracer.setAttribute(Tracer.SPAN_KIND_ATTRIBUTE, "server");
    Tracer.setAttribute("error", "true");
    Tracer.endSpan(null, null);

    SpanRecord record = received.get();
    assertNotNull(record, "the sink was not called");
    assertEquals("GET /orders", record.getOperationName());
    assertEquals("server", record.getKind());
    assertEquals("GET", record.getAttribute("http.method"));
    assertNull(record.getAttribute(Tracer.SPAN_KIND_ATTRIBUTE), "kind should not be repeated");
    assertNull(record.getParentSpanId());
    assertTrue(record.isError(), "error=true should mark the record as failed");
    assertTrue(record.getDurationMs() >= 0);
    assertTrue(record.getEndTimeMs() >= record.getStartTimeMs());
  }

  @Test
  void aChildSpanCarriesItsParentLink() {
    List<SpanRecord> received = new ArrayList<>();
    SpanSinks.register(recordSink("capture", received));

    com.github.cc11001100.weavergirl.api.tracing.SpanContext parent = Tracer.startSpan("parent");
    Tracer.endSpan(null, "OK");

    // endSpan clears the thread's span rather than restoring a stack, so set the parent back
    // before creating the child under it.
    Tracer.setCurrentSpan(parent);
    Tracer.startChildSpan("child");
    Tracer.endSpan(null, "OK");

    assertEquals(2, received.size());
    assertNull(received.get(0).getParentSpanId());
    assertEquals("parent", received.get(0).getOperationName());
    assertEquals(parent.getSpanId(), received.get(1).getParentSpanId());
    assertEquals("child", received.get(1).getOperationName());
  }

  @Test
  void unregisterStopsDelivery() {
    AtomicInteger calls = new AtomicInteger();
    SpanSink sink =
        new SpanSink() {
          @Override
          public String name() {
            return "counter";
          }

          @Override
          public void onSpanEnd(SpanRecord span) {
            calls.incrementAndGet();
          }
        };
    SpanSinks.register(sink);
    Tracer.startSpan("one");
    Tracer.endSpan(null, null);

    SpanSinks.unregister(sink);
    Tracer.startSpan("two");
    Tracer.endSpan(null, null);

    assertEquals(1, calls.get());
    assertTrue(SpanSinks.getSinks().isEmpty());
  }

  @Test
  void registeringTheSameSinkTwiceDeliversOnce() {
    AtomicInteger calls = new AtomicInteger();
    SpanSink sink = recordSinkCounting(calls);

    SpanSinks.register(sink);
    SpanSinks.register(sink);

    Tracer.startSpan("op");
    Tracer.endSpan(null, null);

    assertEquals(1, calls.get());
    assertEquals(1, SpanSinks.getSinks().size());
  }

  @Test
  void aThrowingSinkDoesNotStopTheNextOne() {
    SpanSinks.register(
        new SpanSink() {
          @Override
          public String name() {
            return "broken";
          }

          @Override
          public void onSpanEnd(SpanRecord span) {
            throw new IllegalStateException("backend down");
          }
        });
    AtomicInteger calls = new AtomicInteger();
    SpanSinks.register(recordSinkCounting(calls));

    Tracer.startSpan("op");
    assertDoesNotThrow(() -> Tracer.endSpan(null, null));
    assertEquals(1, calls.get(), "the sink after the failing one was skipped");
  }

  @Test
  void theTracerListenerIsDetachedWhenTheLastSinkGoesAway() {
    AtomicInteger otherListenerCalls = new AtomicInteger();
    com.github.cc11001100.weavergirl.api.tracing.SpanCompletionListener other =
        (span, durationMs) -> otherListenerCalls.incrementAndGet();
    Tracer.addCompletionListener(other);
    try {
      SpanSink sink = recordSinkCounting(new AtomicInteger());
      SpanSinks.register(sink);
      SpanSinks.unregister(sink);

      Tracer.startSpan("op");
      Tracer.endSpan(null, null);

      assertEquals(1, otherListenerCalls.get(), "detaching sinks removed an unrelated listener");
    } finally {
      Tracer.removeCompletionListener(other);
    }
  }

  @Test
  void statsCountDeliveriesAndFailuresPerSinkName() {
    SpanSinks.register(recordSinkCounting(new AtomicInteger()));
    SpanSinks.register(
        new SpanSink() {
          @Override
          public String name() {
            return "broken";
          }

          @Override
          public void onSpanEnd(SpanRecord span) {
            throw new IllegalStateException("backend down");
          }
        });

    Tracer.startSpan("op");
    Tracer.endSpan(null, null);

    Map<String, SpanSinkStats> stats = SpanSinks.getSinkStats();
    assertEquals(2, stats.size());
    assertEquals(1, stats.get("counting").getDeliveries());
    assertEquals(0, stats.get("counting").getFailures());
    assertEquals(1, stats.get("broken").getDeliveries());
    assertEquals(1, stats.get("broken").getFailures());
  }

  @Test
  void statsDisappearWhenTheSinkIsUnregistered() {
    SpanSink sink = recordSinkCounting(new AtomicInteger());
    SpanSinks.register(sink);
    Tracer.startSpan("op");
    Tracer.endSpan(null, null);

    SpanSinks.unregister(sink);

    assertTrue(SpanSinks.getSinkStats().isEmpty());
  }

  @Test
  void clearRemovesEverySink() {
    SpanSinks.register(recordSinkCounting(new AtomicInteger()));
    SpanSinks.register(recordSinkCounting(new AtomicInteger()));

    SpanSinks.clear();

    assertTrue(SpanSinks.getSinks().isEmpty());
  }

  private static SpanSink recordSink(String name, List<SpanRecord> received) {
    return new SpanSink() {
      @Override
      public String name() {
        return name;
      }

      @Override
      public void onSpanEnd(SpanRecord span) {
        received.add(span);
      }
    };
  }

  private static SpanSink recordSinkCounting(AtomicInteger calls) {
    return new SpanSink() {
      @Override
      public String name() {
        return "counting";
      }

      @Override
      public void onSpanEnd(SpanRecord span) {
        calls.incrementAndGet();
      }
    };
  }
}
