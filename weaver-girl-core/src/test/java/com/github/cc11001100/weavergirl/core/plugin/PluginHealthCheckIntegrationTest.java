package com.github.cc11001100.weavergirl.core.plugin;

import static org.junit.jupiter.api.Assertions.*;

import com.github.cc11001100.weavergirl.api.plugin.PluginHealth;
import com.github.cc11001100.weavergirl.api.plugin.PluginHealthRegistry;
import com.github.cc11001100.weavergirl.api.plugin.PluginPerformanceTracker;
import com.github.cc11001100.weavergirl.api.plugin.WeaverPlugin;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * Integration test for plugin health check mechanism.
 */
class PluginHealthCheckIntegrationTest {

  private static final String TEST_PLUGIN = "test-plugin";

  @BeforeEach
  void setUp() {
    PluginPerformanceTracker.clear();
    PluginHealthRegistry.clear();
  }

  @AfterEach
  void tearDown() {
    PluginPerformanceTracker.clear();
    PluginHealthRegistry.clear();
  }

  @Test
  void testHealthyPlugin() {
    WeaverPlugin plugin = new WeaverPlugin() {
      @Override
      public String name() {
        return TEST_PLUGIN;
      }
    };

    // Record 100 invocations with low error rate (2%)
    for (int i = 0; i < 98; i++) {
      PluginPerformanceTracker.recordInvocation(plugin, 10, false);
    }
    for (int i = 0; i < 2; i++) {
      PluginPerformanceTracker.recordInvocation(plugin, 10, true);
    }

    PluginPerformanceTracker.PluginMetricsSnapshot metrics =
        PluginPerformanceTracker.getMetrics(TEST_PLUGIN);
    assertEquals(100, metrics.getInvocationCount());
    assertEquals(0.02, metrics.getErrorRate(), 0.001);
  }

  @Test
  void testDegradedPluginByErrorRate() {
    WeaverPlugin plugin = new WeaverPlugin() {
      @Override
      public String name() {
        return TEST_PLUGIN;
      }
    };

    // Record 100 invocations with high error rate (10%)
    for (int i = 0; i < 90; i++) {
      PluginPerformanceTracker.recordInvocation(plugin, 10, false);
    }
    for (int i = 0; i < 10; i++) {
      PluginPerformanceTracker.recordInvocation(plugin, 10, true);
    }

    PluginPerformanceTracker.PluginMetricsSnapshot metrics =
        PluginPerformanceTracker.getMetrics(TEST_PLUGIN);
    assertEquals(0.1, metrics.getErrorRate(), 0.001);
  }

  @Test
  void testUnhealthyPluginByErrorRate() {
    WeaverPlugin plugin = new WeaverPlugin() {
      @Override
      public String name() {
        return TEST_PLUGIN;
      }
    };

    // Record 100 invocations with very high error rate (25%)
    for (int i = 0; i < 75; i++) {
      PluginPerformanceTracker.recordInvocation(plugin, 10, false);
    }
    for (int i = 0; i < 25; i++) {
      PluginPerformanceTracker.recordInvocation(plugin, 10, true);
    }

    PluginPerformanceTracker.PluginMetricsSnapshot metrics =
        PluginPerformanceTracker.getMetrics(TEST_PLUGIN);
    assertEquals(0.25, metrics.getErrorRate(), 0.001);
  }

  @Test
  void testDegradedPluginByDuration() {
    WeaverPlugin plugin = new WeaverPlugin() {
      @Override
      public String name() {
        return TEST_PLUGIN;
      }
    };

    // Record invocations with high average duration
    for (int i = 0; i < 50; i++) {
      PluginPerformanceTracker.recordInvocation(plugin, 150, false);
    }

    PluginPerformanceTracker.PluginMetricsSnapshot metrics =
        PluginPerformanceTracker.getMetrics(TEST_PLUGIN);
    assertEquals(150.0, metrics.getAvgDurationMs(), 0.01);
  }

  @Test
  void testHealthRegistryIntegration() {
    // Simulate plugin reporting its own health
    PluginHealthRegistry.reportHealthy(TEST_PLUGIN);
    PluginHealth health = PluginHealthRegistry.getHealth(TEST_PLUGIN);
    assertNotNull(health);
    assertTrue(health.isHealthy());

    PluginHealthRegistry.reportDegraded(TEST_PLUGIN, "slow queries");
    health = PluginHealthRegistry.getHealth(TEST_PLUGIN);
    assertNotNull(health);
    assertFalse(health.isHealthy());
    assertEquals(PluginHealth.Status.DEGRADED, health.getStatus());

    PluginHealthRegistry.reportUnhealthy(TEST_PLUGIN, "connection failed");
    health = PluginHealthRegistry.getHealth(TEST_PLUGIN);
    assertNotNull(health);
    assertFalse(health.isHealthy());
    assertEquals(PluginHealth.Status.UNHEALTHY, health.getStatus());
  }

  @Test
  void testPerformanceMetricsWithPluginName() {
    // Test recording by plugin name directly (as InterceptorHolder does)
    for (int i = 0; i < 50; i++) {
      PluginPerformanceTracker.recordInvocationByName(TEST_PLUGIN, 20, false);
    }
    for (int i = 0; i < 5; i++) {
      PluginPerformanceTracker.recordInvocationByName(TEST_PLUGIN, 30, true);
    }

    PluginPerformanceTracker.PluginMetricsSnapshot metrics =
        PluginPerformanceTracker.getMetrics(TEST_PLUGIN);
    assertNotNull(metrics);
    assertEquals(55, metrics.getInvocationCount());
    assertEquals(5, metrics.getErrorCount());
    assertEquals(21.0, metrics.getAvgDurationMs(), 0.1);
  }
}
