package com.github.cc11001100.weavergirl.core.plugin;

import com.github.cc11001100.weavergirl.api.interceptor.InterceptorDefinition;
import com.github.cc11001100.weavergirl.api.plugin.WeaverPlugin;
import com.github.cc11001100.weavergirl.api.registry.InterceptorRegistry;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Records which plugin registered each interceptor.
 *
 * <p>A plugin registers from two places: {@code registerInterceptors}, and {@code init} through the
 * registry its {@code PluginContext} hands out. Neither place is visible to the plugin manager, and
 * the definition name does not reliably say which plugin it came from — a hand-written name can leave
 * the class out, and a scanned interceptor is named after the class it weaves, not the plugin. So the
 * registry a plugin is given records the owner as the definition is registered, and disabling the
 * plugin removes exactly those definitions.
 */
public final class PluginOwnership {

  /**
   * Keyed by the plugin instance. Two plugins can share a name, and recording by name would credit
   * both with the same interceptors, so disabling one removed the other's.
   */
  private final ConcurrentHashMap<WeaverPlugin, Set<String>> interceptorsByPlugin =
      new ConcurrentHashMap<>();

  /** A registry that delegates to {@code delegate} and records every definition under {@code plugin}. */
  public InterceptorRegistry recording(WeaverPlugin plugin, InterceptorRegistry delegate) {
    return new OwnershipRegistry(plugin, delegate);
  }

  /** The definition names {@code plugin} has registered, or an empty set when it registered none. */
  public Set<String> interceptorsOf(WeaverPlugin plugin) {
    Set<String> names = interceptorsByPlugin.get(plugin);
    return names == null ? Collections.emptySet() : Collections.unmodifiableSet(names);
  }

  /**
   * Record that {@code plugin} owns {@code definition} without going through a registry. Used when the
   * definition was registered before its owner was known, which is how a test sets the scene the loader
   * normally builds up itself.
   */
  public void record(WeaverPlugin plugin, String definition) {
    if (plugin == null || definition == null) {
      return;
    }
    interceptorsByPlugin.computeIfAbsent(plugin, k -> ConcurrentHashMap.newKeySet()).add(definition);
  }

  /** Forget that {@code plugin} owns {@code definition}, because it was unregistered. */
  public void forget(WeaverPlugin plugin, String definition) {
    Set<String> names = interceptorsByPlugin.get(plugin);
    if (names != null) {
      names.remove(definition);
    }
  }

  /** The registry wrapper that attributes registrations to one plugin. */
  private final class OwnershipRegistry implements InterceptorRegistry {
    private final WeaverPlugin plugin;
    private final InterceptorRegistry delegate;

    OwnershipRegistry(WeaverPlugin plugin, InterceptorRegistry delegate) {
      this.plugin = plugin;
      this.delegate = delegate;
    }

    @Override
    public void register(InterceptorDefinition definition) {
      delegate.register(definition);
      if (definition != null && definition.getName() != null) {
        interceptorsByPlugin
            .computeIfAbsent(plugin, k -> ConcurrentHashMap.newKeySet())
            .add(definition.getName());
      }
    }

    @Override
    public boolean unregister(String name) {
      boolean removed = delegate.unregister(name);
      if (removed) {
        forget(plugin, name);
      }
      return removed;
    }

    @Override
    public List<InterceptorDefinition> getInterceptorsForClass(String className) {
      return delegate.getInterceptorsForClass(className);
    }

    @Override
    public List<InterceptorDefinition> getAllDefinitions() {
      return delegate.getAllDefinitions();
    }

    @Override
    public void clear() {
      // The delegate is shared with every other plugin's recording registry, so clearing through
      // one wrapper clears the whole registry — the unweave path clears the raw DefaultInterceptorRegistry,
      // which leaves every wrapper's records stale, so wipe them all rather than just this plugin's.
      interceptorsByPlugin.clear();
      delegate.clear();
    }
  }

  /** Exposed so a test can tell a recorded name from an empty result. */
  List<String> pluginsOwning(String definition) {
    List<String> owners = new ArrayList<>();
    for (java.util.Map.Entry<WeaverPlugin, Set<String>> entry : interceptorsByPlugin.entrySet()) {
      if (entry.getValue().contains(definition)) {
        owners.add(entry.getKey().name());
      }
    }
    return owners;
  }
}
