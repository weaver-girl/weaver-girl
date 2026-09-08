package com.github.cc11001100.weavergirl.api.plugin;

import static org.junit.jupiter.api.Assertions.*;

import java.util.*;
import org.junit.jupiter.api.*;

/** Tests for plugin health monitoring (P64). */
class PluginHealthTest {

  @AfterEach
  void tearDown() {
    PluginHealthRegistry.clear();
  }

  @Test
  void pluginHealth_healthy() {
    PluginHealth h = PluginHealth.healthy("test");
    assertEquals("test", h.getPluginName());
    assertTrue(h.isHealthy());
    assertEquals(PluginHealth.Status.HEALTHY, h.getStatus());
  }

  @Test
  void pluginHealth_degraded() {
    PluginHealth h = PluginHealth.degraded("test", "slow response");
    assertFalse(h.isHealthy());
    assertEquals(PluginHealth.Status.DEGRADED, h.getStatus());
    assertEquals("slow response", h.getMessage());
  }

  @Test
  void pluginHealth_unhealthy() {
    PluginHealth h = PluginHealth.unhealthy("test", "crashed");
    assertFalse(h.isHealthy());
    assertEquals(PluginHealth.Status.UNHEALTHY, h.getStatus());
  }

  @Test
  void pluginHealth_toString() {
    PluginHealth h = PluginHealth.degraded("svc", "high latency");
    assertTrue(h.toString().contains("svc"));
    assertTrue(h.toString().contains("DEGRADED"));
  }

  @Test
  void registry_reportAndGet() {
    PluginHealthRegistry.reportHealthy("plugin-a");
    PluginHealthRegistry.reportDegraded("plugin-b", "slow");

    assertTrue(PluginHealthRegistry.getHealth("plugin-a").isHealthy());
    assertFalse(PluginHealthRegistry.getHealth("plugin-b").isHealthy());
  }

  @Test
  void registry_allHealthy() {
    PluginHealthRegistry.reportHealthy("a");
    PluginHealthRegistry.reportHealthy("b");
    assertTrue(PluginHealthRegistry.allHealthy());

    PluginHealthRegistry.reportUnhealthy("c", "down");
    assertFalse(PluginHealthRegistry.allHealthy());
  }

  @Test
  void registry_getUnhealthyPlugins() {
    PluginHealthRegistry.reportHealthy("a");
    PluginHealthRegistry.reportUnhealthy("b", "err");
    PluginHealthRegistry.reportDegraded("c", "slow");

    List<String> unhealthy = PluginHealthRegistry.getUnhealthyPlugins();
    assertEquals(2, unhealthy.size());
    assertTrue(unhealthy.contains("b"));
    assertTrue(unhealthy.contains("c"));
  }

  @Test
  void registry_getAllHealth() {
    PluginHealthRegistry.reportHealthy("a");
    assertEquals(1, PluginHealthRegistry.getAllHealth().size());
  }

  @Test
  void registry_clear() {
    PluginHealthRegistry.reportHealthy("a");
    PluginHealthRegistry.clear();
    assertEquals(0, PluginHealthRegistry.getAllHealth().size());
  }

  @Test
  void registry_getNonexistent() {
    assertNull(PluginHealthRegistry.getHealth("nonexistent"));
  }
}
