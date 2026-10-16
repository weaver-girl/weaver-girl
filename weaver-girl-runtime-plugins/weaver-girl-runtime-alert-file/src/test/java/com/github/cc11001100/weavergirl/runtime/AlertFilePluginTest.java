package com.github.cc11001100.weavergirl.runtime;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.github.cc11001100.weavergirl.api.alert.AlertEngine;
import com.github.cc11001100.weavergirl.api.alert.AlertRule;
import com.github.cc11001100.weavergirl.api.plugin.PluginContext;
import com.github.cc11001100.weavergirl.api.registry.InterceptorRegistry;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Collections;
import java.util.HashMap;
import java.util.Map;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** Alerts land in the file only while the plugin is alive, and only when a path is configured. */
class AlertFilePluginTest {

  @TempDir Path tempDir;

  @AfterEach
  void noRulesLeak() {
    AlertEngine.clear();
  }

  @Test
  void name_isAlertFile() {
    assertEquals("alert-file", new AlertFilePlugin().name());
  }

  @Test
  void disabledWithoutPath() {
    assertFalse(new AlertFilePlugin().isEnabled(context(Collections.emptyMap())));
  }

  @Test
  void disabledWhenPathBlank() {
    assertFalse(new AlertFilePlugin().isEnabled(context(config("alertFile", "  "))));
  }

  @Test
  void alertIsAppendedAndStopsAfterDestroy() throws Exception {
    Path file = tempDir.resolve("alerts.log");
    AlertFilePlugin plugin = new AlertFilePlugin();
    plugin.init(context(config("alertFile", file.toString())));

    AlertEngine.addRule(
        AlertRule.builder()
            .name("slow-calls")
            .metric("calls")
            .operator("gt")
            .threshold(10)
            .severity("WARN")
            .message("too many\ncalls")
            .build());
    AlertEngine.evaluate("calls", 25);

    String written = new String(Files.readAllBytes(file), java.nio.charset.StandardCharsets.UTF_8);
    assertTrue(written.contains("WARN"), written);
    assertTrue(written.contains("slow-calls"), written);
    assertTrue(written.contains("25.0"), written);
    assertFalse(written.contains("\ncalls"), "a line break in the message split the record: " + written);

    plugin.destroy();
    long length = Files.size(file);
    AlertEngine.evaluate("calls", 30);
    assertEquals(length, Files.size(file), "an alert was written after the plugin stopped");
  }

  @Test
  void initWithoutPath_doesNothing() {
    AlertFilePlugin plugin = new AlertFilePlugin();
    plugin.init(context(Collections.emptyMap()));
    plugin.destroy();
  }

  private static Map<String, String> config(String key, String value) {
    Map<String, String> config = new HashMap<>();
    config.put(key, value);
    return config;
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
        return "alert-file";
      }
    };
  }
}
