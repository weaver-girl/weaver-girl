package com.github.cc11001100.weavergirl.runtime;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.github.cc11001100.weavergirl.api.plugin.PluginContext;
import java.lang.management.ManagementFactory;
import java.util.Collections;
import java.util.HashMap;
import java.util.Map;
import javax.management.ObjectName;
import org.junit.jupiter.api.Test;

/** The jmx capability publishes the agent MBean unless {@code jmx=false}. */
class JmxPluginTest {

  private static final ObjectName AGENT =
      objectName("com.github.cc11001100.weavergirl:type=Agent");

  @Test
  void enabledByDefault() {
    assertTrue(new JmxPlugin().isEnabled(context(Collections.emptyMap())));
  }

  @Test
  void disabledWhenSwitchedOff() {
    JmxPlugin plugin = new JmxPlugin();
    PluginContext ctx = context(config("jmx", "false"));

    assertFalse(plugin.isEnabled(ctx));
    // init() runs before isEnabled(), so switching it off must not publish the MBean.
    plugin.init(ctx);
    plugin.destroy();
  }

  @Test
  void registersAndRemovesTheMBean() throws Exception {
    JmxPlugin plugin = new JmxPlugin();
    plugin.init(context(Collections.emptyMap()));
    try {
      assertTrue(ManagementFactory.getPlatformMBeanServer().isRegistered(AGENT));
    } finally {
      plugin.destroy();
    }
    assertFalse(ManagementFactory.getPlatformMBeanServer().isRegistered(AGENT));
  }

  private static ObjectName objectName(String name) {
    try {
      return new ObjectName(name);
    } catch (Exception e) {
      throw new IllegalStateException(e);
    }
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
        "jmx");
  }
}
