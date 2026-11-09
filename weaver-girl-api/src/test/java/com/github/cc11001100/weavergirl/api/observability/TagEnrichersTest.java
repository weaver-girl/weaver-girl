package com.github.cc11001100.weavergirl.api.observability;

import static org.junit.jupiter.api.Assertions.*;

import com.github.cc11001100.weavergirl.api.tracing.SpanContext;
import com.github.cc11001100.weavergirl.api.tracing.SpanProcessor;
import com.github.cc11001100.weavergirl.api.tracing.SpanProcessors;
import com.github.cc11001100.weavergirl.api.tracing.Tracer;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

/** Registered enrichers add tags to every finished span, in priority order, before governance. */
class TagEnrichersTest {

  @AfterEach
  void reset() {
    TagEnrichers.clear();
    SpanProcessors.clear();
    ExtensionPerformanceTracker.clear();
    Tracer.clearCurrentSpan();
  }

  @Test
  void anEnricherAddsItsTagsToTheSpan() {
    TagEnrichers.register(enricher("tenant", 0, span -> tags("tenant.id", "t-1")));

    SpanContext enriched = TagEnrichers.apply(span());

    assertEquals("t-1", enriched.getAttributes().get("tenant.id"));
  }

  @Test
  void applyReturnsTheInputUnchangedWhenNothingIsRegistered() {
    SpanContext span = span();

    assertSame(span, TagEnrichers.apply(span));
    assertTrue(TagEnrichers.isEmpty());
  }

  @Test
  void applyToleratesANullSpan() {
    TagEnrichers.register(enricher("tenant", 0, span -> tags("a", "b")));

    assertNull(TagEnrichers.apply(null));
  }

  @Test
  void returningAnEmptyMapLeavesTheSpanIdentical() {
    TagEnrichers.register(enricher("noop", 0, span -> Collections.<String, String>emptyMap()));
    SpanContext span = span();

    assertSame(span, TagEnrichers.apply(span), "an empty result must not rebuild the span");
  }

  @Test
  void lowerPriorityRunsFirstAndLaterEnrichersSeeEarlierTags() {
    StringBuilder order = new StringBuilder();
    TagEnrichers.register(
        enricher(
            "last",
            50,
            span -> {
              order.append("last:").append(span.getAttributes().get("first")).append(";");
              return tags("third", "c");
            }));
    TagEnrichers.register(
        enricher(
            "first",
            -50,
            span -> {
              order.append("first;");
              return tags("first", "a");
            }));

    TagEnrichers.apply(span());

    assertEquals("first;last:a;", order.toString());
    assertEquals("[first, last]", TagEnrichers.getEnrichers().stream()
        .map(TagEnricher::name)
        .collect(java.util.stream.Collectors.toList())
        .toString());
  }

  @Test
  void enrichersShareTheDefaultPriorityInRegistrationOrder() {
    StringBuilder order = new StringBuilder();
    TagEnrichers.register(
        enricher(
            "one",
            0,
            span -> {
              order.append("1");
              return null;
            }));
    TagEnrichers.register(
        enricher(
            "two",
            0,
            span -> {
              order.append("2");
              return null;
            }));

    TagEnrichers.apply(span());

    assertEquals("12", order.toString());
  }

  @Test
  void aThrowingEnricherIsSkippedAndTheOthersStillApply() {
    TagEnrichers.register(
        enricher(
            "broken",
            -10,
            span -> {
              throw new IllegalStateException("boom");
            }));
    TagEnrichers.register(enricher("tenant", 0, span -> tags("tenant.id", "t-1")));

    SpanContext enriched = TagEnrichers.apply(span());

    assertEquals("t-1", enriched.getAttributes().get("tenant.id"));
    ExtensionStats stats =
        ExtensionPerformanceTracker.get(
            ExtensionPerformanceTracker.KIND_TAG_ENRICHER, "broken");
    assertNotNull(stats);
    assertEquals(1, stats.getErrors());
  }

  @Test
  void aNullTagMapIsTreatedAsNoTags() {
    TagEnrichers.register(enricher("null", 0, span -> null));
    SpanContext span = span();

    assertSame(span, TagEnrichers.apply(span));
    ExtensionStats stats =
        ExtensionPerformanceTracker.get(ExtensionPerformanceTracker.KIND_TAG_ENRICHER, "null");
    assertNotNull(stats);
    assertEquals(0, stats.getErrors(), "returning null is a skip, not a failure");
  }

  @Test
  void registeringTheSameEnricherTwiceAddsItOnce() {
    TagEnricher enricher = enricher("tenant", 0, span -> tags("a", "b"));

    TagEnrichers.register(enricher);
    TagEnrichers.register(enricher);

    assertEquals(1, TagEnrichers.getEnrichers().size());
  }

  @Test
  void unregisteringRemovesTheEnricher() {
    TagEnricher enricher = enricher("tenant", 0, span -> tags("a", "b"));
    TagEnrichers.register(enricher);

    TagEnrichers.unregister(enricher);

    assertTrue(TagEnrichers.isEmpty());
    assertNull(TagEnrichers.apply(span()).getAttributes().get("a"));
  }

  @Test
  void aBlankOrThrowingNameIsReportedAsUnknown() {
    TagEnrichers.register(enricher("  ", 0, span -> tags("a", "b")));
    TagEnrichers.register(
        new TagEnricher() {
          @Override
          public String name() {
            throw new IllegalStateException("no name");
          }

          @Override
          public Map<String, String> enrichTags(SpanContext span) {
            return tags("c", "d");
          }
        });

    TagEnrichers.apply(span());

    // Both are recorded under "unknown", which is why the counter is shared.
    ExtensionStats stats =
        ExtensionPerformanceTracker.get(
            ExtensionPerformanceTracker.KIND_TAG_ENRICHER, "unknown");
    assertNotNull(stats);
    assertEquals(2, stats.getInvocations());
  }

  @Test
  void endingASpanEnrichesItBeforeTheProcessorChainRuns() {
    TagEnrichers.register(enricher("tenant", 0, span -> tags("tenant.id", "t-1")));
    AtomicReference<Map<String, String>> seenByProcessor = new AtomicReference<>();
    AtomicReference<Map<String, String>> published = new AtomicReference<>();
    SpanProcessors.register(
        new SpanProcessor() {
          @Override
          public String name() {
            return "observer";
          }

          @Override
          public int priority() {
            return 100;
          }

          @Override
          public SpanContext process(SpanContext span) {
            seenByProcessor.set(new LinkedHashMap<>(span.getAttributes()));
            return span;
          }
        });
    com.github.cc11001100.weavergirl.api.tracing.SpanCompletionListener listener =
        (span, durationMs) -> published.set(new LinkedHashMap<>(span.getAttributes()));
    Tracer.addCompletionListener(listener);
    SpanContext ended;
    try {
      Tracer.startSpan("op");
      ended = Tracer.endSpan("op", "OK");
    } finally {
      Tracer.removeCompletionListener(listener);
    }

    assertNotNull(ended);
    assertEquals("t-1", seenByProcessor.get().get("tenant.id"),
        "governance must see enriched tags so it can redact them");
    assertEquals("t-1", published.get().get("tenant.id"),
        "listeners and exporters receive the enriched span");
  }

  @Test
  void aProcessorThatDropsTheSpanAlsoDropsTheEnrichedTags() {
    TagEnrichers.register(enricher("tenant", 0, span -> tags("tenant.id", "t-1")));
    SpanProcessors.register(
        new SpanProcessor() {
          @Override
          public String name() {
            return "dropper";
          }

          @Override
          public SpanContext process(SpanContext span) {
            return null;
          }
        });

    Tracer.startSpan("op");

    assertNull(Tracer.endSpan("op", "OK"));
  }

  private static SpanContext span() {
    return SpanContext.builder().traceId("0af7651916cd43dd8448eb211c80319c").spanId("b7ad6b7169203331").build();
  }

  private static Map<String, String> tags(String key, String value) {
    Map<String, String> tags = new LinkedHashMap<>();
    tags.put(key, value);
    return tags;
  }

  private static TagEnricher enricher(
      String name, int priority, java.util.function.Function<SpanContext, Map<String, String>> body) {
    return new TagEnricher() {
      @Override
      public String name() {
        return name;
      }

      @Override
      public int priority() {
        return priority;
      }

      @Override
      public Map<String, String> enrichTags(SpanContext span) {
        return body.apply(span);
      }
    };
  }
}
