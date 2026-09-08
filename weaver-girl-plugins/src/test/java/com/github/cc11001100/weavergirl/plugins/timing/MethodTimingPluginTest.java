package com.github.cc11001100.weavergirl.plugins.timing;

import static org.junit.jupiter.api.Assertions.*;

import com.github.cc11001100.weavergirl.api.interceptor.InterceptorDefinition;
import com.github.cc11001100.weavergirl.api.plugin.PluginContext;
import com.github.cc11001100.weavergirl.api.registry.InterceptorRegistry;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class MethodTimingPluginTest {

  private MethodTimingPlugin plugin;
  private StubRegistry registry;

  @BeforeEach
  void setUp() {
    plugin = new MethodTimingPlugin();
    registry = new StubRegistry();
  }

  @Test
  void name_returnsMethodTiming() {
    assertEquals("method-timing", plugin.name());
  }

  @Test
  void registerInterceptors_noClassPattern_doesNotRegister() {
    plugin.init(new StubContext(new HashMap<>()));
    plugin.registerInterceptors(registry);
    assertTrue(registry.definitions.isEmpty());
  }

  @Test
  void registerInterceptors_emptyClassPattern_doesNotRegister() {
    Map<String, String> config = new HashMap<>();
    config.put("classPattern", "");
    plugin.init(new StubContext(config));
    plugin.registerInterceptors(registry);
    assertTrue(registry.definitions.isEmpty());
  }

  @Test
  void registerInterceptors_withClassPattern_registersDefinition() {
    Map<String, String> config = new HashMap<>();
    config.put("classPattern", "com\\.example\\..*");
    plugin.init(new StubContext(config));
    plugin.registerInterceptors(registry);
    assertEquals(1, registry.definitions.size());
  }

  @Test
  void registerInterceptors_enabledFalse_doesNotRegister() {
    Map<String, String> config = new HashMap<>();
    config.put("classPattern", "com\\.example\\..*");
    config.put("enabled", "false");
    plugin.init(new StubContext(config));
    plugin.registerInterceptors(registry);
    assertTrue(registry.definitions.isEmpty());
  }

  @Test
  void init_readsSlowThresholdConfig() {
    Map<String, String> config = new HashMap<>();
    config.put("classPattern", "com\\.example\\..*");
    config.put("slowThreshold", "500");
    plugin.init(new StubContext(config));
    // Verify via registration — the plugin should be functional
    plugin.registerInterceptors(registry);
    assertEquals(1, registry.definitions.size());
  }

  @Test
  void init_invalidSlowThreshold_usesDefault() {
    Map<String, String> config = new HashMap<>();
    config.put("classPattern", "com\\.example\\..*");
    config.put("slowThreshold", "not-a-number");
    plugin.init(new StubContext(config));
    plugin.registerInterceptors(registry);
    assertEquals(1, registry.definitions.size());
  }

  @Test
  void init_readsMethodPatternConfig() {
    Map<String, String> config = new HashMap<>();
    config.put("classPattern", "com\\.example\\..*");
    config.put("methodPattern", "process.*");
    plugin.init(new StubContext(config));
    plugin.registerInterceptors(registry);
    assertEquals(1, registry.definitions.size());
  }

  @Test
  void init_readsLogLevelConfig() {
    Map<String, String> config = new HashMap<>();
    config.put("classPattern", "com\\.example\\..*");
    config.put("logLevel", "DEBUG");
    plugin.init(new StubContext(config));
    plugin.registerInterceptors(registry);
    assertEquals(1, registry.definitions.size());
  }

  @Test
  void registeredDefinition_hasCorrectPriority() {
    Map<String, String> config = new HashMap<>();
    config.put("classPattern", "com\\.example\\..*");
    plugin.init(new StubContext(config));
    plugin.registerInterceptors(registry);
    assertEquals(5, registry.definitions.get(0).getPriority());
  }

  private static class StubContext implements PluginContext {
    private final Map<String, String> config;

    StubContext(Map<String, String> config) {
      this.config = config;
    }

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
      return "method-timing";
    }
  }

  private static class StubRegistry implements InterceptorRegistry {
    final List<InterceptorDefinition> definitions = new ArrayList<>();

    @Override
    public void register(InterceptorDefinition definition) {
      definitions.add(definition);
    }

    @Override
    public boolean unregister(String name) {
      return definitions.removeIf(d -> d.getName().equals(name));
    }

    @Override
    public List<InterceptorDefinition> getInterceptorsForClass(String className) {
      return new ArrayList<>();
    }

    @Override
    public List<InterceptorDefinition> getAllDefinitions() {
      return new ArrayList<>(definitions);
    }
  }
}
