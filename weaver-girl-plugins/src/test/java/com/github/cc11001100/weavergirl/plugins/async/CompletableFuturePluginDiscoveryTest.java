package com.github.cc11001100.weavergirl.plugins.async;

import static org.junit.jupiter.api.Assertions.assertTrue;

import com.github.cc11001100.weavergirl.api.plugin.WeaverPlugin;
import com.github.cc11001100.weavergirl.core.plugin.PluginLoader;
import com.github.cc11001100.weavergirl.core.registry.DefaultInterceptorRegistry;
import java.util.Collections;
import java.util.List;
import java.util.stream.Collectors;
import org.junit.jupiter.api.Test;

/**
 * Integration test for {@link CompletableFuturePropagationPlugin} discovery.
 *
 * <p>Loads all plugins through the real {@link PluginLoader} (ServiceLoader + init +
 * registerInterceptors lifecycle) and verifies that the CompletableFuture placeholder:
 *
 * <ul>
 *   <li>is discovered from {@code META-INF/services};
 *   <li>completes the load lifecycle without error;
 *   <li>contributes zero interceptor definitions, so it can never disturb the bootstrap {@code
 *       CompletableFuture} class at transform time.
 * </ul>
 */
class CompletableFuturePluginDiscoveryTest {

  @Test
  void placeholderPlugin_loadsWithZeroDefinitions() {
    DefaultInterceptorRegistry registry = new DefaultInterceptorRegistry();
    PluginLoader loader = new PluginLoader();

    List<WeaverPlugin> loaded = loader.loadPlugins(registry, Collections.emptyMap());

    List<String> names = loaded.stream().map(WeaverPlugin::name).collect(Collectors.toList());
    assertTrue(
        names.contains("completable-future-context-propagation"),
        "CompletableFuture placeholder plugin should be discovered via ServiceLoader, got: "
            + names);

    List<String> cfDefinitions =
        registry.getAllDefinitions().stream()
            .map(def -> def.getName())
            .filter(defName -> defName.startsWith("cf-"))
            .collect(Collectors.toList());
    assertTrue(
        cfDefinitions.isEmpty(),
        "Placeholder plugin must register no definitions, got: " + cfDefinitions);
  }

  @Test
  void asyncPlugin_stillRegistersExecutorHooks() {
    DefaultInterceptorRegistry registry = new DefaultInterceptorRegistry();
    PluginLoader loader = new PluginLoader();

    List<WeaverPlugin> loaded = loader.loadPlugins(registry, Collections.emptyMap());

    List<String> names = loaded.stream().map(WeaverPlugin::name).collect(Collectors.toList());
    assertTrue(
        names.contains("async-context-propagation"),
        "Async executor plugin should still load alongside the placeholder, got: " + names);
    assertTrue(
        registry.getAllDefinitions().stream().anyMatch(def -> def.getName().startsWith("async-")),
        "Async executor definitions should be registered");
  }
}
