package com.github.cc11001100.weavergirl.core.management;

import static org.junit.jupiter.api.Assertions.*;

import java.lang.management.ManagementFactory;
import javax.management.MBeanServer;
import javax.management.ObjectName;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class AgentMonitorTest {

  private AgentMonitor monitor;

  @BeforeEach
  void setUp() {
    monitor = AgentMonitor.getInstance();
    monitor.resetCounters();
    monitor.unregister();
  }

  @AfterEach
  void tearDown() {
    monitor.unregister();
  }

  @Test
  void incrementInterceptCount_accumulates() {
    assertEquals(0, monitor.getTotalInterceptCount());
    monitor.incrementInterceptCount();
    assertEquals(1, monitor.getTotalInterceptCount());
    monitor.incrementInterceptCount();
    monitor.incrementInterceptCount();
    assertEquals(3, monitor.getTotalInterceptCount());
  }

  @Test
  void addInterceptTime_tracksTotalTime() {
    assertEquals(0, monitor.getTotalInterceptTimeMs());
    monitor.addInterceptTime(1_000_000); // 1ms
    assertEquals(1, monitor.getTotalInterceptTimeMs());
    monitor.addInterceptTime(2_000_000); // 2ms
    assertEquals(3, monitor.getTotalInterceptTimeMs());
  }

  @Test
  void averageInterceptTimeUs_calculatesCorrectly() {
    monitor.incrementInterceptCount();
    monitor.incrementInterceptCount();
    monitor.addInterceptTime(2_000_000); // 2ms total = 2000us
    assertEquals(1000.0, monitor.getAverageInterceptTimeUs(), 0.1);
  }

  @Test
  void averageInterceptTimeUs_zeroCount_returnsZero() {
    assertEquals(0.0, monitor.getAverageInterceptTimeUs());
  }

  @Test
  void transformedClassCount_tracksCorrectly() {
    assertEquals(0, monitor.getTransformedClassCount());
    monitor.incrementTransformedClassCount();
    assertEquals(1, monitor.getTransformedClassCount());
  }

  @Test
  void pluginMetadata_setAndGet() {
    monitor.setPluginCount(12);
    monitor.setPluginNames("jdbc,servlet,redis");
    monitor.setAgentVersion("1.2.3");
    monitor.setInterceptorDefinitionCount(30);

    assertEquals(12, monitor.getPluginCount());
    assertEquals("jdbc,servlet,redis", monitor.getPluginNames());
    assertEquals("1.2.3", monitor.getAgentVersion());
    assertEquals(30, monitor.getInterceptorDefinitionCount());
  }

  @Test
  void excludedClassCount_tracksCorrectly() {
    monitor.setExcludedClassCount(42);
    assertEquals(42, monitor.getExcludedClassCount());
  }

  @Test
  void resetCounters_clearsAllCounters() {
    monitor.incrementInterceptCount();
    monitor.addInterceptTime(5_000_000);
    monitor.incrementTransformedClassCount();
    monitor.resetCounters();

    assertEquals(0, monitor.getTotalInterceptCount());
    assertEquals(0, monitor.getTotalInterceptTimeMs());
    assertEquals(0, monitor.getTransformedClassCount());
  }

  @Test
  void register_jmxMBeanAccessible() throws Exception {
    monitor.register();

    MBeanServer server = ManagementFactory.getPlatformMBeanServer();
    ObjectName name = new ObjectName("com.github.cc11001100.weavergirl:type=Agent");
    assertTrue(server.isRegistered(name));

    // Verify we can read attributes
    Long count = (Long) server.getAttribute(name, "TotalInterceptCount");
    assertEquals(0L, count.longValue());
  }

  @Test
  void register_idempotent() {
    assertDoesNotThrow(
        () -> {
          monitor.register();
          monitor.register(); // second call is no-op
        });
  }

  @Test
  void unregister_removesMBean() throws Exception {
    monitor.register();
    monitor.unregister();

    MBeanServer server = ManagementFactory.getPlatformMBeanServer();
    ObjectName name = new ObjectName("com.github.cc11001100.weavergirl:type=Agent");
    assertFalse(server.isRegistered(name));
  }

  @Test
  void setNullValues_handledGracefully() {
    assertDoesNotThrow(
        () -> {
          monitor.setAgentVersion(null);
          monitor.setPluginNames(null);
        });
    assertEquals("unknown", monitor.getAgentVersion());
    assertEquals("", monitor.getPluginNames());
  }
}
