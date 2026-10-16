package com.github.cc11001100.weavergirl.runtime;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.github.cc11001100.weavergirl.api.plugin.PluginContext;
import com.github.cc11001100.weavergirl.api.registry.InterceptorRegistry;
import java.util.Collections;
import java.util.HashMap;
import java.util.Map;
import org.junit.jupiter.api.Test;

/** Config watching needs both {@code watch=true} and a {@code config} path. */
class ConfigWatchPluginTest {

  @Test
  void disabledByDefault() {
    assertFalse(new ConfigWatchPlugin().isEnabled(context(Collections.emptyMap())));
  }

  @Test
  void disabledWhenWatchSetWithoutConfigPath() {
    assertFalse(new ConfigWatchPlugin().isEnabled(context(config("watch", "true"))));
  }

  @Test
  void enabledWhenWatchAndConfigPathSet() {
    Map<String, String> config = config("watch", "true");
    config.put("config", "/tmp/weaver.yml");
    assertTrue(new ConfigWatchPlugin().isEnabled(context(config)));
  }

  @Test
  void missingConfigFileDoesNotThrow() {
    ConfigWatchPlugin plugin = new ConfigWatchPlugin();
    Map<String, String> config = config("watch", "true");
    config.put("config", "/tmp/weaver-girl-does-not-exist.yml");
    PluginContext context = context(config);

    plugin.init(context);
    plugin.destroy();
  }

  private static Map<String, String> config(String key, String value) {
    Map<String, String> config = new HashMap<>();
    config.put(key, value);
    return config;
  }

  private static PluginContext context(Map<String, String> config) {
    return new com.github.cc11001100.weavergirl.core.plugin.DefaultPluginContext(
        registry(), config, ConfigWatchPlugin.NAME);
  }

  private static InterceptorRegistry registry() {
    return new com.github.cc11001100.weavergirl.core.registry.DefaultInterceptorRegistry();
  }
}
