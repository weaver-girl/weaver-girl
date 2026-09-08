package com.github.cc11001100.weavergirl.core.plugin;

import com.github.cc11001100.weavergirl.api.plugin.PluginContext;
import com.github.cc11001100.weavergirl.api.plugin.WeaverPlugin;
import com.github.cc11001100.weavergirl.api.registry.InterceptorRegistry;
import com.github.cc11001100.weavergirl.core.WeaverGirl;
import com.github.cc11001100.weavergirl.core.status.AgentStatus;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.ServiceLoader;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.ThreadFactory;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Loads WeaverPlugin implementations via Java ServiceLoader mechanism. Manages the full plugin
 * lifecycle: init → registerInterceptors → (runtime) → destroy.
 */
public class PluginLoader {

  private static final Logger log = LoggerFactory.getLogger(PluginLoader.class);

  private final List<WeaverPlugin> loadedPlugins = new ArrayList<>();
  private static final int MAX_PARALLEL_PLUGIN_LOAD_THREADS = Math.max(2, Runtime.getRuntime().availableProcessors() / 2);

  /**
   * Load all plugins from the given ClassLoader and register their interceptors. Calls init() then
   * registerInterceptors() on each plugin.
   *
   * <p>Plugins listed in the {@code disabledPlugins} config key (comma-separated) will be skipped.
   * Example: {@code disabledPlugins=servlet,kafka}
   *
   * @param classLoader the ClassLoader to scan for plugin SPI declarations
   * @param registry the interceptor registry
   * @param config agent configuration properties (may be empty)
   * @return the list of successfully loaded plugins
   */
  public List<WeaverPlugin> loadPlugins(
      ClassLoader classLoader, InterceptorRegistry registry, Map<String, String> config) {
    ServiceLoader<WeaverPlugin> serviceLoader = ServiceLoader.load(WeaverPlugin.class, classLoader);

    // Collect all discovered plugins first
    List<WeaverPlugin> discovered = new ArrayList<>();
    for (WeaverPlugin plugin : serviceLoader) {
      discovered.add(plugin);
    }

    // Parse disabled plugins list
    java.util.Set<String> disabledPlugins = new java.util.HashSet<>();
    String disabledStr = config.getOrDefault("disabledPlugins", "");
    if (disabledStr != null && !disabledStr.isEmpty()) {
      for (String name : disabledStr.split(",")) {
        String trimmed = name.trim();
        if (!trimmed.isEmpty()) {
          disabledPlugins.add(trimmed);
        }
      }
    }

    // Filter out disabled plugins
    List<WeaverPlugin> enabled = new ArrayList<>();
    for (WeaverPlugin plugin : discovered) {
      if (disabledPlugins.contains(plugin.name())) {
        log.info("Plugin {} is disabled via config — skipping", plugin.name());
        AgentStatus.getInstance().recordPluginStatus(plugin.name(), false, "disabled via config");
      } else {
        enabled.add(plugin);
      }
    }

    // Resolve dependencies before initializing
    PluginDependencyResolver resolver = new PluginDependencyResolver();
    List<WeaverPlugin> sorted = resolver.resolve(enabled);

    // P99: parallelize plugin initialization when there are enough plugins to justify it.
    // We still respect dependency order by only parallelizing plugins within the same
    // dependency level (plugins that don't depend on each other).
    java.util.Map<String, Long> perPluginMs = new java.util.LinkedHashMap<>();
    if (sorted.size() > 2 && MAX_PARALLEL_PLUGIN_LOAD_THREADS > 1) {
      loadPluginsParallel(sorted, registry, config, perPluginMs);
    } else {
      loadPluginsSequential(sorted, registry, config, perPluginMs);
    }

    // Per-plugin startup breakdown (ms, descending) — surfaces which plugins
    // dominate the pluginLoad phase so a slow one can be targeted for optimization.
    log.info(
        "Plugin load breakdown (ms): {}",
        perPluginMs.entrySet().stream()
            .sorted(java.util.Map.Entry.<String, Long>comparingByValue().reversed())
            .map(e -> e.getKey() + "=" + e.getValue())
            .collect(java.util.stream.Collectors.joining(", ")));

    // Summary report
    int total = sorted.size();
    int succeeded = loadedPlugins.size();
    int failed = total - succeeded;
    log.info(
        "Loaded {} plugins ({} succeeded, {} failed) out of {} discovered",
        succeeded,
        succeeded,
        failed,
        total);
    if (failed > 0) {
      log.warn(
          "Failed plugins: {}",
          sorted.stream()
              .filter(p -> !loadedPlugins.contains(p))
              .map(WeaverPlugin::name)
              .collect(java.util.stream.Collectors.joining(", ")));
    }
    return Collections.unmodifiableList(loadedPlugins);
  }

  /**
   * Load all plugins using the context ClassLoader.
   *
   * @see #loadPlugins(ClassLoader, InterceptorRegistry, Map)
   */
  public List<WeaverPlugin> loadPlugins(InterceptorRegistry registry, Map<String, String> config) {
    return loadPlugins(Thread.currentThread().getContextClassLoader(), registry, config);
  }

  /**
   * Load plugins from a plugin directory, creating an isolated ClassLoader per JAR. This is the
   * preferred way to load plugins in production.
   *
   * @param pluginDir the directory containing plugin JARs
   * @param registry the interceptor registry
   * @param config agent configuration
   * @return the list of successfully loaded plugins
   */
  public List<WeaverPlugin> loadPluginsFromDirectory(
      String pluginDir, InterceptorRegistry registry, Map<String, String> config) {
    PluginJarScanner scanner = new PluginJarScanner();
    List<PluginClassLoader> classLoaders =
        scanner.scan(pluginDir, WeaverGirl.class.getClassLoader());

    List<WeaverPlugin> allPlugins = new ArrayList<>();
    for (PluginClassLoader cl : classLoaders) {
      List<WeaverPlugin> plugins = loadPlugins(cl, registry, config);
      allPlugins.addAll(plugins);
    }
    return allPlugins;
  }

  /** Destroy all loaded plugins in reverse order. Should be called during agent shutdown. */
  public void destroyAll() {
    // Destroy in reverse order of loading
    for (int i = loadedPlugins.size() - 1; i >= 0; i--) {
      WeaverPlugin plugin = loadedPlugins.get(i);
      try {
        plugin.destroy();
        log.info("Plugin {} destroyed", plugin.name());
      } catch (Exception e) {
        log.error("Failed to destroy plugin {}: {}", plugin.name(), e.getMessage(), e);
      }
    }
    loadedPlugins.clear();
  }

  /** Get all currently loaded plugins. */
  public List<WeaverPlugin> getLoadedPlugins() {
    return Collections.unmodifiableList(loadedPlugins);
  }

  private void loadPluginsSequential(
      List<WeaverPlugin> sorted,
      InterceptorRegistry registry,
      Map<String, String> config,
      java.util.Map<String, Long> perPluginMs) {
    for (WeaverPlugin plugin : sorted) {
      long pluginStart = System.nanoTime();
      try {
        log.info("Loading plugin: {}", plugin.name());
        PluginContext context = new DefaultPluginContext(registry, config, plugin.name());
        plugin.init(context);
        if (!plugin.isEnabled(context)) {
          log.info(
              "Plugin {} is disabled via isEnabled() check — skipping interceptors", plugin.name());
          AgentStatus.getInstance()
              .recordPluginStatus(plugin.name(), false, "disabled via isEnabled()");
          perPluginMs.put(plugin.name(), (System.nanoTime() - pluginStart) / 1_000_000L);
          continue;
        }
        plugin.registerInterceptors(registry);
        loadedPlugins.add(plugin);
        AgentStatus.getInstance().recordPluginStatus(plugin.name(), true, null);
        log.info("Plugin {} loaded successfully", plugin.name());
      } catch (Throwable e) {
        String errorMsg = e.getClass().getSimpleName() + ": " + e.getMessage();
        AgentStatus.getInstance().recordPluginStatus(plugin.name(), false, errorMsg);
        log.error("Failed to load plugin {}: {}", plugin.name(), e.getMessage(), e);
      }
      perPluginMs.put(plugin.name(), (System.nanoTime() - pluginStart) / 1_000_000L);
    }
  }

  private void loadPluginsParallel(
      List<WeaverPlugin> sorted,
      InterceptorRegistry registry,
      Map<String, String> config,
      java.util.Map<String, Long> perPluginMs) {
    ExecutorService executor = Executors.newFixedThreadPool(
        MAX_PARALLEL_PLUGIN_LOAD_THREADS,
        new ThreadFactory() {
          private final java.util.concurrent.atomic.AtomicInteger counter = new java.util.concurrent.atomic.AtomicInteger(0);
          @Override
          public Thread newThread(Runnable r) {
            Thread t = new Thread(r, "weaver-girl-plugin-loader-" + counter.incrementAndGet());
            t.setDaemon(true);
            return t;
          }
        });

    // P99 dependency-aware parallel loading: group plugins by dependency level.
    // Plugins within the same level can run in parallel; levels run sequentially.
    try {
      java.util.Map<WeaverPlugin, Integer> levelMap = new java.util.HashMap<>();
      java.util.Map<String, WeaverPlugin> nameMap = new java.util.HashMap<>();
      for (WeaverPlugin p : sorted) {
        nameMap.put(p.name(), p);
      }

      for (WeaverPlugin plugin : sorted) {
        int maxDepLevel = -1;
        for (String dep : getPluginDependencies(plugin)) {
          WeaverPlugin depPlugin = nameMap.get(dep);
          if (depPlugin != null) {
            maxDepLevel = Math.max(maxDepLevel, levelMap.get(depPlugin));
          }
        }
        levelMap.put(plugin, maxDepLevel + 1);
      }

      int maxLevel = 0;
      for (int level : levelMap.values()) {
        maxLevel = Math.max(maxLevel, level);
      }

      for (int level = 0; level <= maxLevel; level++) {
        List<WeaverPlugin> levelPlugins = new ArrayList<>();
        for (WeaverPlugin plugin : sorted) {
          if (levelMap.get(plugin) == level) {
            levelPlugins.add(plugin);
          }
        }

        if (levelPlugins.isEmpty()) {
          continue;
        }

        if (levelPlugins.size() == 1) {
          loadPluginSync(levelPlugins.get(0), registry, config, perPluginMs);
        } else {
          CountDownLatch latch = new CountDownLatch(levelPlugins.size());
          List<Future<?>> futures = new ArrayList<>();
          for (WeaverPlugin plugin : levelPlugins) {
            futures.add(executor.submit(() -> {
              loadPluginSync(plugin, registry, config, perPluginMs);
              latch.countDown();
            }));
          }
          latch.await();
          for (Future<?> f : futures) {
            try {
              f.get();
            } catch (Exception e) {
              log.debug("Plugin loader future failed: {}", e.getMessage());
            }
          }
        }
      }
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
      log.warn("Plugin loading interrupted", e);
    } finally {
      executor.shutdownNow();
    }
  }

  private java.util.Set<String> getPluginDependencies(WeaverPlugin plugin) {
    try {
      java.lang.reflect.Method dependsMethod = plugin.getClass().getMethod("depends");
      Object result = dependsMethod.invoke(plugin);
      if (result instanceof java.util.Collection) {
        java.util.Set<String> deps = new java.util.HashSet<>();
        for (Object o : (java.util.Collection<?>) result) {
          if (o instanceof String) {
            deps.add((String) o);
          }
        }
        return deps;
      }
    } catch (Exception e) {
      log.debug("Failed to get plugin dependencies for {}: {}", plugin.name(), e.getMessage());
    }
    return java.util.Collections.emptySet();
  }

  private void loadPluginSync(
      WeaverPlugin plugin,
      InterceptorRegistry registry,
      Map<String, String> config,
      java.util.Map<String, Long> perPluginMs) {
    long pluginStart = System.nanoTime();
    try {
      PluginContext context = new DefaultPluginContext(registry, config, plugin.name());
      plugin.init(context);
      if (!plugin.isEnabled(context)) {
        log.info(
            "Plugin {} is disabled via isEnabled() check — skipping interceptors", plugin.name());
        AgentStatus.getInstance()
            .recordPluginStatus(plugin.name(), false, "disabled via isEnabled()");
        perPluginMs.put(plugin.name(), (System.nanoTime() - pluginStart) / 1_000_000L);
        return;
      }
      plugin.registerInterceptors(registry);
      loadedPlugins.add(plugin);
      AgentStatus.getInstance().recordPluginStatus(plugin.name(), true, null);
      log.info("Plugin {} loaded successfully", plugin.name());
    } catch (Throwable e) {
      String errorMsg = e.getClass().getSimpleName() + ": " + e.getMessage();
      AgentStatus.getInstance().recordPluginStatus(plugin.name(), false, errorMsg);
      log.error("Failed to load plugin {}: {}", plugin.name(), e.getMessage(), e);
    }
    perPluginMs.put(plugin.name(), (System.nanoTime() - pluginStart) / 1_000_000L);
  }
}
