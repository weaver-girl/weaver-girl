package com.github.cc11001100.weavergirl.core.plugin;

import com.github.cc11001100.weavergirl.api.interceptor.InterceptorDefinition;
import com.github.cc11001100.weavergirl.api.event.InterceptorEvent;
import com.github.cc11001100.weavergirl.api.event.InterceptorEventPublisher;
import com.github.cc11001100.weavergirl.core.event.LifecycleEvents;
import com.github.cc11001100.weavergirl.api.plugin.PluginInfo;
import com.github.cc11001100.weavergirl.api.plugin.PluginManager;
import com.github.cc11001100.weavergirl.api.plugin.PluginManagerHolder;
import com.github.cc11001100.weavergirl.api.plugin.PluginState;
import com.github.cc11001100.weavergirl.api.plugin.WeaverPlugin;
import com.github.cc11001100.weavergirl.api.registry.InterceptorRegistry;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Default implementation of {@link PluginManager}.
 *
 * <p>Manages runtime plugin lifecycle: enable/disable/unload. Tracks plugin states and coordinates
 * interceptor registration/unregistration.
 *
 * @since 1.1.0
 */
public class DefaultPluginManager implements PluginManager {

  private static final Logger log = LoggerFactory.getLogger(DefaultPluginManager.class);

  private final PluginLoader pluginLoader;
  private final InterceptorRegistry registry;
  private final ConcurrentHashMap<String, PluginEntry> pluginEntries = new ConcurrentHashMap<>();

  /**
   * Create a DefaultPluginManager.
   *
   * @param pluginLoader the plugin loader that has already loaded plugins
   * @param registry the interceptor registry for registering/unregistering
   */
  public DefaultPluginManager(PluginLoader pluginLoader, InterceptorRegistry registry) {
    this.pluginLoader = pluginLoader;
    this.registry = registry;
    PluginManagerHolder.setInstance(this);

    // Initialize entries for all already-loaded plugins
    for (WeaverPlugin plugin : pluginLoader.getLoadedPlugins()) {
      String name = plugin.name();
      int interceptorCount = countInterceptorsForPlugin(name);
      pluginEntries.put(
          name,
          new PluginEntry(
              plugin, PluginState.ACTIVE, System.currentTimeMillis(), interceptorCount, null));
    }
  }

  @Override
  public boolean disablePlugin(String pluginName) {
    PluginEntry entry = pluginEntries.get(pluginName);
    if (entry == null) {
      log.warn("[PluginManager] Plugin '{}' not found — cannot disable", pluginName);
      return false;
    }

    if (!entry.state.canTransitionTo(PluginState.DISABLED)) {
      log.warn("[PluginManager] Plugin '{}' in state {} — cannot disable", pluginName, entry.state);
      try {
        InterceptorEventPublisher.getInstance()
            .publish(
                LifecycleEvents.plugin(
                    LifecycleEvents.PHASE_DISABLE, pluginName, false, "state=" + entry.state));
      } catch (Throwable t) {
        log.debug("Lifecycle event publish failed: {}", t.getMessage());
      }
      return false;
    }

    // Unregister all interceptors for this plugin
    int removed = unregisterPluginInterceptors(pluginName);
    entry.state = PluginState.DISABLED;
    entry.lastStateChangedAt = System.currentTimeMillis();
    entry.interceptorCount = 0;

    log.info(
        "[PluginManager] Plugin '{}' disabled ({} interceptors unregistered)", pluginName, removed);
    try {
      InterceptorEventPublisher.getInstance()
          .publish(
              LifecycleEvents.plugin(
                  LifecycleEvents.PHASE_DISABLE, pluginName, true,
                  "unregistered=" + removed));
    } catch (Throwable t) {
      log.debug("Lifecycle event publish failed: {}", t.getMessage());
    }
    return true;
  }

  @Override
  public boolean enablePlugin(String pluginName) {
    PluginEntry entry = pluginEntries.get(pluginName);
    if (entry == null) {
      log.warn("[PluginManager] Plugin '{}' not found — cannot enable", pluginName);
      return false;
    }

    if (!entry.state.canTransitionTo(PluginState.ACTIVE)) {
      log.warn("[PluginManager] Plugin '{}' in state {} — cannot enable", pluginName, entry.state);
      try {
        InterceptorEventPublisher.getInstance()
            .publish(
                LifecycleEvents.plugin(
                    LifecycleEvents.PHASE_ENABLE, pluginName, false, "state=" + entry.state));
      } catch (Throwable t) {
        log.debug("Lifecycle event publish failed: {}", t.getMessage());
      }
      return false;
    }

    // Re-register interceptors
    try {
      entry.plugin.registerInterceptors(registry);
      entry.state = PluginState.ACTIVE;
      entry.lastStateChangedAt = System.currentTimeMillis();
      entry.interceptorCount = countInterceptorsForPlugin(pluginName);

      log.info(
          "[PluginManager] Plugin '{}' enabled ({} interceptors registered)",
          pluginName,
          entry.interceptorCount);
      try {
        InterceptorEventPublisher.getInstance()
            .publish(
                LifecycleEvents.plugin(
                    LifecycleEvents.PHASE_ENABLE, pluginName, true,
                    "registered=" + entry.interceptorCount));
      } catch (Throwable t) {
        log.debug("Lifecycle event publish failed: {}", t.getMessage());
      }
      return true;
    } catch (Exception e) {
      log.error("[PluginManager] Failed to enable plugin '{}': {}", pluginName, e.getMessage(), e);
      try {
        InterceptorEventPublisher.getInstance()
            .publish(
                LifecycleEvents.plugin(
                    LifecycleEvents.PHASE_ENABLE, pluginName, false, e.getMessage()));
      } catch (Throwable t) {
        log.debug("Lifecycle event publish failed: {}", t.getMessage());
      }
      return false;
    }
  }

  @Override
  public boolean unloadPlugin(String pluginName) {
    PluginEntry entry = pluginEntries.get(pluginName);
    if (entry == null) {
      log.warn("[PluginManager] Plugin '{}' not found — cannot unload", pluginName);
      return false;
    }

    if (!entry.state.canTransitionTo(PluginState.UNLOADED)) {
      log.warn("[PluginManager] Plugin '{}' in state {} — cannot unload", pluginName, entry.state);
      return false;
    }

    // Unregister interceptors if still active
    if (entry.state == PluginState.ACTIVE) {
      unregisterPluginInterceptors(pluginName);
    }

    // Destroy the plugin
    try {
      entry.plugin.destroy();
    } catch (Exception e) {
      log.warn("[PluginManager] Error destroying plugin '{}': {}", pluginName, e.getMessage());
    }

    entry.state = PluginState.UNLOADED;
    entry.lastStateChangedAt = System.currentTimeMillis();
    entry.interceptorCount = 0;

    log.info("[PluginManager] Plugin '{}' unloaded and destroyed", pluginName);
    try {
      InterceptorEventPublisher.getInstance()
          .publish(LifecycleEvents.plugin(LifecycleEvents.PHASE_UNLOAD, pluginName, true, null));
    } catch (Throwable t) {
      log.debug("Lifecycle event publish failed: {}", t.getMessage());
    }
    return true;
  }

  @Override
  public Optional<PluginInfo> getPluginInfo(String pluginName) {
    PluginEntry entry = pluginEntries.get(pluginName);
    if (entry == null) {
      return Optional.empty();
    }
    return Optional.of(entry.toPluginInfo());
  }

  @Override
  public List<PluginInfo> getAllPluginInfo() {
    List<PluginInfo> result = new ArrayList<>();
    for (PluginEntry entry : pluginEntries.values()) {
      result.add(entry.toPluginInfo());
    }
    return Collections.unmodifiableList(result);
  }

  @Override
  public boolean isPluginActive(String pluginName) {
    PluginEntry entry = pluginEntries.get(pluginName);
    return entry != null && entry.state == PluginState.ACTIVE;
  }

  @Override
  public int getActivePluginCount() {
    int count = 0;
    for (PluginEntry entry : pluginEntries.values()) {
      if (entry.state == PluginState.ACTIVE) {
        count++;
      }
    }
    return count;
  }

  // ===== Internal helpers =====

  private int unregisterPluginInterceptors(String pluginName) {
    List<InterceptorDefinition> allDefs = registry.getAllDefinitions();
    int removed = 0;
    for (InterceptorDefinition def : allDefs) {
      // Convention: YAML interceptors use "yaml-" prefix, plugins use their plugin name or
      // generated names
      // We track which interceptors belong to a plugin via naming convention
      if (def.getName().startsWith(pluginName) || def.getName().contains("-" + pluginName + "-")) {
        if (registry.unregister(def.getName())) {
          removed++;
        }
      }
    }
    return removed;
  }

  private int countInterceptorsForPlugin(String pluginName) {
    int count = 0;
    for (InterceptorDefinition def : registry.getAllDefinitions()) {
      if (def.getName().startsWith(pluginName) || def.getName().contains("-" + pluginName + "-")) {
        count++;
      }
    }
    return count;
  }

  /** Internal tracking entry for a managed plugin. */
  private static class PluginEntry {
    final WeaverPlugin plugin;
    volatile PluginState state;
    final long loadedAt;
    volatile int interceptorCount;
    volatile long lastStateChangedAt;
    final String version;

    PluginEntry(
        WeaverPlugin plugin,
        PluginState state,
        long loadedAt,
        int interceptorCount,
        String version) {
      this.plugin = plugin;
      this.state = state;
      this.loadedAt = loadedAt;
      this.interceptorCount = interceptorCount;
      this.version = version;
      this.lastStateChangedAt = loadedAt;
    }

    PluginInfo toPluginInfo() {
      return new PluginInfo(
          plugin.name(), state, version, interceptorCount, loadedAt, lastStateChangedAt);
    }
  }
}
