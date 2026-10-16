package com.github.cc11001100.weavergirl.runtime;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.github.cc11001100.weavergirl.api.plugin.PluginContext;
import com.github.cc11001100.weavergirl.api.registry.InterceptorRegistry;
import java.util.Collections;
import java.util.HashMap;
import java.util.Map;
import org.junit.jupiter.api.Test;

/** The checker starts only when an endpoint is configured, and stops again on destroy. */
class UpdateCheckPluginTest {

  @Test
  void name_isUpdateCheck() {
    assertEquals("update-check", new UpdateCheckPlugin().name());
  }

  @Test
  void disabledWithoutEndpoint() {
    UpdateCheckPlugin plugin = new UpdateCheckPlugin();
    assertFalse(plugin.isEnabled(context(Collections.emptyMap())));
  }

  @Test
  void enabledWhenEndpointSet() {
    Map<String, String> config = new HashMap<>();
    config.put("updateCheckEndpoint", "http://127.0.0.1:1/version.json");
    assertTrue(new UpdateCheckPlugin().isEnabled(context(config)));
  }

  @Test
  void initWithoutEndpoint_doesNotThrow() {
    UpdateCheckPlugin plugin = new UpdateCheckPlugin();
    assertDoesNotThrow(() -> plugin.init(context(Collections.emptyMap())));
    assertDoesNotThrow(plugin::destroy);
  }

  @Test
  void initWithEndpoint_startsAndStops() {
    Map<String, String> config = new HashMap<>();
    // Nothing is listening there, which is the point: the checker must tolerate a dead endpoint.
    config.put("updateCheckEndpoint", "http://127.0.0.1:1/version.json");
    config.put("updateCheckIntervalMs", "not-a-number");

    UpdateCheckPlugin plugin = new UpdateCheckPlugin();
    plugin.init(context(config));
    assertDoesNotThrow(plugin::destroy);
    // destroy() is idempotent: a second call has no checker left to stop.
    assertDoesNotThrow(plugin::destroy);
  }

  private static PluginContext context(Map<String, String> config) {
    return new PluginContext() {
      @Override
      public InterceptorRegistry getRegistry() {
        return null;
      }

      @Override
      public String getConfig(String key) {
        return config.get(key);
      }

      @Override
      public String getConfig(String key, String defaultValue) {
        return config.getOrDefault(key, defaultValue);
      }

      @Override
      public Map<String, String> getAllConfig() {
        return config;
      }

      @Override
      public String getPluginName() {
        return "update-check";
      }

      @Override
      public boolean getConfigBoolean(String key, boolean defaultValue) {
        String value = config.get(key);
        return value == null ? defaultValue : Boolean.parseBoolean(value);
      }
    };
  }
}
