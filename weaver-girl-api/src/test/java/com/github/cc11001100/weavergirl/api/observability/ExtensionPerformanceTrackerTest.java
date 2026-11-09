package com.github.cc11001100.weavergirl.api.observability;

import static org.junit.jupiter.api.Assertions.*;

import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

/** Counters and timings recorded per extension point and extension name. */
class ExtensionPerformanceTrackerTest {

  @AfterEach
  void reset() {
    ExtensionPerformanceTracker.clear();
  }

  @Test
  void nothingIsRecordedUntilAnExtensionIsCalled() {
    assertTrue(ExtensionPerformanceTracker.getAll().isEmpty());
    assertTrue(ExtensionPerformanceTracker.getByKind(
        ExtensionPerformanceTracker.KIND_SPAN_SINK).isEmpty());
    assertNull(
        ExtensionPerformanceTracker.get(
            ExtensionPerformanceTracker.KIND_SPAN_SINK, "anything"));
  }

  @Test
  void callsAndFailuresAreAccumulated() {
    String kind = ExtensionPerformanceTracker.KIND_SPAN_SINK;
    ExtensionPerformanceTracker.record(kind, "backend", 1_000_000L, false);
    ExtensionPerformanceTracker.record(kind, "backend", 3_000_000L, true);

    ExtensionStats stats = ExtensionPerformanceTracker.get(kind, "backend");

    assertNotNull(stats);
    assertEquals("backend", stats.getName());
    assertEquals(kind, stats.getKind());
    assertEquals(2, stats.getInvocations());
    assertEquals(1, stats.getErrors());
    assertEquals(0.5, stats.getErrorRate(), 1e-9);
    assertEquals(4.0, stats.getTotalDurationMs(), 1e-9);
    assertEquals(2.0, stats.getAvgDurationMs(), 1e-9);
    assertTrue(stats.getLastInvocationAt() > 0);
    assertTrue(stats.getLastErrorAt() > 0);
  }

  @Test
  void oneExtensionNeverCalledHasNoLastErrorTimestamp() {
    ExtensionPerformanceTracker.record(
        ExtensionPerformanceTracker.KIND_TAG_ENRICHER, "tenant", 500_000L, false);

    ExtensionStats stats =
        ExtensionPerformanceTracker.get(
            ExtensionPerformanceTracker.KIND_TAG_ENRICHER, "tenant");

    assertEquals(0, stats.getErrors());
    assertEquals(0.0, stats.getErrorRate());
    assertEquals(0, stats.getLastErrorAt());
  }

  @Test
  void kindsAreKeptApart() {
    ExtensionPerformanceTracker.record(ExtensionPerformanceTracker.KIND_SPAN_SINK, "x", 1, true);
    ExtensionPerformanceTracker.record(
        ExtensionPerformanceTracker.KIND_SPAN_PROCESSOR, "x", 1, false);

    assertEquals(
        1,
        ExtensionPerformanceTracker.get(ExtensionPerformanceTracker.KIND_SPAN_SINK, "x")
            .getErrors());
    assertEquals(
        0,
        ExtensionPerformanceTracker.get(ExtensionPerformanceTracker.KIND_SPAN_PROCESSOR, "x")
            .getErrors());
    assertEquals(2, ExtensionPerformanceTracker.getAll().size());
  }

  @Test
  void missingOrBlankNamesAreReportedAsUnknown() {
    ExtensionPerformanceTracker.record(ExtensionPerformanceTracker.KIND_SPAN_SINK, null, 1, false);
    ExtensionPerformanceTracker.record(ExtensionPerformanceTracker.KIND_SPAN_SINK, "   ", 1, true);

    ExtensionStats stats =
        ExtensionPerformanceTracker.get(ExtensionPerformanceTracker.KIND_SPAN_SINK, "unknown");

    assertNotNull(stats);
    assertEquals(2, stats.getInvocations());
    assertEquals(1, stats.getErrors());
  }

  @Test
  void getAllIsSortedByKindThenName() {
    ExtensionPerformanceTracker.record(ExtensionPerformanceTracker.KIND_SPAN_SINK, "zeta", 1, false);
    ExtensionPerformanceTracker.record(ExtensionPerformanceTracker.KIND_SPAN_SINK, "alpha", 1, false);
    ExtensionPerformanceTracker.record(
        ExtensionPerformanceTracker.KIND_HEALTH_CHECK, "mid", 1, false);

    List<ExtensionStats> all = ExtensionPerformanceTracker.getAll();

    assertEquals(3, all.size());
    assertEquals("mid", all.get(0).getName(), "health-check sorts before span-sink");
    assertEquals("alpha", all.get(1).getName());
    assertEquals("zeta", all.get(2).getName());
  }

  @Test
  void getByKindIsSortedByNameAndImmutable() {
    ExtensionPerformanceTracker.record(ExtensionPerformanceTracker.KIND_SPAN_SINK, "b", 1, false);
    ExtensionPerformanceTracker.record(ExtensionPerformanceTracker.KIND_SPAN_SINK, "a", 1, false);

    Map<String, ExtensionStats> stats =
        ExtensionPerformanceTracker.getByKind(ExtensionPerformanceTracker.KIND_SPAN_SINK);

    assertEquals("[a, b]", stats.keySet().toString());
    assertThrows(UnsupportedOperationException.class, () -> stats.put("c", null));
  }

  @Test
  void removingOneExtensionDropsOnlyItsCounters() {
    ExtensionPerformanceTracker.record(ExtensionPerformanceTracker.KIND_SPAN_SINK, "a", 1, false);
    ExtensionPerformanceTracker.record(ExtensionPerformanceTracker.KIND_SPAN_SINK, "b", 1, false);

    ExtensionPerformanceTracker.remove(ExtensionPerformanceTracker.KIND_SPAN_SINK, "a");

    assertNull(ExtensionPerformanceTracker.get(ExtensionPerformanceTracker.KIND_SPAN_SINK, "a"));
    assertNotNull(ExtensionPerformanceTracker.get(ExtensionPerformanceTracker.KIND_SPAN_SINK, "b"));
  }

  @Test
  void negativeDurationsAreIgnoredRatherThanCorruptingTheAverage() {
    ExtensionPerformanceTracker.record(ExtensionPerformanceTracker.KIND_SPAN_SINK, "a", -5, false);
    ExtensionPerformanceTracker.record(ExtensionPerformanceTracker.KIND_SPAN_SINK, "a", 2_000_000L, false);

    ExtensionStats stats =
        ExtensionPerformanceTracker.get(ExtensionPerformanceTracker.KIND_SPAN_SINK, "a");

    assertEquals(2, stats.getInvocations());
    assertEquals(1.0, stats.getAvgDurationMs(), 1e-9);
  }
}
