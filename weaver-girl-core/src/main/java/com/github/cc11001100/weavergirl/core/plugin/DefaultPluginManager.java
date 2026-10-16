package com.github.cc11001100.weavergirl.core.plugin;

import com.github.cc11001100.weavergirl.api.interceptor.InterceptorDefinition;
import com.github.cc11001100.weavergirl.api.event.InterceptorEvent;
import com.github.cc11001100.weavergirl.api.event.InterceptorEventPublisher;
import com.github.cc11001100.weavergirl.api.event.LifecycleEvents;
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
  private final PluginOwnership ownership;

  /**
   * Keyed by the plugin instance, not its name. Two plugins may share a name — a built-in and the
   * drop-in that replaces it, or two jars that picked the same one — and a name-keyed map keeps one of
   * them, so disabling or unloading that name destroyed both while reporting only one.
   */
  private final ConcurrentHashMap<WeaverPlugin, PluginEntry> pluginEntries = new ConcurrentHashMap<>();

  /**
   * Create a DefaultPluginManager.
   *
   * @param pluginLoader the plugin loader that has already loaded plugins
   * @param registry the interceptor registry for registering/unregistering
   */
  public DefaultPluginManager(PluginLoader pluginLoader, InterceptorRegistry registry) {
    this(pluginLoader, registry, pluginLoader.ownership());
  }

  /**
   * Create a DefaultPluginManager over an ownership record the caller already filled, which is how a
   * test drives plugins through their context without the loader.
   */
  DefaultPluginManager(
      PluginLoader pluginLoader, InterceptorRegistry registry, PluginOwnership ownership) {
    this.pluginLoader = pluginLoader;
    this.registry = registry;
    this.ownership = ownership;
    PluginManagerHolder.setInstance(this);

    // The loader recorded ownership while the plugins loaded, so there is nothing to replay here.
    // Replaying registerInterceptors would build fresh definitions and register them over the ones
    // already loaded, and registration runs an interceptor's initialize() again — a second connection
    // pool, a second background thread, a counter that starts at two.
    for (WeaverPlugin plugin : pluginLoader.getLoadedPlugins()) {
      String name = plugin.name();
      int recorded = ownership.interceptorsOf(plugin).size();
      PluginEntry entry =
          new PluginEntry(
              plugin,
              PluginState.ACTIVE,
              System.currentTimeMillis(),
              recorded == 0 ? countInterceptorsForPlugin(name) : recorded,
              null);
      entry.interceptorNames.addAll(ownership.interceptorsOf(plugin));
      pluginEntries.put(plugin, entry);
    }
  }

  /**
   * Suspend every active plugin that requires a name just disabled, then repeat for what those required. A
   * suspended plugin keeps its instance and only loses its interceptors, so enabling the requirement again can
   * bring it back. A requirement is still met while any plugin of that name stays active.
   *
   * @return the number of interceptors unregistered
   */
  private int suspendDependents(List<PluginEntry> disabled) {
    java.util.Set<String> names = new java.util.HashSet<>();
    for (PluginEntry entry : disabled) {
      names.add(entry.plugin.name());
    }
    int removed = 0;
    boolean found = true;
    while (found) {
      found = false;
      for (PluginEntry entry : new ArrayList<>(pluginEntries.values())) {
        if (entry.state != PluginState.ACTIVE || names.contains(entry.plugin.name())) {
          continue;
        }
        for (String dep : pluginLoader.requirementsOf(entry.plugin)) {
          if (names.contains(dep) && !nameActive(dep)) {
            removed += unregisterPluginInterceptors(entry);
            entry.state = PluginState.DISABLED;
            entry.suspended = true;
            entry.lastStateChangedAt = System.currentTimeMillis();
            entry.interceptorCount = 0;
            names.add(entry.plugin.name());
            found = true;
            log.info(
                "[PluginManager] Plugin '{}' suspended because it requires '{}', which is disabled",
                entry.plugin.name(),
                dep);
            break;
          }
        }
      }
    }
    return removed;
  }

  /**
   * Bring back every plugin that was suspended because a name just re-enabled is what it requires, then repeat
   * for the plugins suspended because of those. A plugin disabled on its own is not suspended and stays
   * disabled, and one whose other requirements are still unmet stays suspended.
   *
   * @return the number of interceptors registered again
   */
  private int resumeDependents(List<PluginEntry> enabled) {
    java.util.Set<String> names = new java.util.HashSet<>();
    for (PluginEntry entry : enabled) {
      names.add(entry.plugin.name());
    }
    int registered = 0;
    boolean found = true;
    while (found) {
      found = false;
      for (PluginEntry entry : new ArrayList<>(pluginEntries.values())) {
        if (!entry.suspended || names.contains(entry.plugin.name())) {
          continue;
        }
        // Only a plugin waiting on a name that just came back is considered. One suspended because something
        // unrelated is disabled is not this call's to resume, even when its own requirements happen to be met.
        boolean waitingOnEnabled = false;
        boolean blocked = false;
        for (String dep : pluginLoader.requirementsOf(entry.plugin)) {
          if (names.contains(dep)) {
            waitingOnEnabled = true;
          }
          if (!nameActive(dep)) {
            blocked = true;
          }
        }
        if (!waitingOnEnabled || blocked) {
          continue;
        }
        java.util.Set<String> interceptorNames = new java.util.LinkedHashSet<>();
        try {
          entry.plugin.registerInterceptors(
              ownership.recording(entry.plugin, recording(interceptorNames)));
        } catch (Exception e) {
          log.error(
              "[PluginManager] Plugin '{}' could not resume after its requirement returned: {}",
              entry.plugin.name(),
              e.getMessage());
          continue;
        }
        entry.interceptorNames.clear();
        entry.interceptorNames.addAll(interceptorNames);
        entry.state = PluginState.ACTIVE;
        entry.suspended = false;
        entry.lastStateChangedAt = System.currentTimeMillis();
        entry.interceptorCount = interceptorNames.size();
        registered += interceptorNames.size();
        names.add(entry.plugin.name());
        found = true;
        log.info(
            "[PluginManager] Plugin '{}' resumed because the plugin it requires is active again",
            entry.plugin.name());
      }
    }
    return registered;
  }

  /** Whether any plugin of {@code name} is still active. A requirement names a plugin, not one instance. */
  private boolean nameActive(String name) {
    for (PluginEntry entry : entriesNamed(name)) {
      if (entry.state == PluginState.ACTIVE) {
        return true;
      }
    }
    return false;
  }

  /** The plugins behind {@code entries}. */
  private List<WeaverPlugin> pluginsOf(List<PluginEntry> entries) {
    List<WeaverPlugin> plugins = new ArrayList<>();
    for (PluginEntry entry : entries) {
      plugins.add(entry.plugin);
    }
    return plugins;
  }

  /** Every managed plugin that goes by {@code pluginName}, in load order. */
  private List<PluginEntry> entriesNamed(String pluginName) {
    List<PluginEntry> entries = new ArrayList<>();
    for (WeaverPlugin plugin : pluginLoader.getLoadedPlugins()) {
      PluginEntry entry = pluginEntries.get(plugin);
      if (entry != null && pluginName.equals(plugin.name())) {
        entries.add(entry);
      }
    }
    return entries;
  }

  @Override
  public boolean disablePlugin(String pluginName) {
    // A name can belong to more than one plugin. Each one that can move to DISABLED does; the call
    // reports success when at least one did, and a name that matches nothing reports failure.
    List<PluginEntry> entries = entriesNamed(pluginName);
    if (entries.isEmpty()) {
      log.warn("[PluginManager] Plugin '{}' not found — cannot disable", pluginName);
      return false;
    }

    List<PluginEntry> movable = new ArrayList<>();
    for (PluginEntry candidate : entries) {
      if (candidate.state.canTransitionTo(PluginState.DISABLED)) {
        movable.add(candidate);
      }
    }
    if (movable.isEmpty()) {
      log.warn(
          "[PluginManager] Plugin '{}' in state {} — cannot disable",
          pluginName,
          entries.get(0).state);
      try {
        InterceptorEventPublisher.getInstance()
            .publish(
                LifecycleEvents.plugin(
                    LifecycleEvents.PHASE_DISABLE, pluginName, false, "state=" + entries.get(0).state));
      } catch (Throwable t) {
        log.debug("Lifecycle event publish failed: {}", t.getMessage());
      }
      return false;
    }

    // Each plugin unregisters only the interceptors it owns, so two plugins that share the name do not
    // take each other's.
    int removed = 0;
    long now = System.currentTimeMillis();
    for (PluginEntry candidate : movable) {
      removed += unregisterPluginInterceptors(candidate);
      candidate.state = PluginState.DISABLED;
      candidate.suspended = false;
      candidate.lastStateChangedAt = now;
      candidate.interceptorCount = 0;
    }

    // A plugin that requires this one would keep intercepting calls it refused to start without. It is
    // suspended rather than disabled: nothing destroyed it, and enabling this one again brings it back.
    // A requirement stays met while any plugin of that name is still active.
    removed += suspendDependents(movable);

    log.info(
        "[PluginManager] Plugin '{}' disabled ({} instance(s), {} interceptors unregistered)",
        pluginName,
        movable.size(),
        removed);
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
    List<PluginEntry> entries = entriesNamed(pluginName);
    if (entries.isEmpty()) {
      log.warn("[PluginManager] Plugin '{}' not found — cannot enable", pluginName);
      return false;
    }

    List<PluginEntry> movable = new ArrayList<>();
    for (PluginEntry candidate : entries) {
      if (candidate.state.canTransitionTo(PluginState.ACTIVE) && !candidate.suspended) {
        movable.add(candidate);
      }
    }
    if (movable.isEmpty()) {
      log.warn(
          "[PluginManager] Plugin '{}' in state {} — cannot enable", pluginName, entries.get(0).state);
      try {
        InterceptorEventPublisher.getInstance()
            .publish(
                LifecycleEvents.plugin(
                    LifecycleEvents.PHASE_ENABLE, pluginName, false, "state=" + entries.get(0).state));
      } catch (Throwable t) {
        log.debug("Lifecycle event publish failed: {}", t.getMessage());
      }
      return false;
    }

    // Re-register each plugin's interceptors and remember exactly which definitions the call added, so a
    // later disable removes those and not whatever another plugin of the same name registered.
    int registered = 0;
    long now = System.currentTimeMillis();
    for (PluginEntry entry : movable) {
      java.util.Set<String> names = new java.util.LinkedHashSet<>();
      try {
        entry.plugin.registerInterceptors(ownership.recording(entry.plugin, recording(names)));
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
      entry.interceptorNames.clear();
      entry.interceptorNames.addAll(names);
      entry.state = PluginState.ACTIVE;
      entry.lastStateChangedAt = now;
      entry.interceptorCount = names.size();
      registered += names.size();
    }

    // Disabling this plugin suspended the ones that require it. Now that it is active again their requirement
    // is met, so they come back too — unless one of them was disabled on its own, which this leaves disabled.
    registered += resumeDependents(movable);

    log.info(
        "[PluginManager] Plugin '{}' enabled ({} instance(s), {} interceptors registered)",
        pluginName,
        movable.size(),
        registered);
    try {
      InterceptorEventPublisher.getInstance()
          .publish(
              LifecycleEvents.plugin(
                  LifecycleEvents.PHASE_ENABLE, pluginName, true, "registered=" + registered));
    } catch (Throwable t) {
      log.debug("Lifecycle event publish failed: {}", t.getMessage());
    }
    return true;
  }

  @Override
  public boolean unloadPlugin(String pluginName) {
    List<PluginEntry> entries = entriesNamed(pluginName);
    if (entries.isEmpty()) {
      log.warn("[PluginManager] Plugin '{}' not found — cannot unload", pluginName);
      return false;
    }

    List<PluginEntry> movable = new ArrayList<>();
    for (PluginEntry candidate : entries) {
      if (candidate.state.canTransitionTo(PluginState.UNLOADED)) {
        movable.add(candidate);
      }
    }
    if (movable.isEmpty()) {
      log.warn(
          "[PluginManager] Plugin '{}' in state {} — cannot unload", pluginName, entries.get(0).state);
      return false;
    }

    // The loader unloads the named plugins and every plugin that required one of them. It is the same path a
    // removed jar takes, so the rule is identical however the plugin went: a requirement is still met while any
    // plugin of that name stays loaded, and a chain collapses from the one that just went. The entries only
    // record the state the loader already applied.
    for (WeaverPlugin dependent : pluginLoader.unloadDependentsOf(pluginsOf(movable))) {
      PluginEntry entry = pluginEntries.get(dependent);
      if (entry != null && entry.state != PluginState.UNLOADED) {
        entry.state = PluginState.UNLOADED;
        entry.lastStateChangedAt = System.currentTimeMillis();
        entry.interceptorCount = 0;
      }
    }

    log.info(
        "[PluginManager] Plugin '{}' unloaded and destroyed ({} instance(s))",
        pluginName,
        movable.size());
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
    // One info per name, matching the interface. The interceptor count sums every plugin of that name,
    // and the state is the most active one, so a name is not reported idle while one of its plugins is
    // still running.
    List<PluginEntry> entries = entriesNamed(pluginName);
    if (entries.isEmpty()) {
      return Optional.empty();
    }
    PluginEntry chosen = entries.get(0);
    int interceptors = 0;
    for (PluginEntry entry : entries) {
      interceptors += entry.interceptorCount;
      if (entry.state == PluginState.ACTIVE) {
        chosen = entry;
      }
    }
    PluginInfo info = chosen.toPluginInfo();
    return Optional.of(
        new PluginInfo(
            info.getName(),
            info.getState(),
            info.getVersion(),
            interceptors,
            info.getLoadedAt(),
            info.getLastStateChangedAt()));
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
    for (PluginEntry entry : entriesNamed(pluginName)) {
      if (entry.state == PluginState.ACTIVE) {
        return true;
      }
    }
    return false;
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

  private int unregisterPluginInterceptors(PluginEntry entry) {
    String pluginName = entry.plugin.name();
    List<InterceptorDefinition> allDefs = registry.getAllDefinitions();
    int removed = 0;
    for (InterceptorDefinition def : allDefs) {
      // The definition remembers the plugin that registered it, which holds however the plugin got hold
      // of the registry. The ownership record covers the same ground for registrations made through the
      // registry the loader handed out. Only a definition with neither falls back to the name convention.
      boolean owned =
          def.getOwner() == entry.plugin
              || ownership.interceptorsOf(entry.plugin).contains(def.getName())
              || entry.interceptorNames.contains(def.getName())
              || (def.getOwner() == null
                  && ownership.interceptorsOf(entry.plugin).isEmpty()
                  && entry.interceptorNames.isEmpty()
                  && belongsToPlugin(def.getName(), pluginName));
      if (owned && registry.unregister(def.getName())) {
        ownership.forget(entry.plugin, def.getName());
        removed++;
      }
    }
    if (entry != null) {
      entry.interceptorNames.clear();
    }
    return removed;
  }

  /** A registry that delegates everything and notes the name of each definition it is handed. */
  private InterceptorRegistry recording(java.util.Set<String> names) {
    return new InterceptorRegistry() {
      @Override
      public void register(InterceptorDefinition definition) {
        registry.register(definition);
        if (definition != null && definition.getName() != null) {
          names.add(definition.getName());
        }
      }

      @Override
      public boolean unregister(String name) {
        boolean removed = registry.unregister(name);
        if (removed) {
          names.remove(name);
        }
        return removed;
      }

      @Override
      public List<InterceptorDefinition> getInterceptorsForClass(String className) {
        return registry.getInterceptorsForClass(className);
      }

      @Override
      public List<InterceptorDefinition> getAllDefinitions() {
        return registry.getAllDefinitions();
      }
    };
  }

  private int countInterceptorsForPlugin(String pluginName) {
    int count = 0;
    for (InterceptorDefinition def : registry.getAllDefinitions()) {
      if (belongsToPlugin(def.getName(), pluginName)) {
        count++;
      }
    }
    return count;
  }

  /**
   * Whether an interceptor definition belongs to a plugin, judged from its name.
   *
   * <p>The builder names a definition {@code pluginName + "-" + classPattern + "-" + methodPattern}.
   * The plugin name may itself contain {@code -}, but the class pattern is a package-qualified class
   * name and so always contains a {@code .}. That dot is the boundary: everything before the first one
   * is {@code pluginName + "-"}. A character prefix cannot draw this line, because {@code jdbc} is a
   * prefix of {@code jdbc-pool} and disabling the first used to unregister every interceptor the
   * second had registered.
   *
   * <p>Field and catch definitions put a fixed kind word between the two, {@code plugin-field-} and
   * {@code plugin-catch-}, and that word has to be stepped over to reach the plugin name.
   *
   * <p>A definition with no dot has no class pattern, so there is no boundary to find. It belongs to
   * the plugin only when the whole name is the plugin name.
   */
  private static boolean belongsToPlugin(String definitionName, String pluginName) {
    if (definitionName == null || pluginName == null || pluginName.isEmpty()) {
      return false;
    }
    int dot = definitionName.indexOf('.');
    if (dot < 0) {
      return definitionName.equals(pluginName);
    }
    int boundary = definitionName.lastIndexOf('-', dot);
    if (boundary < 0) {
      return false;
    }
    String head = definitionName.substring(0, boundary);
    for (String kind : DEFINITION_KIND_MARKERS) {
      if (head.endsWith("-" + kind)) {
        head = head.substring(0, head.length() - kind.length() - 1);
        break;
      }
    }
    return head.equals(pluginName);
  }

  /** The words the builders insert between a plugin name and its class pattern. */
  private static final String[] DEFINITION_KIND_MARKERS = {"field", "catch"};

  /** Internal tracking entry for a managed plugin. */
  private static class PluginEntry {
    final WeaverPlugin plugin;
    volatile PluginState state;
    final long loadedAt;
    volatile int interceptorCount;
    volatile long lastStateChangedAt;
    final String version;

    /** Definition names this plugin registered through the manager, as opposed to at load time. */
    final java.util.Set<String> interceptorNames = new java.util.LinkedHashSet<>();

    /**
     * Set when this plugin was disabled because a plugin it requires is disabled, rather than because someone
     * disabled it. Re-enabling the requirement brings it back; re-enabling it on its own must not, since the
     * requirement is still disabled.
     */
    volatile boolean suspended;

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
