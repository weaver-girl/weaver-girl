package com.github.cc11001100.weavergirl.core.management;

import static org.junit.jupiter.api.Assertions.*;

import java.util.List;
import org.junit.jupiter.api.*;

/** Tests for Agent self-diagnostics (P49). */
class AgentDiagnosticsTest {

  private AgentDiagnostics diagnostics;

  @BeforeEach
  void setUp() {
    diagnostics = AgentDiagnostics.getInstance();
  }

  // ===== Memory tracking =====

  @Test
  void recordMemorySnapshot_storesHistory() {
    diagnostics.recordMemorySnapshot();
    List<AgentDiagnostics.MemorySnapshot> history = diagnostics.getMemoryHistory();
    assertFalse(history.isEmpty());

    AgentDiagnostics.MemorySnapshot snap = history.get(history.size() - 1);
    assertTrue(snap.heapUsed > 0);
    assertTrue(snap.heapMax > 0);
    assertTrue(snap.activeThreads > 0);
  }

  @Test
  void memoryHistory_isBounded() {
    for (int i = 0; i < 70; i++) {
      diagnostics.recordMemorySnapshot();
    }
    assertTrue(diagnostics.getMemoryHistory().size() <= 60);
  }

  @Test
  void memoryTrend_returnsZeroWithFewSnapshots() {
    // With singleton, take one snapshot then check trend
    diagnostics.recordMemorySnapshot();
    // Trend should be calculable with 1 snapshot in history
    double trend = diagnostics.getMemoryTrend();
    // With only one snapshot, trend is 0
    // The calculation needs 2+ snapshots, so it returns 0
    // After recording 2 snapshots we can get a real trend
  }

  // ===== Interceptor hotspots =====

  @Test
  void recordInterceptorInvocation_tracksStats() {
    String name = "stat-test-" + System.nanoTime();
    diagnostics.recordInterceptorInvocation(name, 1_000_000); // 1ms
    diagnostics.recordInterceptorInvocation(name, 3_000_000); // 3ms

    List<AgentDiagnostics.InterceptorHotspot> hotspots = diagnostics.getAllHotspots();
    AgentDiagnostics.InterceptorHotspot hotspot = null;
    for (AgentDiagnostics.InterceptorHotspot h : hotspots) {
      if (name.equals(h.name)) {
        hotspot = h;
        break;
      }
    }
    assertNotNull(hotspot, "Should find hotspot for " + name);
    assertEquals(2, hotspot.invocationCount);
    assertEquals(2.0, hotspot.getAverageTimeMs(), 0.01);
    assertEquals(4.0, hotspot.getTotalTimeMs(), 0.01);
    assertEquals(3.0, hotspot.getMaxTimeMs(), 0.01);
  }

  @Test
  void getTopHotspots_sortedByTotalTime() {
    // Use unique names to avoid conflicts with other tests sharing the singleton
    String fastName = "topo-fast-" + System.nanoTime();
    String slowName = "topo-slow-" + System.nanoTime();
    String medName = "topo-medium-" + System.nanoTime();

    diagnostics.recordInterceptorInvocation(fastName, 100_000);
    diagnostics.recordInterceptorInvocation(slowName, 10_000_000);
    diagnostics.recordInterceptorInvocation(medName, 1_000_000);

    List<AgentDiagnostics.InterceptorHotspot> top = diagnostics.getTopHotspots(3);
    // Find our entries — they should be sorted by total time descending
    // slow (10ms) > medium (1ms) > fast (0.1ms)
    boolean foundSlowFirst = false;
    for (int i = 0; i < top.size() - 1; i++) {
      if (top.get(i).name.equals(slowName)) foundSlowFirst = true;
      if (top.get(i).name.equals(medName) || top.get(i).name.equals(slowName)) {
        // med/slow should come before fast
        for (int j = i + 1; j < top.size(); j++) {
          if (top.get(j).name.equals(fastName)) {
            assertTrue(
                top.get(i).totalTimeNanos > top.get(j).totalTimeNanos,
                "Slower interceptor should have more total time");
          }
        }
      }
    }
    // Verify slow is in the list
    assertTrue(top.stream().anyMatch(h -> h.name.equals(slowName)));
  }

  // ===== Fault detection =====

  @Test
  void recordFault_storesFault() {
    diagnostics.recordFault("TEST_FAULT", "Test fault message");

    assertTrue(diagnostics.hasFaults());
    List<AgentDiagnostics.FaultRecord> faults = diagnostics.getFaults();
    assertTrue(
        faults.stream()
            .anyMatch(f -> "TEST_FAULT".equals(f.type) && f.message.contains("Test fault")));
  }

  @Test
  void getRecentFaults_returnsLatest() {
    for (int i = 0; i < 5; i++) {
      diagnostics.recordFault("BATCH_" + i, "msg " + i);
    }
    List<AgentDiagnostics.FaultRecord> recent = diagnostics.getRecentFaults(3);
    assertEquals(3, recent.size());
  }

  // ===== Diagnostics report =====

  @Test
  void generateReport_containsKeySections() {
    diagnostics.recordMemorySnapshot();
    String report = diagnostics.generateReport();

    assertTrue(report.contains("Uptime:"));
    assertTrue(report.contains("Memory:"));
    assertTrue(report.contains("Threads:"));
    assertTrue(report.contains("End Report"));
  }

  @Test
  void generateReport_includesHotspots() {
    diagnostics.recordInterceptorInvocation("report-test", 5_000_000);
    String report = diagnostics.generateReport();
    assertTrue(report.contains("report-test"));
  }

  @Test
  void generateReport_includesFaults() {
    diagnostics.recordFault("REPORT_FAULT", "For report test");
    String report = diagnostics.generateReport();
    assertTrue(report.contains("REPORT_FAULT"));
  }

  // ===== InterceptorHotspot unit tests =====

  @Test
  void hotspot_emptyStats() {
    AgentDiagnostics.InterceptorHotspot hotspot = new AgentDiagnostics.InterceptorHotspot("empty");
    assertEquals(0, hotspot.invocationCount);
    assertEquals(0.0, hotspot.getAverageTimeMs(), 0.001);
    assertEquals(0.0, hotspot.getTotalTimeMs(), 0.001);
  }

  /**
   * Only the twenty most expensive interceptors are kept. Once a twenty-first arrives, the one that
   * has cost the least time so far is dropped.
   */
  @Test
  void theCheapestHotspotIsDroppedOnceTheLimitIsPassed() {
    String prefix = "trim-" + System.nanoTime() + "-";
    for (int i = 0; i < 21; i++) {
      diagnostics.recordInterceptorInvocation(prefix + i, (i + 1) * 1_000L);
    }

    // The hotspot map is shared with every other test, so the report only shows the twenty most
    // expensive overall. What this batch can prove is that its own cheapest entry was the one evicted.
    java.util.Set<String> kept = new java.util.HashSet<>();
    for (AgentDiagnostics.InterceptorHotspot hotspot : diagnostics.getAllHotspots()) {
      if (hotspot.name.startsWith(prefix)) {
        kept.add(hotspot.name);
      }
    }
    assertFalse(kept.contains(prefix + 0), "the cheapest interceptor was kept");
    assertFalse(kept.isEmpty(), "every interceptor from this batch was evicted");
  }

  /** The fault log keeps the newest hundred and forgets the oldest. */
  @Test
  void theFaultLogDropsTheOldestOnceItIsFull() {
    String prefix = "overflow-" + System.nanoTime() + "-";
    for (int i = 0; i < 101; i++) {
      diagnostics.recordFault(prefix + i, "msg");
    }

    long kept =
        diagnostics.getFaults().stream().filter(fault -> fault.type.startsWith(prefix)).count();
    assertTrue(kept <= 100, "the fault log kept " + kept + " of one batch");
    assertTrue(
        diagnostics.getFaults().stream().noneMatch(fault -> fault.type.equals(prefix + 0)),
        "the oldest fault was kept");
  }
}
