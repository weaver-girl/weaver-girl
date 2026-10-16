package com.github.cc11001100.weavergirl.runtime;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.github.cc11001100.weavergirl.api.plugin.PluginContext;
import com.github.cc11001100.weavergirl.core.InterceptorHolder;
import java.util.Collections;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

/** The dynamic-refresh capability schedules a refresh only when the interval is a positive number. */
class DynamicRefreshPluginTest {

  @AfterEach
  void clearHook() {
    InterceptorHolder.setRetransformHook(null);
  }

  @Test
  void disabledByDefault() {
    assertFalse(new DynamicRefreshPlugin().isEnabled(context(Collections.emptyMap())));
  }

  @Test
  void disabledWhenIntervalNotPositive() {
    assertFalse(new DynamicRefreshPlugin().isEnabled(context(config("refreshIntervalSeconds", "0"))));
    assertFalse(new DynamicRefreshPlugin().isEnabled(context(config("refreshIntervalSeconds", "-5"))));
  }

  @Test
  void disabledWhenIntervalNotANumber() {
    DynamicRefreshPlugin plugin = new DynamicRefreshPlugin();
    PluginContext ctx = context(config("refreshIntervalSeconds", "soon"));

    assertFalse(plugin.isEnabled(ctx));
    // init() runs before isEnabled(), so a bad value must not throw or start anything.
    plugin.init(ctx);
    plugin.destroy();
  }

  @Test
  void enabledWhenIntervalPositive() {
    DynamicRefreshPlugin plugin = new DynamicRefreshPlugin();
    PluginContext ctx = context(config("refreshIntervalSeconds", "3600"));

    assertTrue(plugin.isEnabled(ctx));
    plugin.init(ctx);
    plugin.destroy();
    plugin.destroy();
  }

  @Test
  void refreshDropsTheSnapshotAndRetransforms() {
    AtomicInteger retransformed = new AtomicInteger();
    InterceptorHolder.setRetransformHook(retransformed::incrementAndGet);

    new DynamicRefreshPlugin.RefreshTask().run();

    assertEquals(1, retransformed.get());
  }

  @Test
  void refreshWithoutAHookDoesNotThrow() {
    new DynamicRefreshPlugin.RefreshTask().run();
  }

  private static Map<String, String> config(String key, String value) {
    Map<String, String> config = new HashMap<String, String>();
    config.put(key, value);
    return config;
  }

  private static PluginContext context(Map<String, String> config) {
    return new com.github.cc11001100.weavergirl.core.plugin.DefaultPluginContext(
        new com.github.cc11001100.weavergirl.core.registry.DefaultInterceptorRegistry(),
        config,
        "dynamic-refresh");
  }
}
