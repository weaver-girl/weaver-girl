package com.github.cc11001100.weavergirl.api.observability;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/** The metric governance chain drops, rewrites and isolates samples before a scrape renders them. */
class MetricProcessorsTest {

  @BeforeEach
  @AfterEach
  void reset() {
    MetricProcessors.clear();
    ExtensionPerformanceTracker.clear();
  }

  @Test
  void aProcessorRenamesRelabelsAndDropsSamples() {
    MetricProcessors.register(
        new MetricProcessor() {
          @Override
          public String name() {
            return "governor";
          }

          @Override
          public MetricPoint process(MetricPoint point) {
            if (point.name().startsWith("noisy_")) {
              return null;
            }
            Map<String, String> labels = new LinkedHashMap<>(point.labels());
            labels.remove("path");
            return point.toBuilder().name("app_" + point.name()).labels(labels).build();
          }
        });

    MetricPoint kept =
        MetricProcessors.apply(
            MetricPoint.gauge("requests", "Requests", labels("path", "/x", "status", "200"), 7));

    assertNotNull(kept);
    assertEquals("app_requests", kept.name());
    assertEquals(Collections.singletonMap("status", "200"), kept.labels());
    assertEquals(7, kept.value());
    assertEquals(MetricPoint.Type.GAUGE, kept.type());

    assertNull(MetricProcessors.apply(MetricPoint.counter("noisy_hits", null, null, 1)));
  }

  @Test
  void lowerPriorityRunsFirstAndLaterProcessorsSeeEarlierChanges() {
    StringBuilder order = new StringBuilder();
    MetricProcessors.register(
        processor(
            "second",
            10,
            point -> {
              order.append("second:").append(point.name()).append(";");
              return point;
            }));
    MetricProcessors.register(
        processor(
            "first",
            -10,
            point -> {
              order.append("first;");
              return point.toBuilder().name("renamed").build();
            }));

    MetricPoint result = MetricProcessors.apply(MetricPoint.gauge("original", null, null, 1));

    assertEquals("first;second:renamed;", order.toString());
    assertEquals("renamed", result.name());
  }

  @Test
  void processorsSharingTheDefaultPriorityKeepRegistrationOrder() {
    StringBuilder order = new StringBuilder();
    MetricProcessors.register(processor("one", 0, point -> { order.append("1"); return point; }));
    MetricProcessors.register(processor("two", 0, point -> { order.append("2"); return point; }));

    MetricProcessors.apply(MetricPoint.gauge("m", null, null, 1));

    assertEquals("12", order.toString());
  }

  @Test
  void aNullResultEndsTheChain() {
    StringBuilder later = new StringBuilder();
    MetricProcessors.register(processor("dropper", -10, point -> null));
    MetricProcessors.register(processor("later", 0, point -> { later.append("ran"); return point; }));

    assertNull(MetricProcessors.apply(MetricPoint.gauge("m", null, null, 1)));
    assertEquals("", later.toString());
  }

  @Test
  void aThrowingProcessorIsSkippedAndTheSampleIsKept() {
    MetricProcessors.register(
        processor("broken", -10, point -> { throw new IllegalStateException("boom"); }));
    MetricProcessors.register(
        processor("after", 0, point -> point.toBuilder().name("after").build()));

    MetricPoint result = MetricProcessors.apply(MetricPoint.gauge("m", null, null, 1));

    assertNotNull(result);
    assertEquals("after", result.name());
    ExtensionStats stats =
        ExtensionPerformanceTracker.get(ExtensionPerformanceTracker.KIND_METRIC_PROCESSOR, "broken");
    assertNotNull(stats);
    assertEquals(1, stats.getErrors());
  }

  @Test
  void applyIsANoOpWhenNothingIsRegistered() {
    MetricPoint point = MetricPoint.gauge("m", null, null, 1);

    assertSame(point, MetricProcessors.apply(point));
    assertTrue(MetricProcessors.isEmpty());
    assertNull(MetricProcessors.apply(null));
  }

  @Test
  void statsCountProcessedDroppedAndFailures() {
    MetricProcessors.register(
        processor("half", 0, point -> point.name().endsWith("_drop") ? null : point));
    MetricProcessors.register(
        processor(
            "broken",
            0,
            point -> {
              if (point.name().equals("bad")) {
                throw new IllegalStateException("boom");
              }
              return point;
            }));

    MetricProcessors.apply(MetricPoint.gauge("keep", null, null, 1));
    MetricProcessors.apply(MetricPoint.gauge("half_drop", null, null, 1));
    MetricProcessors.apply(MetricPoint.gauge("bad", null, null, 1));

    MetricProcessorStats half = MetricProcessors.getStats().get("half");
    assertNotNull(half);
    assertEquals(2, half.getProcessed());
    assertEquals(1, half.getDropped());
    assertEquals(0, half.getFailures());

    MetricProcessorStats broken = MetricProcessors.getStats().get("broken");
    assertNotNull(broken);
    assertEquals(1, broken.getFailures());
  }

  @Test
  void registeringTheSameProcessorTwiceAddsItOnce() {
    MetricProcessor processor = processor("one", 0, point -> point);

    MetricProcessors.register(processor);
    MetricProcessors.register(processor);

    assertEquals(1, MetricProcessors.getProcessors().size());
  }

  @Test
  void unregisterRemovesTheProcessor() {
    MetricProcessor processor = processor("one", 0, point -> null);
    MetricProcessors.register(processor);

    MetricProcessors.unregister(processor);

    assertTrue(MetricProcessors.isEmpty());
    assertNotNull(MetricProcessors.apply(MetricPoint.gauge("m", null, null, 1)));
  }

  @Test
  void aBlankOrThrowingNameIsReportedAsUnknown() {
    MetricProcessors.register(processor("  ", 0, point -> point));
    MetricProcessors.register(
        new MetricProcessor() {
          @Override
          public String name() {
            throw new IllegalStateException("no name");
          }

          @Override
          public MetricPoint process(MetricPoint point) {
            return point;
          }
        });

    MetricProcessors.apply(MetricPoint.gauge("m", null, null, 1));

    ExtensionStats stats =
        ExtensionPerformanceTracker.get(
            ExtensionPerformanceTracker.KIND_METRIC_PROCESSOR, "unknown");
    assertNotNull(stats);
    assertEquals(2, stats.getInvocations());
  }

  @Test
  void aHistogramPassesItsBucketsThroughTheChain() {
    MetricProcessors.register(
        processor(
            "buckets",
            0,
            point -> point.toBuilder().name(point.name() + "_gov").build()));

    MetricPoint result =
        MetricProcessors.apply(
            MetricPoint.histogram(
                "latency", "Latency", labels("route", "/x"), new double[] {1, 10}, new long[] {2, 5}, 42));

    assertNotNull(result);
    assertEquals("latency_gov", result.name());
    assertTrue(result.isHistogram());
    assertArrayEquals(new double[] {1, 10}, result.upperBounds());
    assertArrayEquals(new long[] {2, 5}, result.cumulativeCounts());
    assertEquals(42, result.sum());
    assertEquals(Collections.singletonMap("route", "/x"), result.labels());
  }

  @Test
  void pointsDropNullLabelEntriesAndAreImmutable() {
    MetricPoint point =
        MetricPoint.gauge("m", "help", labels("a", "b", "nullValue", null), 3);

    assertEquals(Collections.singletonMap("a", "b"), point.labels());
    assertEquals("help", point.help());
    assertFalse(point.isHistogram());
    assertNull(point.upperBounds());
  }

  private static MetricProcessor processor(
      String name, int priority, java.util.function.UnaryOperator<MetricPoint> body) {
    return new MetricProcessor() {
      @Override
      public String name() {
        return name;
      }

      @Override
      public int priority() {
        return priority;
      }

      @Override
      public MetricPoint process(MetricPoint point) {
        return body.apply(point);
      }
    };
  }

  private static Map<String, String> labels(String... keysAndValues) {
    Map<String, String> labels = new LinkedHashMap<>();
    for (int i = 0; i + 1 < keysAndValues.length; i += 2) {
      labels.put(keysAndValues[i], keysAndValues[i + 1]);
    }
    return labels;
  }
}