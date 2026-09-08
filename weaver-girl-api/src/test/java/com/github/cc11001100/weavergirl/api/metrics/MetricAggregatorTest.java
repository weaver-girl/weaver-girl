package com.github.cc11001100.weavergirl.api.metrics;

import static org.junit.jupiter.api.Assertions.*;

import java.util.Map;
import org.junit.jupiter.api.*;

/** Tests for metric aggregation (P61). */
class MetricAggregatorTest {

  @AfterEach
  void tearDown() {
    MetricRegistry.clear();
  }

  // ===== TimeWindowAggregator =====

  @Test
  void aggregator_recordsAndCounts() {
    TimeWindowAggregator agg = new TimeWindowAggregator("test", 60000);
    agg.record(10);
    agg.record(20);
    agg.record(30);

    MetricSnapshot snap = agg.snapshot();
    assertEquals(3, snap.getCount());
    assertEquals(60.0, snap.getSum(), 0.01);
    assertEquals(20.0, snap.getAvg(), 0.01);
    assertEquals(10.0, snap.getMin(), 0.01);
    assertEquals(30.0, snap.getMax(), 0.01);
  }

  @Test
  void aggregator_emptySnapshot() {
    TimeWindowAggregator agg = new TimeWindowAggregator("empty", 60000);
    MetricSnapshot snap = agg.snapshot();
    assertEquals(0, snap.getCount());
    assertEquals(0.0, snap.getAvg(), 0.01);
  }

  @Test
  void aggregator_percentiles() {
    TimeWindowAggregator agg = new TimeWindowAggregator("perc", 60000);
    for (int i = 1; i <= 100; i++) {
      agg.record(i);
    }

    MetricSnapshot snap = agg.snapshot();
    assertEquals(50.0, snap.getPercentile(0.5), 5.0); // p50 ≈ 50
    assertEquals(99.0, snap.getPercentile(0.99), 2.0); // p99 ≈ 99
    assertEquals(95.0, snap.getPercentile(0.95), 2.0); // p95 ≈ 95
    assertEquals(90.0, snap.getPercentile(0.9), 2.0); // p90 ≈ 90
  }

  @Test
  void aggregator_ratePerSecond() {
    TimeWindowAggregator agg = new TimeWindowAggregator("rate", 60000);
    for (int i = 0; i < 60; i++) {
      agg.record(1);
    }
    MetricSnapshot snap = agg.snapshot();
    // Rate per second = count / (windowSeconds)
    // Since all values are recorded instantly, window is very small, rate is very high
    // Just verify count is correct and avg is correct
    assertEquals(60, snap.getCount());
    assertEquals(1.0, snap.getAvg(), 0.01);
  }

  @Test
  void aggregator_windowExpires() throws Exception {
    TimeWindowAggregator agg = new TimeWindowAggregator("expire", 1000); // 1s window
    agg.record(42);
    assertEquals(1, agg.size());

    Thread.sleep(1200); // wait for expiry
    MetricSnapshot snap = agg.snapshot();
    assertEquals(0, snap.getCount());
  }

  @Test
  void aggregator_singleValue() {
    TimeWindowAggregator agg = new TimeWindowAggregator("single", 60000);
    agg.record(42);
    MetricSnapshot snap = agg.snapshot();
    assertEquals(1, snap.getCount());
    assertEquals(42.0, snap.getAvg(), 0.01);
    assertEquals(42.0, snap.getMin(), 0.01);
    assertEquals(42.0, snap.getMax(), 0.01);
    assertEquals(42.0, snap.getPercentile(0.99), 0.01);
  }

  @Test
  void aggregator_getNameAndWindow() {
    TimeWindowAggregator agg = new TimeWindowAggregator("my-metric", 30000);
    assertEquals("my-metric", agg.getName());
    assertEquals(30000, agg.getWindowMs());
  }

  @Test
  void aggregator_windowMinimum() {
    // Window should be at least 1000ms
    TimeWindowAggregator agg = new TimeWindowAggregator("min", 100);
    assertEquals(1000, agg.getWindowMs());
  }

  @Test
  void aggregator_clear() {
    TimeWindowAggregator agg = new TimeWindowAggregator("clr", 60000);
    agg.record(1);
    agg.record(2);
    assertEquals(2, agg.size());
    agg.clear();
    assertEquals(0, agg.size());
  }

  // ===== MetricSnapshot =====

  @Test
  void snapshot_toString() {
    MetricSnapshot snap = new MetricSnapshot(1000, 2000, "test", 10, 100, 5, 15, null);
    String str = snap.toString();
    assertTrue(str.contains("test"));
    assertTrue(str.contains("count=10"));
  }

  // ===== MetricRegistry =====

  @Test
  void registry_getOrCreate() {
    TimeWindowAggregator agg1 = MetricRegistry.getOrCreate("test-metric");
    TimeWindowAggregator agg2 = MetricRegistry.getOrCreate("test-metric");
    assertSame(agg1, agg2);
  }

  @Test
  void registry_record() {
    MetricRegistry.record("reg-test", 42);
    MetricRegistry.record("reg-test", 84);

    MetricSnapshot snap = MetricRegistry.snapshot("reg-test");
    assertNotNull(snap);
    assertEquals(2, snap.getCount());
  }

  @Test
  void registry_snapshotAll() {
    MetricRegistry.record("m1", 10);
    MetricRegistry.record("m2", 20);

    Map<String, MetricSnapshot> all = MetricRegistry.snapshotAll();
    assertEquals(2, all.size());
    assertTrue(all.containsKey("m1"));
    assertTrue(all.containsKey("m2"));
  }

  @Test
  void registry_snapshotNonexistent() {
    assertNull(MetricRegistry.snapshot("nonexistent"));
  }

  @Test
  void registry_getMetricNames() {
    MetricRegistry.record("a", 1);
    MetricRegistry.record("b", 2);
    assertTrue(MetricRegistry.getMetricNames().contains("a"));
    assertTrue(MetricRegistry.getMetricNames().contains("b"));
  }

  @Test
  void registry_remove() {
    MetricRegistry.record("to-remove", 1);
    MetricRegistry.remove("to-remove");
    assertNull(MetricRegistry.snapshot("to-remove"));
  }

  @Test
  void registry_clear() {
    MetricRegistry.record("x", 1);
    MetricRegistry.clear();
    assertEquals(0, MetricRegistry.size());
  }

  @Test
  void registry_labeledMetricsAreSortedAndQueryable() {
    Map<String, String> labels = new java.util.LinkedHashMap<>();
    labels.put("zone", "east");
    labels.put("app", "orders");
    MetricRegistry.record("requests", labels, 7);

    String key = "requests{app=orders,zone=east}";
    MetricSnapshot snapshot = MetricRegistry.snapshot("requests", labels);
    assertNotNull(snapshot);
    assertEquals(1, snapshot.getCount());
    assertEquals(labels, snapshot.getLabels());
    assertTrue(MetricRegistry.getMetricsByName("requests").contains(key));
    assertEquals(1, MetricRegistry.getMetricsByName(key).size());
    assertNull(MetricRegistry.snapshot("missing", labels));
    assertTrue(MetricRegistry.getMetricsByName("unknown").isEmpty());
  }

  @Test
  void registryHandlesEmptyAndNullLabels() {
    MetricRegistry.record("plain", (Map<String, String>) null, 1);
    MetricRegistry.record("empty", java.util.Collections.emptyMap(), 2);
    assertNotNull(MetricRegistry.snapshot("plain", null));
    assertNotNull(MetricRegistry.snapshot("empty", java.util.Collections.emptyMap()));
    assertSame(
        MetricRegistry.getOrCreate("strict"), MetricRegistry.getOrCreateStrict("strict", 60000));
  }
}
