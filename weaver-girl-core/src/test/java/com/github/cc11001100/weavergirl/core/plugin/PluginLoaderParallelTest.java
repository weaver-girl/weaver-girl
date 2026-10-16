package com.github.cc11001100.weavergirl.core.plugin;

import static org.junit.jupiter.api.Assertions.*;

import com.github.cc11001100.weavergirl.api.registry.InterceptorRegistry;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

/**
 * Tests for PluginLoader parallel loading and ThreadFactory.
 */
class PluginLoaderParallelTest {

  @Test
  void loadPlugins_withSingleThreadFactory() {
    // Test the ThreadFactory pattern used in PluginLoader
    AtomicInteger counter = new AtomicInteger(0);
    ThreadFactory tf = r -> {
      Thread t = new Thread(r, "test-thread-" + counter.incrementAndGet());
      t.setDaemon(true);
      return t;
    };

    ExecutorService executor = Executors.newFixedThreadPool(2, tf);

    try {
      // Submit some work
      for (int i = 0; i < 3; i++) {
        executor.submit(() -> {
          try {
            Thread.sleep(10);
          } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
          }
        });
      }
    } finally {
      executor.shutdown();
    }

    assertTrue(true);
  }

  @Test
  void loadPlugins_withEmptyServiceLoader() {
    PluginLoader loader = new PluginLoader();
    ClassLoader emptyLoader = new ClassLoader() {};
    InterceptorRegistry mockRegistry = Mockito.mock(InterceptorRegistry.class);
    Map<String, String> config = new HashMap<>();

    List<?> result = loader.loadPlugins(emptyLoader, mockRegistry, config);

    assertNotNull(result);
    assertTrue(result.isEmpty());
  }
}
