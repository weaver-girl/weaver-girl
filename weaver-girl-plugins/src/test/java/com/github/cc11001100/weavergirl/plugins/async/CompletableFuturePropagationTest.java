package com.github.cc11001100.weavergirl.plugins.async;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.github.cc11001100.weavergirl.api.context.ContextCompletableFuture;
import com.github.cc11001100.weavergirl.api.context.ThreadContext;
import com.github.cc11001100.weavergirl.api.interceptor.InterceptorDefinition;
import com.github.cc11001100.weavergirl.api.plugin.PluginContext;
import com.github.cc11001100.weavergirl.api.registry.InterceptorRegistry;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * Tests for {@link CompletableFuturePropagationPlugin} and the {@link ContextCompletableFuture} API
 * it documents.
 *
 * <p>The plugin itself is a capability placeholder: bytecode weaving of {@code CompletableFuture}
 * factory methods is not supported while {@code ARGUMENT_REWRITE} advice is skipped for bootstrap
 * classes, so the plugin registers no interceptor definitions. Propagation is provided by the
 * {@code ContextCompletableFuture} API, verified end-to-end below.
 */
class CompletableFuturePropagationTest {

  private CompletableFuturePropagationPlugin plugin;
  private StubRegistry registry;

  @BeforeEach
  void setUp() {
    plugin = new CompletableFuturePropagationPlugin();
    registry = new StubRegistry();
    ThreadContext.clear();
  }

  @AfterEach
  void tearDown() {
    ThreadContext.clear();
  }

  @Test
  void name_returnsCompletableFutureContextPropagation() {
    assertEquals("completable-future-context-propagation", plugin.name());
  }

  @Test
  void registerInterceptors_enabled_registersNoDefinitions() {
    plugin.init(new StubContext(new HashMap<>()));
    plugin.registerInterceptors(registry);

    assertTrue(
        registry.definitions.isEmpty(),
        "CompletableFuture weaving is unsupported on the bootstrap classloader "
            + "— the plugin must register no definitions");
  }

  @Test
  void registerInterceptors_disabled_registersNothing() {
    Map<String, String> config = new HashMap<>();
    config.put("enabled", "false");
    plugin.init(new StubContext(config));
    plugin.registerInterceptors(registry);

    assertTrue(registry.definitions.isEmpty());
  }

  @Test
  void contextCompletableFuture_supplyAsync_propagatesThreadContext() throws Exception {
    ThreadContext.put("traceId", "cf-trace-1");
    ExecutorService delegate = Executors.newSingleThreadExecutor();
    try {
      String result =
          ContextCompletableFuture.supplyAsync(() -> ThreadContext.<String>get("traceId"), delegate)
              .get(5, TimeUnit.SECONDS);

      assertEquals(
          "cf-trace-1",
          result,
          "ThreadContext should be propagated through supplyAsync with executor");
    } finally {
      delegate.shutdownNow();
    }
  }

  @Test
  void contextCompletableFuture_runAsync_propagatesThreadContext() throws Exception {
    ThreadContext.put("traceId", "cf-trace-2");
    ExecutorService delegate = Executors.newSingleThreadExecutor();
    try {
      List<String> captured = new ArrayList<>();
      ContextCompletableFuture.runAsync(
              () -> {
                synchronized (captured) {
                  captured.add(ThreadContext.<String>get("traceId"));
                }
              },
              delegate)
          .get(5, TimeUnit.SECONDS);

      assertEquals(
          "cf-trace-2",
          captured.get(0),
          "ThreadContext should be propagated through runAsync with executor");
    } finally {
      delegate.shutdownNow();
    }
  }

  // --- Stubs ---

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
      return "completable-future-context-propagation";
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
      return false;
    }

    @Override
    public List<InterceptorDefinition> getAllDefinitions() {
      return definitions;
    }

    @Override
    public List<InterceptorDefinition> getInterceptorsForClass(String className) {
      return definitions;
    }
  }
}
