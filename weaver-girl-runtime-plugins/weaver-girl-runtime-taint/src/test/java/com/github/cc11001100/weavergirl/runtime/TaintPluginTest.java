package com.github.cc11001100.weavergirl.runtime;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.github.cc11001100.weavergirl.api.plugin.PluginContext;
import com.github.cc11001100.weavergirl.core.AgentInstrumentation;
import java.util.Collections;
import java.util.HashMap;
import java.util.Map;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

/** The taint capability instruments JDK classes only when {@code taint=true}. */
class TaintPluginTest {

  @AfterEach
  void clearInstrumentation() {
    AgentInstrumentation.set(null);
  }

  @Test
  void disabledByDefault() {
    assertFalse(new TaintPlugin().isEnabled(context(Collections.emptyMap())));
  }

  @Test
  void enabledWhenSwitchedOn() {
    assertTrue(new TaintPlugin().isEnabled(context(config("taint", "true"))));
  }

  @Test
  void initWithoutInstrumentationDoesNotThrow() {
    // taint=true but the agent published nothing. The plugin warns and installs nothing.
    TaintPlugin plugin = new TaintPlugin();
    PluginContext ctx = context(config("taint", "true"));

    plugin.init(ctx);
    plugin.destroy();
  }

  @Test
  void initWhenDisabledDoesNothing() {
    TaintPlugin plugin = new TaintPlugin();
    plugin.init(context(Collections.emptyMap()));
    plugin.destroy();
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
        "taint");
  }
}
