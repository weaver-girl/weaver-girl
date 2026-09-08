package com.github.cc11001100.weavergirl.api.metrics;

import static org.junit.jupiter.api.Assertions.*;

import java.util.Map;
import org.junit.jupiter.api.Test;

class MetricValueObjectsTest {

  @Test
  void labelsSupportConvenienceFactoriesAndImmutableBuilds() {
    MetricLabels labels = new MetricLabels().label("a", "1").label("b", "2");
    assertEquals(Map.of("a", "1", "b", "2"), labels.build());
    assertEquals(Map.of("x", "y"), MetricLabels.of("x", "y").build());
    assertEquals(Map.of("x", "1", "y", "2"), MetricLabels.of("x", "1", "y", "2").build());
    assertTrue(MetricLabels.empty().isEmpty());
    assertThrows(UnsupportedOperationException.class, () -> labels.build().put("c", "3"));
  }

  @Test
  void snapshotCalculatesAverageRateAndUsesDefensiveCopies() {
    Map<Double, Double> percentiles = Map.of(0.5, 4.0, 0.99, 9.0);
    Map<String, String> labels = Map.of("route", "/items");
    MetricSnapshot snapshot =
        new MetricSnapshot(1000, 3000, "latency", 4, 20, 1, 9, percentiles, labels);
    assertEquals(1000, snapshot.getWindowStartMs());
    assertEquals(3000, snapshot.getWindowEndMs());
    assertEquals("latency", snapshot.getMetricName());
    assertEquals(4, snapshot.getCount());
    assertEquals(20, snapshot.getSum());
    assertEquals(1, snapshot.getMin());
    assertEquals(9, snapshot.getMax());
    assertEquals(5, snapshot.getAvg());
    assertEquals("/items", snapshot.getLabels().get("route"));
    assertEquals(4, snapshot.getPercentile(0.5));
    assertEquals(0, snapshot.getPercentile(0.75));
    assertEquals(2, snapshot.getRatePerSecond());
    assertThrows(UnsupportedOperationException.class, () -> snapshot.getLabels().put("x", "y"));
    assertTrue(snapshot.toString().contains("p99=9.00"));
  }

  @Test
  void snapshotHandlesEmptyAndInvalidWindowCases() {
    MetricSnapshot empty = new MetricSnapshot(10, 10, "empty", 0, 0, 0, 0, null, null);
    assertEquals(0, empty.getAvg());
    assertEquals(0, empty.getRatePerSecond());
    assertTrue(empty.getLabels().isEmpty());
    assertTrue(empty.getPercentiles().isEmpty());
    MetricSnapshot reversed = new MetricSnapshot(20, 10, "reversed", 1, 1, 1, 1, Map.of());
    assertEquals(0, reversed.getRatePerSecond());
  }
}
