package com.github.cc11001100.weavergirl.core.management;

import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

/** The monitor reads the live JVM and never throws when a bean is missing. */
class AgentSelfMonitorTest {

  @Test
  void readingsComeFromTheRunningJvm() {
    double cpu = AgentSelfMonitor.getCpuUsage();
    assertTrue(cpu == -1 || cpu >= 0, "cpu=" + cpu);

    assertTrue(AgentSelfMonitor.getThreadCount() > 0);
    assertTrue(AgentSelfMonitor.getDaemonThreadCount() >= 0);
    assertTrue(AgentSelfMonitor.getHeapMemoryUsed() > 0);
    assertTrue(AgentSelfMonitor.getNonHeapMemoryUsed() >= 0);
  }
}
