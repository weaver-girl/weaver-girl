package com.github.cc11001100.weavergirl.core.management;

import static org.junit.jupiter.api.Assertions.*;

import org.junit.jupiter.api.Test;

/**
 * Tests for AgentSelfMonitor.
 */
class AgentSelfMonitorTest {

  @Test
  void getCpuUsage_returnsValueOrNegativeOne() {
    double cpu = AgentSelfMonitor.getCpuUsage();
    // May return -1 if not on HotSpot JVM or error occurs
    assertTrue(cpu >= -1.0 && cpu <= 1.0 || cpu == -1);
  }

  @Test
  void getThreadCount_returnsPositiveOrNegativeOne() {
    long count = AgentSelfMonitor.getThreadCount();
    assertTrue(count >= -1);
  }

  @Test
  void getDaemonThreadCount_returnsPositiveOrNegativeOne() {
    long count = AgentSelfMonitor.getDaemonThreadCount();
    assertTrue(count >= -1);
  }

  @Test
  void getHeapMemoryUsed_returnsNonNegativeOrNegativeOne() {
    long used = AgentSelfMonitor.getHeapMemoryUsed();
    assertTrue(used >= -1);
  }

  @Test
  void getNonHeapMemoryUsed_returnsNonNegativeOrNegativeOne() {
    long used = AgentSelfMonitor.getNonHeapMemoryUsed();
    assertTrue(used >= -1);
  }

  @Test
  void multipleCallsAreConsistent() {
    // Multiple calls should not throw
    for (int i = 0; i < 3; i++) {
      assertDoesNotThrow(() -> {
        AgentSelfMonitor.getCpuUsage();
        AgentSelfMonitor.getThreadCount();
        AgentSelfMonitor.getDaemonThreadCount();
        AgentSelfMonitor.getHeapMemoryUsed();
        AgentSelfMonitor.getNonHeapMemoryUsed();
      });
    }
  }
}
