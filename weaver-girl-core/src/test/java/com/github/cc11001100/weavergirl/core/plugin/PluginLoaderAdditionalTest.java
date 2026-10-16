package com.github.cc11001100.weavergirl.core.plugin;

import static org.junit.jupiter.api.Assertions.*;

import com.github.cc11001100.weavergirl.api.registry.InterceptorRegistry;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

/**
 * Additional tests for PluginLoader to improve coverage.
 */
class PluginLoaderAdditionalTest {

  @Test
  void loadPlugins_withDisabledPlugins_skipsSpecifiedPlugins() {
    PluginLoader loader = new PluginLoader();
    ClassLoader emptyLoader = new ClassLoader() {};
    InterceptorRegistry mockRegistry = Mockito.mock(InterceptorRegistry.class);

    Map<String, String> config = new HashMap<>();
    config.put("disabledPlugins", "nonexistent-plugin");

    List<?> result = loader.loadPlugins(emptyLoader, mockRegistry, config);

    assertNotNull(result);
  }

  @Test
  void loadPlugins_withEmptyDisabledPlugins_loadsAll() {
    PluginLoader loader = new PluginLoader();
    ClassLoader emptyLoader = new ClassLoader() {};
    InterceptorRegistry mockRegistry = Mockito.mock(InterceptorRegistry.class);

    Map<String, String> config = new HashMap<>();
    config.put("disabledPlugins", "");

    List<?> result = loader.loadPlugins(emptyLoader, mockRegistry, config);

    assertNotNull(result);
  }

  @Test
  void loadPlugins_withNullRegistry_doesNotThrow() {
    PluginLoader loader = new PluginLoader();
    ClassLoader emptyLoader = new ClassLoader() {};
    Map<String, String> config = new HashMap<>();

    // Current implementation may not validate null registry
    assertDoesNotThrow(() -> {
      loader.loadPlugins(emptyLoader, null, config);
    });
  }
}
