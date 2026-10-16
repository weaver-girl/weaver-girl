package com.github.cc11001100.weavergirl.core.plugin;

import static org.junit.jupiter.api.Assertions.*;

import com.github.cc11001100.weavergirl.api.registry.InterceptorRegistry;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

/**
 * Tests for PluginLoader ThreadFactory.
 */
class PluginLoaderThreadFactoryTest {

  @Test
  void loadPlugins_withEmptyClassLoader_returnsEmptyList() {
    PluginLoader loader = new PluginLoader();
    ClassLoader emptyLoader = new ClassLoader() {};

    InterceptorRegistry mockRegistry = Mockito.mock(InterceptorRegistry.class);
    Map<String, String> config = new HashMap<>();

    List<?> result = loader.loadPlugins(emptyLoader, mockRegistry, config);

    assertNotNull(result);
    assertTrue(result.isEmpty());
  }

  @Test
  void loadPlugins_withNullConfig_usesEmptyConfig() {
    PluginLoader loader = new PluginLoader();
    ClassLoader emptyLoader = new ClassLoader() {};

    InterceptorRegistry mockRegistry = Mockito.mock(InterceptorRegistry.class);

    // A null config means "no selection": every discovered plugin loads.
    List<?> result = loader.loadPlugins(emptyLoader, mockRegistry, null);
    assertNotNull(result);
    assertTrue(result.isEmpty());
  }
}
