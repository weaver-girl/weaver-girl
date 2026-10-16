package com.github.cc11001100.weavergirl.runtime;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.github.cc11001100.weavergirl.api.event.InterceptorEventPublisher;
import com.github.cc11001100.weavergirl.api.plugin.PluginContext;
import com.github.cc11001100.weavergirl.api.registry.InterceptorRegistry;
import java.util.Collections;
import java.util.HashMap;
import java.util.Map;
import org.junit.jupiter.api.Test;

/** The json-events capability switches on only when {@code jsonEvents=true}. */
class JsonEventsPluginTest {

  @Test
  void disabledByDefault() {
    assertFalse(new JsonEventsPlugin().isEnabled(context(Collections.emptyMap())));
  }

  @Test
  void enabledWhenJsonEventsTrue() {
    JsonEventsPlugin plugin = new JsonEventsPlugin();
    PluginContext context = context(config("jsonEvents", "true"));

    assertTrue(plugin.isEnabled(context));

    // init only subscribes a listener. Count before and after so the assertion does not depend on
    // whatever else the suite already registered on the global publisher.
    InterceptorEventPublisher publisher = InterceptorEventPublisher.getInstance();
    int before = publisher.getListenerCount();
    plugin.init(context);
    assertEquals(before + 1, publisher.getListenerCount());
    publisher.removeListener(publisher.getListeners().get(publisher.getListenerCount() - 1));
  }

  @Test
  void disabledWhenJsonEventsFalse() {
    assertFalse(new JsonEventsPlugin().isEnabled(context(config("jsonEvents", "false"))));
  }

  private static Map<String, String> config(String key, String value) {
    Map<String, String> config = new HashMap<>();
    config.put(key, value);
    return config;
  }

  private static PluginContext context(Map<String, String> config) {
    return new com.github.cc11001100.weavergirl.core.plugin.DefaultPluginContext(
        registry(), config, JsonEventsPlugin.NAME);
  }

  private static InterceptorRegistry registry() {
    return new com.github.cc11001100.weavergirl.core.registry.DefaultInterceptorRegistry();
  }
}
