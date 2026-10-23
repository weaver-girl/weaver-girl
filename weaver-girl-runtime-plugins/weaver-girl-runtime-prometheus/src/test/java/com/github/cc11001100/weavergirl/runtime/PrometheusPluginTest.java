package com.github.cc11001100.weavergirl.runtime;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.github.cc11001100.weavergirl.api.event.InterceptorEventPublisher;
import com.github.cc11001100.weavergirl.api.plugin.PluginContext;
import java.util.Collections;
import java.util.HashMap;
import java.util.Map;
import org.junit.jupiter.api.Test;

/** The prometheus capability switches on only when {@code metricsPort} is set. */
class PrometheusPluginTest {

  @Test
  void disabledWithoutPort() {
    assertFalse(new PrometheusPlugin().isEnabled(context(Collections.emptyMap())));
  }

  @Test
  void enabledWhenPortSet() {
    assertTrue(new PrometheusPlugin().isEnabled(context(config("metricsPort", "0"))));
  }

  @Test
  void invalidPortDoesNotThrow() {
    PrometheusPlugin plugin = new PrometheusPlugin();
    PluginContext context = context(config("metricsPort", "not-a-port"));

    assertTrue(plugin.isEnabled(context));
    InterceptorEventPublisher publisher = InterceptorEventPublisher.getInstance();
    int before = publisher.getListenerCount();
    plugin.init(context);
    plugin.destroy();
    // An invalid port bails out before subscribing, so the global publisher is untouched.
    assertEquals(before, publisher.getListenerCount());
  }

  @Test
  void destroyUnsubscribesTheExporter() {
    PrometheusPlugin plugin = new PrometheusPlugin();
    PluginContext context = context(config("metricsPort", "0"));
    InterceptorEventPublisher publisher = InterceptorEventPublisher.getInstance();

    int before = publisher.getListenerCount();
    plugin.init(context);
    assertEquals(before + 1, publisher.getListenerCount());

    plugin.destroy();
    assertEquals(
        before,
        publisher.getListenerCount(),
        "a reloaded plugin must not leave its old exporter subscribed");
  }

  private static Map<String, String> config(String key, String value) {
    Map<String, String> config = new HashMap<>();
    config.put(key, value);
    return config;
  }

  /**
   * The plugin reads config and nothing else, so the test context only answers config. Built here
   * rather than from {@code DefaultPluginContext}, which would pull the whole engine onto the test
   * classpath of a plugin that no longer depends on it.
   */
  private static PluginContext context(Map<String, String> config) {
    return new MapPluginContext(config);
  }

  private static final class MapPluginContext implements PluginContext {

    private final Map<String, String> config;

    MapPluginContext(Map<String, String> config) {
      this.config = config;
    }

    @Override
    public String getConfig(String key) {
      return config.get(key);
    }

    @Override
    public String getConfig(String key, String defaultValue) {
      String value = config.get(key);
      return value != null ? value : defaultValue;
    }

    @Override
    public Map<String, String> getAllConfig() {
      return Collections.unmodifiableMap(config);
    }

    @Override
    public com.github.cc11001100.weavergirl.api.registry.InterceptorRegistry getRegistry() {
      throw new UnsupportedOperationException("prometheus does not touch the registry");
    }

    @Override
    public String getPluginName() {
      return PrometheusPlugin.NAME;
    }
  }
}
