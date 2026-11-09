package com.github.cc11001100.weavergirl.api.plugin;

import static org.junit.jupiter.api.Assertions.*;

import com.github.cc11001100.weavergirl.api.plugin.WeaverPlugin;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * Tests for PluginPerformanceTracker.
 */
class PluginPerformanceTrackerTest {

  private static final String TEST_PLUGIN_NAME = "test-plugin";

  @BeforeEach
  void setUp() {
    PluginPerformanceTracker.clear();
  }

  @AfterEach
  void tearDown() {
    PluginPerformanceTracker.clear();
  }

  @Test
  void testRecordInvocation() {
    WeaverPlugin plugin = new WeaverPlugin() {
      @Override
      public String name() {
        return TEST_PLUGIN_NAME;
      }
    };

    PluginPerformanceTracker.recordInvocation(plugin, 10, false);
    PluginPerformanceTracker.recordInvocation(plugin, 20, false);
    PluginPerformanceTracker.recordInvocation(plugin, 30, true);

    PluginPerformanceTracker.PluginMetricsSnapshot metrics =
        PluginPerformanceTracker.getMetrics(TEST_PLUGIN_NAME);

    assertNotNull(metrics);
    assertEquals(3, metrics.getInvocationCount());
    assertEquals(1, metrics.getErrorCount());
    assertEquals(20.0, metrics.getAvgDurationMs(), 0.01);
    assertTrue(metrics.getLastInvocationAt() > 0);
    assertTrue(metrics.getLastErrorAt() > 0);
  }

  @Test
  void testErrorRate() {
    WeaverPlugin plugin = new WeaverPlugin() {
      @Override
      public String name() {
        return TEST_PLUGIN_NAME;
      }
    };

    // 10 invocations, 2 errors -> 20% error rate
    for (int i = 0; i < 8; i++) {
      PluginPerformanceTracker.recordInvocation(plugin, 10, false);
    }
    for (int i = 0; i < 2; i++) {
      PluginPerformanceTracker.recordInvocation(plugin, 10, true);
    }

    PluginPerformanceTracker.PluginMetricsSnapshot metrics =
        PluginPerformanceTracker.getMetrics(TEST_PLUGIN_NAME);

    assertEquals(0.2, metrics.getErrorRate(), 0.001);
  }

  @Test
  void testNullPlugin() {
    PluginPerformanceTracker.recordInvocation(null, 10, false);
    PluginPerformanceTracker.recordInvocation(null, 10, true);

    assertTrue(PluginPerformanceTracker.getAllMetrics().isEmpty());
  }

  @Test
  void testGetMetricsForUnknownPlugin() {
    PluginPerformanceTracker.PluginMetricsSnapshot metrics =
        PluginPerformanceTracker.getMetrics("unknown-plugin");
    assertNull(metrics);
  }

  @Test
  void testRemoveMetrics() {
    WeaverPlugin plugin = new WeaverPlugin() {
      @Override
      public String name() {
        return TEST_PLUGIN_NAME;
      }
    };

    PluginPerformanceTracker.recordInvocation(plugin, 10, false);
    assertNotNull(PluginPerformanceTracker.getMetrics(TEST_PLUGIN_NAME));

    PluginPerformanceTracker.removeMetrics(TEST_PLUGIN_NAME);
    assertNull(PluginPerformanceTracker.getMetrics(TEST_PLUGIN_NAME));
  }

  @Test
  void testClear() {
    WeaverPlugin plugin1 = new WeaverPlugin() {
      @Override
      public String name() {
        return "plugin1";
      }
    };
    WeaverPlugin plugin2 = new WeaverPlugin() {
      @Override
      public String name() {
        return "plugin2";
      }
    };

    PluginPerformanceTracker.recordInvocation(plugin1, 10, false);
    PluginPerformanceTracker.recordInvocation(plugin2, 20, false);

    assertEquals(2, PluginPerformanceTracker.getAllMetrics().size());

    PluginPerformanceTracker.clear();
    assertTrue(PluginPerformanceTracker.getAllMetrics().isEmpty());
  }

  @Test
  void testZeroInvocations() {
    WeaverPlugin plugin = new WeaverPlugin() {
      @Override
      public String name() {
        return TEST_PLUGIN_NAME;
      }
    };

    PluginPerformanceTracker.PluginMetricsSnapshot metrics =
        PluginPerformanceTracker.getMetrics(TEST_PLUGIN_NAME);
    assertNull(metrics);
  }
}
