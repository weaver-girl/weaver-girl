package com.github.cc11001100.weavergirl.api.tracing;

import static org.junit.jupiter.api.Assertions.*;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class SpanProcessorsTest {

  @BeforeEach
  @AfterEach
  void clearProcessors() {
    SpanProcessors.clear();
    Tracer.setCurrentSpan(null);
  }

  @Test
  void processorsRunInOrderAndChainTheirOutput() {
    SpanProcessors.register(
        new SpanProcessor() {
          @Override
          public String name() {
            return "add-tenant";
          }

          @Override
          public SpanContext process(SpanContext span) {
            return span.toBuilder().attribute("tenant", "acme").build();
          }
        });
    SpanProcessors.register(
        new SpanProcessor() {
          @Override
          public String name() {
            return "derive";
          }

          @Override
          public SpanContext process(SpanContext span) {
            // The second processor must see what the first one added.
            return span.toBuilder()
                .attribute("tenant.tag", span.getAttribute("tenant") + "-prod")
                .build();
          }
        });

    Tracer.startSpan("op");
    SpanContext published = Tracer.endSpan(null, "OK");

    assertEquals("acme", published.getAttribute("tenant"));
    assertEquals("acme-prod", published.getAttribute("tenant.tag"));
    assertEquals("op", published.getOperationName());
  }

  @Test
  void aNullReturnDropsTheSpanAndStopsTheChain() {
    AtomicInteger laterRuns = new AtomicInteger();
    SpanProcessors.register(
        new SpanProcessor() {
          @Override
          public String name() {
            return "drop-health-checks";
          }

          @Override
          public SpanContext process(SpanContext span) {
            return null;
          }
        });
    SpanProcessors.register(
        new SpanProcessor() {
          @Override
          public String name() {
            return "never-runs";
          }

          @Override
          public SpanContext process(SpanContext span) {
            laterRuns.incrementAndGet();
            return span;
          }
        });

    AtomicInteger listenerCalls = new AtomicInteger();
    SpanCompletionListener listener = (span, durationMs) -> listenerCalls.incrementAndGet();
    Tracer.addCompletionListener(listener);
    try {
      Tracer.startSpan("GET /health");
      SpanContext published = Tracer.endSpan(null, "OK");

      assertNull(published, "a dropped span should not be returned as recorded");
      assertEquals(0, laterRuns.get(), "the chain should stop at the drop");
      assertEquals(0, listenerCalls.get(), "listeners must not see a dropped span");
      assertNull(Tracer.getCurrentSpan(), "the thread must be left without a current span");
    } finally {
      Tracer.removeCompletionListener(listener);
    }
  }

  @Test
  void aThrowingProcessorPublishesTheSpanUnchanged() {
    SpanProcessors.register(
        new SpanProcessor() {
          @Override
          public String name() {
            return "broken";
          }

          @Override
          public SpanContext process(SpanContext span) {
            throw new IllegalStateException("bad rule");
          }
        });
    SpanProcessors.register(
        new SpanProcessor() {
          @Override
          public String name() {
            return "after-broken";
          }

          @Override
          public SpanContext process(SpanContext span) {
            return span.toBuilder().attribute("after", "yes").build();
          }
        });

    List<SpanContext> received = new ArrayList<>();
    SpanCompletionListener listener = (span, durationMs) -> received.add(span);
    Tracer.addCompletionListener(listener);
    try {
      Tracer.startSpan("op");
      SpanContext published = Tracer.endSpan(null, "OK");

      assertNotNull(published);
      assertEquals("yes", published.getAttribute("after"));
      assertEquals(1, received.size());
      assertDoesNotThrow(() -> Tracer.endSpan(null, "OK"));
    } finally {
      Tracer.removeCompletionListener(listener);
    }
  }

  @Test
  void statsCountKeptDroppedAndFailedSpansByProcessorName() {
    SpanProcessors.register(processor("keep", span -> span));
    SpanProcessors.register(
        processor(
            "broken",
            span -> {
              throw new IllegalStateException("bad rule");
            }));
    SpanProcessors.register(processor("drop", span -> null));

    Tracer.startSpan("one");
    assertNull(Tracer.endSpan(null, "OK"));

    Map<String, SpanProcessorStats> stats = SpanProcessors.getStats();
    assertEquals(1, stats.get("keep").getProcessed());
    assertEquals(0, stats.get("keep").getFailures());
    assertEquals(1, stats.get("broken").getFailures());
    assertEquals(0, stats.get("broken").getDropped(), "a failure keeps the span, it does not drop it");
    assertEquals(1, stats.get("drop").getDropped());

    SpanProcessors.unregister(SpanProcessors.getProcessors().get(2));
    Tracer.startSpan("two");
    assertNotNull(Tracer.endSpan(null, "OK"));

    stats = SpanProcessors.getStats();
    assertFalse(stats.containsKey("drop"), "unregistered processors lose their stats");
    assertEquals(2, stats.get("keep").getProcessed());
    assertEquals(2, stats.get("broken").getFailures());
  }

  @Test
  void registeringTheSameProcessorTwiceRunsItOnce() {
    AtomicInteger runs = new AtomicInteger();
    SpanProcessor processor =
        processor(
            "count",
            span -> {
              runs.incrementAndGet();
              return span;
            });

    SpanProcessors.register(processor);
    SpanProcessors.register(processor);

    Tracer.startSpan("op");
    Tracer.endSpan(null, "OK");

    assertEquals(1, runs.get());
    assertEquals(1, SpanProcessors.getProcessors().size());
  }

  @Test
  void toBuilderRoundTripsAndCanRemoveAttributes() {
    Tracer.startSpan("GET /orders");
    Tracer.setAttribute("http.method", "GET");
    Tracer.setAttribute("http.header.authorization", "Bearer secret");
    // Setting baggage after attributes must not drop the attributes.
    Tracer.setBaggage("tenant", "acme");
    SpanContext original = Tracer.endSpan(null, "OK");

    SpanContext redacted =
        original.toBuilder().removeAttribute("http.header.authorization").build();

    assertEquals("GET", redacted.getAttribute("http.method"));
    assertNull(redacted.getAttribute("http.header.authorization"), "the secret must be gone");
    assertEquals("acme", redacted.getBaggageItem("tenant"));
    assertEquals(original.getTraceId(), redacted.getTraceId());
    assertEquals(original.getSpanId(), redacted.getSpanId());
    assertEquals(original.getStartTimeMs(), redacted.getStartTimeMs());
    assertEquals(original.getErrorStatus(), redacted.getErrorStatus());
    assertEquals(original.getOperationName(), redacted.getOperationName());
  }

  private static SpanProcessor processor(String name, ProcessorBody body) {
    return new SpanProcessor() {
      @Override
      public String name() {
        return name;
      }

      @Override
      public SpanContext process(SpanContext span) {
        return body.process(span);
      }
    };
  }

  private interface ProcessorBody {
    SpanContext process(SpanContext span);
  }
}
