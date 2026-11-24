package com.github.cc11001100.weavergirl.core.status;

import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;

import com.github.cc11001100.weavergirl.api.config.PerformanceSwitches;
import com.github.cc11001100.weavergirl.api.plugin.PluginPerformanceTracker;
import com.github.cc11001100.weavergirl.core.InterceptorHolder;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * {@code -Dweavergirl.pluginMetrics.enabled=false} must stop the plugin-level mirror. The
 * interceptor definition name is {@code pluginName-classPattern-methodPattern}, so the mirror keys
 * on {@code pluginName}.
 */
class PluginMetricsSwitchTest {

  private static final String HOOK = "bench-plugin-com.example.Foo.handle";

  @BeforeEach
  void setUp() {
    PluginPerformanceTracker.clear();
    PerformanceSwitches.setEnabled(PerformanceSwitches.PLUGIN_METRICS, true);
  }

  @AfterEach
  void tearDown() {
    PerformanceSwitches.setEnabled(PerformanceSwitches.PLUGIN_METRICS, true);
    PluginPerformanceTracker.clear();
    InterceptorHolder.setRegistry(null);
  }

  @Test
  void pluginMirrorIsWrittenWhenEnabled() {
    InterceptorHolder.recordOutcome(HOOK, true, 1_000L);
    assertNotNull(
        PluginPerformanceTracker.getMetrics("bench-plugin"),
        "the plugin mirror should record the callback when enabled");
  }

  @Test
  void pluginMirrorIsSkippedWhenDisabled() {
    PerformanceSwitches.setEnabled(PerformanceSwitches.PLUGIN_METRICS, false);

    InterceptorHolder.recordOutcome(HOOK, true, 1_000L);

    assertNull(
        PluginPerformanceTracker.getMetrics("bench-plugin"),
        "pluginMetrics=false must not write the plugin mirror");
  }
}
