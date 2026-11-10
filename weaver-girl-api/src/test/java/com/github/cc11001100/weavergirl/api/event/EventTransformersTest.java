package com.github.cc11001100.weavergirl.api.event;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.github.cc11001100.weavergirl.api.observability.ExtensionPerformanceTracker;
import com.github.cc11001100.weavergirl.api.observability.ExtensionStats;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/** Event transform runs before listeners, exporters and the history, and isolates failures. */
class EventTransformersTest {

  @BeforeEach
  @AfterEach
  void reset() {
    EventTransformers.clear();
    ExtensionPerformanceTracker.clear();
    InterceptorEventPublisher.getInstance().clearHistory();
    for (InterceptorEventListener listener :
        new ArrayList<InterceptorEventListener>(
            InterceptorEventPublisher.getInstance().getListeners())) {
      InterceptorEventPublisher.getInstance().removeListener(listener);
    }
  }

  @Test
  void aTransformerRedactsAnAttributeAndTheConsumerSeesTheRedactedCopy() {
    EventTransformers.register(
        transformer(
            "redactor",
            0,
            event -> event.toBuilder().attribute("sql", "***").build()));
    List<InterceptorEvent> seen = new ArrayList<>();
    InterceptorEventPublisher.getInstance().addListener(seen::add);

    InterceptorEventPublisher.getInstance()
        .publish(event().attribute("sql", "select * from users").build());

    assertEquals(1, seen.size());
    assertEquals("***", seen.get(0).getAttributes().get("sql"));
    assertEquals("***", InterceptorEventPublisher.getInstance().getHistory().get(0)
        .getAttributes().get("sql"), "the history must not keep the pre-governance copy");
  }

  @Test
  void returningNullDropsTheEventForEveryConsumer() {
    EventTransformers.register(transformer("dropper", 0, event -> null));
    List<InterceptorEvent> seen = new ArrayList<>();
    InterceptorEventPublisher.getInstance().addListener(seen::add);

    InterceptorEventPublisher.getInstance().publish(event().build());

    assertTrue(seen.isEmpty());
    assertTrue(InterceptorEventPublisher.getInstance().getHistory().isEmpty());
  }

  @Test
  void lowerPriorityRunsFirstAndLaterTransformersSeeEarlierChanges() {
    StringBuilder order = new StringBuilder();
    EventTransformers.register(
        transformer(
            "second",
            10,
            event -> {
              order.append("second:").append(event.getType()).append(";");
              return event;
            }));
    EventTransformers.register(
        transformer(
            "first",
            -10,
            event -> {
              order.append("first;");
              return event.toBuilder().type("renamed").build();
            }));

    InterceptorEvent result = EventTransformers.transform(event().build());

    assertEquals("first;second:renamed;", order.toString());
    assertEquals("renamed", result.getType());
  }

  @Test
  void processorsSharingTheDefaultPriorityKeepRegistrationOrder() {
    StringBuilder order = new StringBuilder();
    EventTransformers.register(transformer("one", 0, event -> { order.append("1"); return event; }));
    EventTransformers.register(transformer("two", 0, event -> { order.append("2"); return event; }));

    EventTransformers.transform(event().build());

    assertEquals("12", order.toString());
  }

  @Test
  void aNullResultEndsTheChain() {
    StringBuilder later = new StringBuilder();
    EventTransformers.register(transformer("dropper", -10, event -> null));
    EventTransformers.register(transformer("later", 0, event -> { later.append("ran"); return event; }));

    assertNull(EventTransformers.transform(event().build()));
    assertEquals("", later.toString());
  }

  @Test
  void aThrowingTransformerIsSkippedAndTheEventStillReachesConsumers() {
    EventTransformers.register(
        transformer("broken", -10, event -> { throw new IllegalStateException("boom"); }));
    EventTransformers.register(transformer("after", 0, event -> event.toBuilder().type("after").build()));
    List<InterceptorEvent> seen = new ArrayList<>();
    InterceptorEventPublisher.getInstance().addListener(seen::add);

    InterceptorEventPublisher.getInstance().publish(event().build());

    assertEquals(1, seen.size());
    assertEquals("after", seen.get(0).getType());
    ExtensionStats stats =
        ExtensionPerformanceTracker.get(ExtensionPerformanceTracker.KIND_EVENT_TRANSFORMER, "broken");
    assertNotNull(stats);
    assertEquals(1, stats.getErrors());
  }

  @Test
  void transformIsANoOpWhenNothingIsRegistered() {
    InterceptorEvent event = event().build();

    assertSame(event, EventTransformers.transform(event));
    assertNull(EventTransformers.transform(null));
    assertTrue(EventTransformers.getTransformers().isEmpty());
  }

  @Test
  void statsCountProcessedDroppedAndFailures() {
    EventTransformers.register(
        transformer("half", 0, event -> "drop".equals(event.getType()) ? null : event));
    EventTransformers.register(
        transformer(
            "broken",
            0,
            event -> {
              if ("bad".equals(event.getType())) {
                throw new IllegalStateException("boom");
              }
              return event;
            }));

    EventTransformers.transform(event().build());
    EventTransformers.transform(event().type("drop").build());
    EventTransformers.transform(event().type("bad").build());

    EventTransformerStats half = EventTransformers.getStats().get("half");
    assertNotNull(half);
    assertEquals(2, half.getProcessed());
    assertEquals(1, half.getDropped());
    assertEquals(0, half.getFailures());

    EventTransformerStats broken = EventTransformers.getStats().get("broken");
    assertNotNull(broken);
    assertEquals(1, broken.getFailures());
  }

  @Test
  void registeringTheSameTransformerTwiceAddsItOnce() {
    InterceptorEventTransformer transformer = transformer("one", 0, event -> event);

    EventTransformers.register(transformer);
    EventTransformers.register(transformer);

    assertEquals(1, EventTransformers.getTransformers().size());
  }

  @Test
  void unregisterRemovesTheTransformer() {
    InterceptorEventTransformer transformer = transformer("one", 0, event -> null);
    EventTransformers.register(transformer);

    EventTransformers.unregister(transformer);

    assertTrue(EventTransformers.getTransformers().isEmpty());
    assertNotNull(EventTransformers.transform(event().build()));
  }

  @Test
  void toBuilderKeepsTheTimingAndTraceCorrelation() {
    InterceptorEvent original =
        event().attribute("spanId", "s-1").attribute("traceId", "t-1").timestamp(1234L).build();

    InterceptorEvent copy = original.toBuilder().attribute("sql", "***").build();

    assertEquals(1234L, copy.getTimestamp());
    assertEquals("t-1", copy.getAttributes().get(InterceptorEvent.TRACE_ID));
    assertEquals("s-1", copy.getAttributes().get(InterceptorEvent.SPAN_ID));
    assertEquals("***", copy.getAttributes().get("sql"));
    assertFalse(original.getAttributes().containsKey("sql"), "the original must stay untouched");
  }

  private static InterceptorEvent.Builder event() {
    return InterceptorEvent.builder().type("slow-query").plugin("jdbc").className("PgStatement");
  }

  private static InterceptorEventTransformer transformer(
      String name, int priority, java.util.function.UnaryOperator<InterceptorEvent> body) {
    return new InterceptorEventTransformer() {
      @Override
      public String name() {
        return name;
      }

      @Override
      public int priority() {
        return priority;
      }

      @Override
      public InterceptorEvent transform(InterceptorEvent event) {
        return body.apply(event);
      }
    };
  }
}