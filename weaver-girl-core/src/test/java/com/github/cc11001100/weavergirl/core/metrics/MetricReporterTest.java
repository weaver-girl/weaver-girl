package com.github.cc11001100.weavergirl.core.metrics;

import static org.junit.jupiter.api.Assertions.*;

import java.util.*;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class MetricReporterTest {

  private MetricReporter reporter;

  @BeforeEach
  void setUp() {
    reporter = new MetricReporter(1000, 10);
  }

  @AfterEach
  void tearDown() {
    if (reporter.isRunning()) {
      reporter.stop();
    }
  }

  // === Registration ===

  @Test
  void registerMetric_addsSupplier() {
    reporter.registerMetric("test.metric", () -> 42);
    assertEquals(1, reporter.getMetricCount());
    assertTrue(reporter.getMetricNames().contains("test.metric"));
  }

  @Test
  void registerMetric_chaining() {
    reporter.registerMetric("m1", () -> 1).registerMetric("m2", () -> 2);
    assertEquals(2, reporter.getMetricCount());
  }

  @Test
  void unregisterMetric_removesSupplier() {
    reporter.registerMetric("test", () -> 42);
    reporter.unregisterMetric("test");
    assertEquals(0, reporter.getMetricCount());
  }

  @Test
  void addFormatter_addsSuccessfully() {
    reporter.addFormatter(snapshot -> {});
    assertEquals(1, reporter.getFormatterCount());
  }

  @Test
  void removeFormatter_removesSuccessfully() {
    MetricFormatter fmt = snapshot -> {};
    reporter.addFormatter(fmt);
    reporter.removeFormatter(fmt);
    assertEquals(0, reporter.getFormatterCount());
  }

  // === Lifecycle ===

  @Test
  void start_beginsReporting() {
    reporter.start();
    assertTrue(reporter.isRunning());
  }

  @Test
  void start_idempotent() {
    reporter.start();
    reporter.start();
    assertTrue(reporter.isRunning());
  }

  @Test
  void stop_endsReporting() {
    reporter.start();
    reporter.stop();
    assertFalse(reporter.isRunning());
  }

  @Test
  void stop_idempotent() {
    reporter.start();
    reporter.stop();
    reporter.stop();
    assertFalse(reporter.isRunning());
  }

  // === Collection ===

  @Test
  void collectAndReport_returnsSnapshot() {
    reporter.registerMetric("count", () -> 100);
    MetricSnapshot snapshot = reporter.collectAndReport();
    assertNotNull(snapshot);
    assertEquals(100, snapshot.get("count"));
  }

  @Test
  void collectAndReport_multipleMetrics() {
    reporter.registerMetric("heap.used", () -> 256);
    reporter.registerMetric("heap.max", () -> 1024);
    reporter.registerMetric("threads", () -> 42);
    MetricSnapshot snapshot = reporter.collectAndReport();
    assertEquals(3, snapshot.size());
    assertEquals(256, snapshot.get("heap.used"));
    assertEquals(1024, snapshot.get("heap.max"));
    assertEquals(42, snapshot.get("threads"));
  }

  @Test
  void collectAndReport_exceptionInSupplier_reportsError() {
    reporter.registerMetric(
        "broken",
        () -> {
          throw new RuntimeException("oops");
        });
    MetricSnapshot snapshot = reporter.collectAndReport();
    Object val = snapshot.get("broken");
    assertTrue(val.toString().contains("error:oops"));
  }

  @Test
  void collectAndReport_nullSupplier_returnsNull() {
    reporter.registerMetric("nullable", () -> null);
    MetricSnapshot snapshot = reporter.collectAndReport();
    assertEquals("null", snapshot.get("nullable"));
  }

  @Test
  void collectAndReport_incrementsReportCount() {
    assertEquals(0, reporter.getReportCount());
    reporter.collectAndReport();
    assertEquals(1, reporter.getReportCount());
    reporter.collectAndReport();
    assertEquals(2, reporter.getReportCount());
  }

  @Test
  void collectAndReport_callsFormatters() {
    List<MetricSnapshot> formatted = new ArrayList<>();
    reporter.addFormatter(formatted::add);
    reporter.registerMetric("test", () -> 1);
    reporter.collectAndReport();
    assertEquals(1, formatted.size());
    assertEquals(1, formatted.get(0).get("test"));
  }

  @Test
  void collectAndReport_formatterException_doesNotCrash() {
    reporter.addFormatter(
        snapshot -> {
          throw new RuntimeException("formatter error");
        });
    reporter.registerMetric("test", () -> 1);
    assertDoesNotThrow(() -> reporter.collectAndReport());
  }

  // === History ===

  @Test
  void getLatestSnapshot_returnsLatest() {
    AtomicInteger counter = new AtomicInteger();
    reporter.registerMetric("count", counter::incrementAndGet);
    reporter.collectAndReport();
    reporter.collectAndReport();
    MetricSnapshot latest = reporter.getLatestSnapshot();
    assertNotNull(latest);
    assertEquals(2, latest.get("count"));
  }

  @Test
  void getLatestSnapshot_noReports_returnsNull() {
    assertNull(reporter.getLatestSnapshot());
  }

  @Test
  void getHistory_returnsSnapshots() {
    reporter.registerMetric("val", () -> 1);
    reporter.collectAndReport();
    reporter.collectAndReport();
    List<MetricSnapshot> history = reporter.getHistory();
    assertEquals(2, history.size());
  }

  @Test
  void getHistory_boundedToHistorySize() {
    MetricReporter smallReporter = new MetricReporter(1000, 3);
    smallReporter.registerMetric("val", () -> 1);
    for (int i = 0; i < 10; i++) {
      smallReporter.collectAndReport();
    }
    assertEquals(3, smallReporter.getHistory().size());
  }

  @Test
  void getHistory_isUnmodifiable() {
    reporter.registerMetric("val", () -> 1);
    reporter.collectAndReport();
    assertThrows(UnsupportedOperationException.class, () -> reporter.getHistory().add(null));
  }

  // === Accessors ===

  @Test
  void getIntervalMs_returnsConfigured() {
    assertEquals(1000, reporter.getIntervalMs());
  }

  @Test
  void getHistorySize_returnsConfigured() {
    assertEquals(10, reporter.getHistorySize());
  }

  @Test
  void getMetricNames_returnsNames() {
    reporter.registerMetric("a", () -> 1);
    reporter.registerMetric("b", () -> 2);
    Set<String> names = reporter.getMetricNames();
    assertTrue(names.contains("a"));
    assertTrue(names.contains("b"));
  }

  // === MetricSnapshot ===

  @Test
  void metricSnapshot_toJson() {
    Map<String, Object> values = new LinkedHashMap<>();
    values.put("count", 42);
    values.put("name", "test");
    MetricSnapshot snapshot = new MetricSnapshot(1000, values);
    String json = snapshot.toJson();
    assertTrue(json.contains("\"timestamp\":1000"));
    assertTrue(json.contains("\"count\":42"));
    assertTrue(json.contains("\"name\":\"test\""));
  }

  @Test
  void metricSnapshot_valuesAreUnmodifiable() {
    Map<String, Object> values = new LinkedHashMap<>();
    values.put("key", "val");
    MetricSnapshot snapshot = new MetricSnapshot(0, values);
    assertThrows(
        UnsupportedOperationException.class, () -> snapshot.getValues().put("new", "value"));
  }

  @Test
  void metricSnapshot_toString_containsInfo() {
    MetricSnapshot snapshot = new MetricSnapshot(1000, Collections.singletonMap("k", 1));
    assertTrue(snapshot.toString().contains("ts=1000"));
  }

  // === Constructor defaults ===

  @Test
  void constructor_defaultInterval() {
    MetricReporter r = new MetricReporter();
    assertEquals(60_000, r.getIntervalMs());
  }

  @Test
  void constructor_zeroInterval_usesDefault() {
    MetricReporter r = new MetricReporter(0, 10);
    assertEquals(60_000, r.getIntervalMs());
  }

  @Test
  void constructor_zeroHistory_usesDefault() {
    MetricReporter r = new MetricReporter(1000, 0);
    assertEquals(60, r.getHistorySize());
  }
}
