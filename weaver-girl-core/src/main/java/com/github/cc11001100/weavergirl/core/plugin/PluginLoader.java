package com.github.cc11001100.weavergirl.core.plugin;

import com.github.cc11001100.weavergirl.api.plugin.PluginContext;
import com.github.cc11001100.weavergirl.api.plugin.PluginMetadata;
import com.github.cc11001100.weavergirl.api.plugin.WeaverPlugin;
import com.github.cc11001100.weavergirl.api.registry.InterceptorRegistry;
import com.github.cc11001100.weavergirl.api.event.InterceptorEventPublisher;
import com.github.cc11001100.weavergirl.api.event.LifecycleEvents;
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

  // Filled from the loader threads when plugins load in parallel, so a plain ArrayList would drop
  // entries under concurrent add.
  private final List<WeaverPlugin> loadedPlugins = new java.util.concurrent.CopyOnWriteArrayList<>();

  /**
   * Which plugin registered which interceptor. Filled while plugins load, because that is the only
   * moment a registration can be tied to the plugin making it, and read back by the plugin manager
   * when a plugin is disabled.
   */
  private final PluginOwnership ownership = new PluginOwnership();

  /**
   * One checker for the life of the loader, so a plugin loaded now is remembered when another jar is
   * loaded later. A fresh checker per call would not know a plugin of the same name and version was
   * already loaded, and would load it again.
   */
  private final PluginCompatibilityChecker compatibility =
      new PluginCompatibilityChecker(agentVersion(), WeaverPlugin.API_VERSION);

  /** What one loaded jar contributed, and the content it had when it was loaded. */
  private static final class LoadedJar {
    final java.util.List<WeaverPlugin> plugins = new java.util.concurrent.CopyOnWriteArrayList<>();
    volatile long fingerprint;

    LoadedJar(long fingerprint) {
      this.fingerprint = fingerprint;
    }
  }

  /**
   * Jars already loaded, by canonical path. A directory is scanned again when it changes: a jar whose
   * content is unchanged must not be loaded a second time, one whose content changed is loaded again,
   * and one that has disappeared takes its plugins with it.
   */
  private final java.util.concurrent.ConcurrentHashMap<String, LoadedJar> loadedJars =
      new java.util.concurrent.ConcurrentHashMap<>();

  /** The registry plugins were loaded into, so a jar removed later can unregister what it added. */
  private volatile InterceptorRegistry registry;

  /**
   * The jars the last directory scan opened, so a plugin's metadata is still reachable once its jar has
   * been loaded. A plugin's exports are read from here when its dependencies are wired.
   */
  private volatile List<PluginJarScanner.PluginJar> scannedJars = java.util.Collections.emptyList();

  /** The context each plugin was initialized with, so the services it published can be withdrawn. */
  private final java.util.concurrent.ConcurrentHashMap<WeaverPlugin, DefaultPluginContext> contexts =
      new java.util.concurrent.ConcurrentHashMap<>();

  /**
   * The plugins each loaded plugin requires, from its method and its jar metadata together. Unloading one of
   * them has to unload this plugin too: a hard requirement that only held at load time would leave it running
   * without the plugin it refused to start without.
   */
  private final java.util.concurrent.ConcurrentHashMap<WeaverPlugin, java.util.List<String>> requirements =
      new java.util.concurrent.ConcurrentHashMap<>();

  /** The plugins {@code plugin} required when it loaded, or an empty list when it required nothing. */
  public java.util.List<String> requirementsOf(WeaverPlugin plugin) {
    java.util.List<String> required = requirements.get(plugin);
    return required == null ? java.util.Collections.emptyList() : required;
  }

  /**
   * Unload {@code gone} and then every loaded plugin that can no longer run without them. A hard requirement
   * only checked at load time would leave those intercepting calls they refused to start without. A requirement
   * is still met while any loaded plugin of that name remains, so unloading one of two plugins that share a
   * name removes nothing else. A plugin in {@code gone} that is no longer loaded is skipped, which is how a jar
   * removal reports the dependents of plugins it has already torn down.
   */
  public java.util.List<WeaverPlugin> unloadDependentsOf(java.util.Collection<WeaverPlugin> gone) {
    java.util.Set<String> unloaded = new java.util.HashSet<>();
    java.util.List<WeaverPlugin> removed = new java.util.ArrayList<>();
    for (WeaverPlugin plugin : gone) {
      unloaded.add(plugin.name());
      if (loadedPlugins.contains(plugin)) {
        unloadOne(plugin, "it was unloaded");
        removed.add(plugin);
      } else {
        // Not one this loader activated: the caller still has to have it torn down, and there is no loaded
        // entry to remove. A plugin the loader did activate is never in this branch, so it is not destroyed
        // twice. Its interceptors go with it, matched the way a loaded plugin's are.
        com.github.cc11001100.weavergirl.core.registry.DefaultInterceptorRegistry.untrack(plugin);
        unregisterOwnedBy(plugin, registry);
        withdrawServices(plugin);
        try {
          plugin.destroy();
        } catch (RuntimeException e) {
          log.warn("Failed to destroy plugin {}: {}", plugin.name(), e.getMessage());
        }
      }
    }
    boolean found = true;
    while (found) {
      found = false;
      for (WeaverPlugin plugin : new java.util.ArrayList<>(loadedPlugins)) {
        if (unloaded.contains(plugin.name())) {
          continue;
        }
        for (String dep : requirementsOf(plugin)) {
          if (unloaded.contains(dep) && !nameStillLoaded(dep)) {
            unloadOne(plugin, "it requires '" + dep + "', which is gone");
            unloaded.add(plugin.name());
            removed.add(plugin);
            found = true;
            break;
          }
        }
      }
    }
    return removed;
  }

  /** Whether any plugin of {@code name} is still loaded. A requirement names a plugin, not one instance of it. */
  private boolean nameStillLoaded(String name) {
    for (WeaverPlugin plugin : loadedPlugins) {
      if (name.equals(plugin.name())) {
        return true;
      }
    }
    return false;
  }

  /** Drop {@code plugin} from the loaded set and tear down everything it registered. */
  private void unloadOne(WeaverPlugin plugin, String reason) {
    loadedPlugins.remove(plugin);
    requirements.remove(plugin);
    com.github.cc11001100.weavergirl.core.registry.DefaultInterceptorRegistry.untrack(plugin);
    unregisterOwnedBy(plugin, registry);
    withdrawServices(plugin);
    try {
      plugin.destroy();
    } catch (RuntimeException e) {
      log.warn("Failed to destroy plugin {}: {}", plugin.name(), e.getMessage());
    }
    for (LoadedJar loaded : loadedJars.values()) {
      loaded.plugins.remove(plugin);
    }
    log.info("Plugin {} unloaded because {}", plugin.name(), reason);
  }

  /** The context {@code plugin} is initialized with, remembered so its services can be withdrawn later. */
  private DefaultPluginContext contextFor(
      WeaverPlugin plugin, InterceptorRegistry registry, Map<String, String> config) {
    DefaultPluginContext context =
        new DefaultPluginContext(registryFor(plugin, registry), config, plugin.name());
    contexts.put(plugin, context);
    return context;
  }

  /**
   * Withdraw the services {@code plugin} published. A plugin that published none has nothing to remove.
   * Public so the plugin manager can do it when it unloads a plugin without going through this loader.
   */
  public void withdrawServices(WeaverPlugin plugin) {
    DefaultPluginContext context = contexts.remove(plugin);
    if (context != null) {
      context.withdrawServices();
    }
  }

  /** The ownership record for the plugins this loader activated. */
  public PluginOwnership ownership() {
    return ownership;
  }

  /** The registry a plugin should be handed, so what it registers is attributed to it. */
  private InterceptorRegistry registryFor(WeaverPlugin plugin, InterceptorRegistry registry) {
    return ownership.recording(plugin, registry);
  }

  /**
   * Plugins discovered but deliberately not activated, because {@link WeaverPlugin#isEnabled}
   * returned false. Kept apart from {@link #loadedPlugins} so a capability that is simply switched
   * off is not reported as a plugin that failed to load.
   */
  private final List<WeaverPlugin> inactivePlugins = new java.util.concurrent.CopyOnWriteArrayList<>();
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
    // ServiceLoader.next() wraps a provider that fails to instantiate in a ServiceConfigurationError
    // and aborts the whole scan, which would hide every plugin after the broken one. discover()
    // surfaces the cause and keeps going.
    this.registry = registry;
    List<WeaverPlugin> discovered = discover(classLoader);

    // Parse the capability selection. enabledPlugins (when set) is an allowlist and wins over
    // disabledPlugins, matching PluginJarSelector so the SPI path and the jar path agree.
    java.util.Set<String> disabledPlugins = PluginJarSelector.parse(
        config == null ? null : config.get("disabledPlugins"));
    java.util.Set<String> enabledPlugins = PluginJarSelector.parse(
        config == null ? null : config.get("enabledPlugins"));

    // Filter out capabilities that are not selected
    List<WeaverPlugin> enabled = new ArrayList<>();
    for (WeaverPlugin plugin : discovered) {
      if (!enabledPlugins.isEmpty() && !enabledPlugins.contains(plugin.name())) {
        log.info("Plugin {} is not in enabledPlugins — skipping", plugin.name());
        AgentStatus.getInstance()
            .recordPluginStatus(plugin.name(), false, "not in enabledPlugins");
      } else if (enabledPlugins.isEmpty() && disabledPlugins.contains(plugin.name())) {
        log.info("Plugin {} is disabled via config — skipping", plugin.name());
        AgentStatus.getInstance().recordPluginStatus(plugin.name(), false, "disabled via config");
      } else {
        enabled.add(plugin);
      }
    }

    return activate(enabled, registry, config);
  }

  /**
   * Sort the plugins by their declared dependencies and run each one through init, the enable check
   * and interceptor registration. Shared by the SPI path and the directory path so both honour
   * {@code depends()} the same way.
   */
  private List<WeaverPlugin> activate(
      List<WeaverPlugin> enabled, InterceptorRegistry registry, Map<String, String> config) {
    PluginDependencyResolver resolver = new PluginDependencyResolver();
    // Hard requirements named in the jar metadata hold a plugin that never overrides requires(). The method
    // still wins where a plugin states both; the metadata only adds to it.
    java.util.Map<WeaverPlugin, java.util.List<String>> declared = new java.util.IdentityHashMap<>();
    for (WeaverPlugin plugin : enabled) {
      PluginMetadata metadata = metadataFor(plugin);
      if (metadata != null && !metadata.getRequires().isEmpty()) {
        declared.put(plugin, metadata.getRequires());
      }
    }
    List<WeaverPlugin> sorted = resolver.resolve(enabled, declared);
    for (WeaverPlugin plugin : sorted) {
      java.util.List<String> required = new java.util.ArrayList<>();
      for (String dep : plugin.requires()) {
        required.add(dep);
      }
      java.util.List<String> fromMetadata = declared.get(plugin);
      if (fromMetadata != null) {
        for (String dep : fromMetadata) {
          if (!required.contains(dep)) {
            required.add(dep);
          }
        }
      }
      if (!required.isEmpty()) {
        requirements.put(plugin, required);
      }
    }
    // A plugin that requires something not loaded is left out of the sort entirely. It is inactive rather
    // than failed: nothing threw, the operator asked for a plugin that cannot run, and it registered nothing.
    for (java.util.Map.Entry<WeaverPlugin, String> left : resolver.unsatisfied().entrySet()) {
      inactivePlugins.add(left.getKey());
      AgentStatus.getInstance()
          .recordPluginStatus(
              left.getKey().name(), false, "requires " + left.getValue() + ", which is not loaded");
    }
    // A plugin that depends on another sees the packages that one exports, and nothing else it defines.
    // Wired before any plugin initializes, so a service interface published by a dependency is loadable
    // from the first line of init(). Already loaded plugins are included: a directory rescan activates only
    // the jars that changed, and a plugin loaded earlier still has to be pointed at the new class loader a
    // replaced dependency jar now exports through.
    java.util.List<WeaverPlugin> wired = new java.util.ArrayList<>(loadedPlugins);
    wired.addAll(sorted);
    shareExportedPackages(wired);

    // P99: parallelize plugin initialization when there are enough plugins to justify it.
    // We still respect dependency order by only parallelizing plugins within the same
    // dependency level (plugins that don't depend on each other).
    // Written from the loader threads under parallel loading, so it has to tolerate concurrent puts.
    java.util.Map<String, Long> perPluginMs = new java.util.concurrent.ConcurrentHashMap<>();
    if (sorted.size() > 2 && MAX_PARALLEL_PLUGIN_LOAD_THREADS > 1) {
      loadPluginsParallel(sorted, registry, config, perPluginMs);
    } else {
      loadPluginsSequential(sorted, registry, config, perPluginMs);
    }

    // Per-plugin startup breakdown (ms, descending) — surfaces which plugins
    // dominate the pluginLoad phase so a slow one can be targeted for optimization.
    // Copied first: perPluginMs is filled concurrently, and sorting a concurrent map's entry stream
    // directly sizes its sink from a size that can grow mid-stream, throwing
    // ArrayIndexOutOfBoundsException and aborting agent startup.
    java.util.List<java.util.Map.Entry<String, Long>> timing =
        new java.util.ArrayList<>(perPluginMs.entrySet());
    timing.sort(java.util.Map.Entry.<String, Long>comparingByValue().reversed());
    log.info(
        "Plugin load breakdown (ms): {}",
        timing.stream()
            .map(e -> e.getKey() + "=" + e.getValue())
            .collect(java.util.stream.Collectors.joining(", ")));

    // Summary report. A plugin that is present but switched off by its own isEnabled() check is
    // inactive, not failed — only one that threw counts as a failure. Both lists are filled from the
    // loader threads, so the report reads a snapshot rather than the live lists.
    int total = sorted.size();
    java.util.Set<WeaverPlugin> succeededSet = new java.util.HashSet<>(loadedPlugins);
    java.util.List<WeaverPlugin> inactiveSnapshot = new java.util.ArrayList<>(inactivePlugins);
    int succeeded = succeededSet.size();
    int inactive = inactiveSnapshot.size();
    int failed = total - succeeded - inactive;
    log.info(
        "Loaded {} plugins ({} succeeded, {} inactive, {} failed) out of {} discovered",
        succeeded,
        succeeded,
        inactive,
        failed,
        total);
    if (inactive > 0) {
      log.info(
          "Inactive plugins (disabled by their own isEnabled check, or missing something they require): {}",
          inactiveSnapshot.stream()
              .map(WeaverPlugin::name)
              .collect(java.util.stream.Collectors.joining(", ")));
    }
    if (failed > 0) {
      log.warn(
          "Failed plugins: {}",
          sorted.stream()
              .filter(p -> !succeededSet.contains(p) && !inactiveSnapshot.contains(p))
              .map(WeaverPlugin::name)
              .collect(java.util.stream.Collectors.joining(", ")));
    }
    // A snapshot, not a view. Callers hold on to what this returns, and it must not shrink when a later
    // destroyAll() clears the live list.
    return Collections.unmodifiableList(new ArrayList<>(loadedPlugins));
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
    // Filter by capability before any plugin class is loaded, so a disabled capability's jar is
    // never opened. enabledPlugins (when set) is an allowlist and wins over disabledPlugins.
    this.registry = registry;
    String disabled = config == null ? "" : config.getOrDefault("disabledPlugins", "");
    String enabled = config == null ? "" : config.getOrDefault("enabledPlugins", "");
    List<PluginJarScanner.PluginJar> jars =
        scanner.scanJars(pluginDir, WeaverGirl.class.getClassLoader(), disabled, enabled);
    scannedJars = jars;

    // Discover every jar's plugins first, then sort and activate them as one set. Sorting inside
    // each jar would miss a depends() that points at a plugin in a different jar, which is exactly
    // how a product plugin declares that it needs a built-in one.
    List<WeaverPlugin> discovered = new ArrayList<>();
    java.util.Map<WeaverPlugin, String> pluginJars = new java.util.IdentityHashMap<>();
    for (PluginJarScanner.PluginJar jar : jars) {
      String path = canonical(jar.file);
      long fingerprint = fingerprint(jar.file);
      LoadedJar loaded = loadedJars.get(path);
      if (loaded != null && loaded.fingerprint == fingerprint) {
        log.debug("Plugin jar {} is unchanged — skipping", jar.file.getName());
        continue;
      }
      // The same path with different content is a new version of the jar. The plugins the previous
      // version contributed are unloaded first, so the new version replaces them instead of running
      // alongside them.
      if (loaded != null) {
        unloadJar(path, "its jar was replaced");
      }
      // A jar that declares a checksum is refused when the bytes do not match, before any of its
      // classes load. Jars that declare none (every built-in one) pass straight through.
      PluginVerifier.VerificationResult verification = new PluginVerifier().verify(path);
      if (!verification.isValid()) {
        log.error("Plugin jar {} failed verification and will not be loaded: {}", jar.file.getName(), verification.getError());
        // Nothing from this jar was loaded, but the scanner already opened a class loader for it.
        // Left open it pins the jar file, so a corrected copy could not replace it on disk.
        try {
          jar.classLoader.close();
        } catch (java.io.IOException e) {
          log.warn("Failed to close class loader of rejected plugin jar {}: {}", jar.file.getName(), e.getMessage());
        }
        continue;
      }
      for (WeaverPlugin plugin : discover(jar.classLoader)) {
        discovered.add(plugin);
        pluginJars.put(plugin, path);
      }
      loadedJars.put(path, new LoadedJar(fingerprint));
    }

    // A jar that says which contract it was built for is refused when this agent is older than
    // that. Jars without the declaration (every built-in one) load exactly as before. The checker is
    // the loader's, so it also remembers what an earlier call already loaded.
    java.util.Set<String> available = new java.util.HashSet<>();
    for (WeaverPlugin plugin : discovered) {
      available.add(plugin.name());
    }
    // Each plugin is judged on its own. The jar metadata carries the contract version, but its name
    // is the jar's, and one jar can hold several plugins that do not share it. Checking the metadata
    // once and reusing the verdict would record that one name as loaded, then reject every later
    // plugin in the jar as a duplicate of it.
    List<WeaverPlugin> compatible = new ArrayList<>();
    for (WeaverPlugin plugin : discovered) {
      PluginMetadata metadata = metadataFor(jars, plugin);
      if (metadata != null && metadata.getApiVersion() != null) {
        PluginCompatibilityChecker.CompatibilityReport report =
            compatibility.check(metadata.withName(plugin.name()), available);
        if (!report.isCompatible()) {
          log.error(
              "Plugin {} is incompatible and will not be loaded: {}",
              plugin.name(),
              report.getErrors());
          AgentStatus.getInstance()
              .recordPluginStatus(plugin.name(), false, "incompatible plugin contract");
          continue;
        }
      }
      compatible.add(plugin);
    }

    List<WeaverPlugin> activated = activate(compatible, registry, config);
    // Remember which jar each plugin came from, but only for the ones that actually loaded. A plugin
    // that failed to load must not keep its jar marked as loaded, or a later scan could never retry it.
    for (WeaverPlugin plugin : activated) {
      String path = pluginJars.get(plugin);
      if (path != null) {
        LoadedJar loaded = loadedJars.get(path);
        if (loaded != null) {
          loaded.plugins.add(plugin);
        }
      }
    }
    return activated;
  }

  /**
   * A checksum of the jar's bytes. Size and modification time are not enough: a jar overwritten inside
   * the same second can keep both, and the new version would then be treated as the one already loaded.
   */
  private static long fingerprint(java.io.File file) {
    java.util.zip.CRC32 crc = new java.util.zip.CRC32();
    byte[] buffer = new byte[8192];
    try (java.io.InputStream in = new java.io.FileInputStream(file)) {
      int read;
      while ((read = in.read(buffer)) != -1) {
        crc.update(buffer, 0, read);
      }
    } catch (java.io.IOException e) {
      // Unreadable now, but it may be readable on the next scan. A fingerprint nothing else can produce
      // keeps the jar from being remembered as loaded.
      return System.nanoTime();
    }
    return crc.getValue();
  }

  /**
   * Unload the plugins that came from jars no longer in {@code pluginDir}. A jar deleted from a watched
   * directory takes its plugins with it: their interceptors are unregistered and {@code destroy()} runs.
   *
   * @return the plugins that were removed
   */
  public List<WeaverPlugin> unloadMissingJars(String pluginDir) {
    java.util.Set<String> present = new java.util.HashSet<>();
    java.io.File dir = new java.io.File(pluginDir);
    java.io.File[] files = dir.listFiles(file -> file.isFile() && file.getName().endsWith(".jar"));
    if (files != null) {
      for (java.io.File file : files) {
        present.add(canonical(file));
      }
    }
    List<WeaverPlugin> removed = new ArrayList<>();
    for (String path : new java.util.ArrayList<>(loadedJars.keySet())) {
      if (!present.contains(path)) {
        removed.addAll(unloadJar(path, "its jar was removed"));
      }
    }
    return removed;
  }

  /**
   * Unload every plugin {@code path} contributed: drop them from the loaded set, unregister the
   * interceptors each one registered, and run {@code destroy()}. The jar is no longer remembered, so a
   * later scan loads whatever is now at that path.
   */
  private List<WeaverPlugin> unloadJar(String path, String reason) {
    LoadedJar loaded = loadedJars.remove(path);
    List<WeaverPlugin> removed = new ArrayList<>();
    if (loaded == null) {
      return removed;
    }
    for (WeaverPlugin plugin : loaded.plugins) {
      unloadOne(plugin, reason);
      removed.add(plugin);
    }
    // A plugin in another jar that required one of these can no longer run. It is removed here, before the
    // directory scan loads the replacement, so the replacement is what satisfies the requirement afterwards.
    removed.addAll(unloadDependentsOf(removed));
    return removed;
  }

  /** Remove the interceptors {@code plugin} registered, however it registered them. */
  private void unregisterOwnedBy(WeaverPlugin plugin, InterceptorRegistry registry) {
    if (registry == null) {
      return;
    }
    for (com.github.cc11001100.weavergirl.api.interceptor.InterceptorDefinition def :
        new ArrayList<>(registry.getAllDefinitions())) {
      if (def.getOwner() == plugin || ownership.interceptorsOf(plugin).contains(def.getName())) {
        registry.unregister(def.getName());
        ownership.forget(plugin, def.getName());
      }
    }
  }

  /** A path that is the same file whoever asks, so a jar is recognised as already loaded. */
  private static String canonical(java.io.File file) {
    try {
      return file.getCanonicalPath();
    } catch (java.io.IOException e) {
      return file.getAbsolutePath();
    }
  }

  /** The metadata of the jar a plugin was discovered in, or {@code null} for a jar without any. */
  private PluginMetadata metadataFor(List<PluginJarScanner.PluginJar> jars, WeaverPlugin plugin) {
    ClassLoader loader = plugin.getClass().getClassLoader();
    for (PluginJarScanner.PluginJar jar : jars) {
      if (jar.classLoader == loader) {
        return jar.metadata;
      }
    }
    return null;
  }

  /**
   * Discover the plugins a class loader offers, without activating any of them. Package-visible so a test
   * can supply a fixed set instead of whatever the classpath happens to contain.
   */
  List<WeaverPlugin> discover(ClassLoader classLoader) {
    ServiceLoader<WeaverPlugin> serviceLoader = ServiceLoader.load(WeaverPlugin.class, classLoader);
    List<WeaverPlugin> discovered = new ArrayList<>();
    java.util.Iterator<WeaverPlugin> providers = serviceLoader.iterator();
    while (providers.hasNext()) {
      try {
        discovered.add(providers.next());
      } catch (Throwable t) {
        Throwable cause = t.getCause() != null ? t.getCause() : t;
        log.error("Plugin provider could not be instantiated: {}", cause.toString());
      }
    }
    return discovered;
  }

  /**
   * Version of the running agent, from the jar manifest. Falls back to the development version when
   * the agent is not running from its packaged jar, which is the case in tests.
   */
  private static String agentVersion() {
    Package pkg = PluginLoader.class.getPackage();
    String version = pkg != null ? pkg.getImplementationVersion() : null;
    return version != null ? version : "1.0.0-SNAPSHOT";
  }

  /** Destroy all loaded plugins in reverse order. Should be called during agent shutdown. */
  public void destroyAll() {
    // Destroy in reverse order of loading
    for (int i = loadedPlugins.size() - 1; i >= 0; i--) {
      WeaverPlugin plugin = loadedPlugins.get(i);
      try {
        plugin.destroy();
        com.github.cc11001100.weavergirl.core.registry.DefaultInterceptorRegistry.untrack(plugin);
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
        PluginContext context = contextFor(plugin, registry, config);
        // The registry a plugin was handed may not be the one it registers on: it can keep the one
        // from its context and register through it later, or reach the agent's registry directly.
        // Recording the plugin for the thread credits whichever registry the registration lands on.
        com.github.cc11001100.weavergirl.core.registry.DefaultInterceptorRegistry.registeringAs(
            plugin,
            () -> {
              plugin.init(context);
            });
        if (!plugin.isEnabled(context)) {
          log.info(
              "Plugin {} is disabled via isEnabled() check — skipping interceptors", plugin.name());
          inactivePlugins.add(plugin);
          AgentStatus.getInstance()
              .recordPluginStatus(plugin.name(), false, "disabled via isEnabled()");
          perPluginMs.put(plugin.name(), (System.nanoTime() - pluginStart) / 1_000_000L);
          continue;
        }
        if (!instrumentedLibraryPresent(plugin, context)) {
          inactivePlugins.add(plugin);
          perPluginMs.put(plugin.name(), (System.nanoTime() - pluginStart) / 1_000_000L);
          continue;
        }
        com.github.cc11001100.weavergirl.core.registry.DefaultInterceptorRegistry.registeringAs(
            plugin, () -> plugin.registerInterceptors(registryFor(plugin, registry)));
        loadedPlugins.add(plugin);
        // So a registration the plugin makes later, from a thread of its own, is still credited to it.
        com.github.cc11001100.weavergirl.core.registry.DefaultInterceptorRegistry.track(plugin);
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
      // Several plugins may share a name, and a dependency declared on that name is only satisfied
      // once all of them are loaded. Keeping one per name would schedule a dependent alongside the
      // copies the map forgot.
      java.util.Map<String, java.util.List<WeaverPlugin>> nameMap = new java.util.HashMap<>();
      for (WeaverPlugin p : sorted) {
        nameMap.computeIfAbsent(p.name(), k -> new ArrayList<>()).add(p);
      }

      for (WeaverPlugin plugin : sorted) {
        int maxDepLevel = -1;
        for (String dep : getPluginDependencies(plugin)) {
          java.util.List<WeaverPlugin> depPlugins = nameMap.get(dep);
          // A dependency that is not in this batch has no level. It was already reported as missing
          // by the resolver, and reading a null level here would throw and abort every plugin still
          // waiting to load.
          if (depPlugins == null) {
            continue;
          }
          for (WeaverPlugin depPlugin : depPlugins) {
            Integer depLevel = levelMap.get(depPlugin);
            if (depLevel != null) {
              maxDepLevel = Math.max(maxDepLevel, depLevel);
            }
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

  /**
   * Let every plugin load the packages its dependencies export. The export is declared in the jar metadata,
   * so it is known before any plugin class runs, and it is granted only to a plugin that names the exporter
   * in {@code depends()}. A plugin loaded from the application class path has no class loader of its own and
   * can see everything already, so there is nothing to wire.
   */
  private void shareExportedPackages(List<WeaverPlugin> plugins) {
    java.util.Map<String, PluginClassLoader> exporters = new java.util.HashMap<>();
    for (WeaverPlugin plugin : plugins) {
      PluginMetadata metadata = metadataFor(plugin);
      if (metadata == null || metadata.getExports().isEmpty()) {
        continue;
      }
      ClassLoader loader = plugin.getClass().getClassLoader();
      if (loader instanceof PluginClassLoader) {
        exporters.put(plugin.name(), (PluginClassLoader) loader);
      }
    }
    for (WeaverPlugin plugin : plugins) {
      ClassLoader loader = plugin.getClass().getClassLoader();
      if (!(loader instanceof PluginClassLoader)) {
        continue;
      }
      PluginClassLoader consumer = (PluginClassLoader) loader;
      for (String dependency : getPluginDependencies(plugin)) {
        PluginClassLoader exporter = exporters.get(dependency);
        PluginMetadata metadata = metadataOf(plugins, dependency);
        if (exporter == null || metadata == null) {
          continue;
        }
        for (int i = 0; i < metadata.getExports().size(); i++) {
          consumer.addExport(metadata.getExports().get(i), exporter, metadata.isExportedClass(i));
        }
      }
    }
  }

  /** The metadata of the plugin named {@code name}, or {@code null} when it declared none. */
  private PluginMetadata metadataOf(List<WeaverPlugin> plugins, String name) {
    for (WeaverPlugin plugin : plugins) {
      if (name.equals(plugin.name())) {
        return metadataFor(plugin);
      }
    }
    return null;
  }

  /** The metadata of the jar {@code plugin} was loaded from, or {@code null} when it declared none. */
  private PluginMetadata metadataFor(WeaverPlugin plugin) {
    for (PluginJarScanner.PluginJar jar : scannedJars) {
      if (jar.classLoader == plugin.getClass().getClassLoader()) {
        return jar.metadata;
      }
    }
    return null;
  }

  /**
   * The names this plugin declares it depends on. Read through the interface: {@code depends()} returns
   * a {@code String[]}, and treating the result as a {@link java.util.Collection} matches nothing, so
   * every plugin looked dependency-free and the parallel loader ran a dependent in the same wave as the
   * plugin it was waiting for.
   *
   * <p>A hard requirement orders initialization just as strictly. Leaving it out put a plugin that {@code
   * requires()} another in the same wave, so its {@code init} ran before the plugin it cannot run without had
   * initialized.
   */
  private java.util.Set<String> getPluginDependencies(WeaverPlugin plugin) {
    java.util.Set<String> deps = new java.util.HashSet<>();
    addDependencies(deps, plugin.depends());
    addDependencies(deps, plugin.requires());
    // A requirement that lives only in the jar metadata never appears on the method, but it orders
    // initialization just the same. The list was recorded when the plugin was accepted for loading.
    for (String dep : requirementsOf(plugin)) {
      deps.add(dep);
    }
    return deps;
  }

  /**
   * Whether a library {@code plugin} instruments is on the classpath. A plugin that names none always
   * passes, and one that names several passes when any one of them is present — an application that uses
   * only the second of two clients the plugin covers still has something for it to instrument. The class
   * file is looked up through the plugin's own loader and never loaded: loading it here would define it
   * before the transformer is installed, so the interceptor registered for it could not be woven in.
   */
  private boolean instrumentedLibraryPresent(WeaverPlugin plugin, PluginContext context) {
    String[] libraries = plugin.instrumentedLibraries();
    if (libraries == null || libraries.length == 0) {
      return true;
    }
    ClassLoader loader = plugin.getClass().getClassLoader();
    StringBuilder named = new StringBuilder();
    for (String library : libraries) {
      if (library == null || library.trim().isEmpty()) {
        continue;
      }
      if (context.classPresent(loader, library)) {
        return true;
      }
      if (named.length() > 0) {
        named.append(", ");
      }
      named.append(library);
    }
    // Every entry was null or blank, which declares nothing.
    if (named.length() == 0) {
      return true;
    }
    log.info(
        "Plugin {} left inactive: none of [{}] is on the classpath, so there is nothing for it to instrument",
        plugin.name(),
        named);
    AgentStatus.getInstance()
        .recordPluginStatus(plugin.name(), false, named + " not on the classpath");
    return false;
  }

  /** Tell listeners a plugin initialized but registered nothing. */
  private void publishInactive(WeaverPlugin plugin) {
    try {
      InterceptorEventPublisher.getInstance()
          .publish(
              LifecycleEvents.plugin(
                  LifecycleEvents.PHASE_INIT, plugin.name(), false, "plugin inactive"));
    } catch (Throwable t) {
      log.debug("Lifecycle event publish failed: {}", t.getMessage());
    }
  }

  /** Add the names a plugin declared, dropping the nulls an array is free to contain. */
  private static void addDependencies(java.util.Set<String> deps, String[] declared) {
    if (declared == null) {
      return;
    }
    for (String dep : declared) {
      if (dep != null) {
        deps.add(dep);
      }
    }
  }

  private void loadPluginSync(
      WeaverPlugin plugin,
      InterceptorRegistry registry,
      Map<String, String> config,
      java.util.Map<String, Long> perPluginMs) {
    long pluginStart = System.nanoTime();
    boolean initOk = false;
    boolean registerOk = false;
    try {
      PluginContext context = contextFor(plugin, registry, config);
      com.github.cc11001100.weavergirl.core.registry.DefaultInterceptorRegistry.registeringAs(
          plugin,
          () -> {
            plugin.init(context);
          });
      initOk = true;
      if (!plugin.isEnabled(context)) {
        log.info(
            "Plugin {} is disabled via isEnabled() check — skipping interceptors", plugin.name());
        inactivePlugins.add(plugin);
        AgentStatus.getInstance()
            .recordPluginStatus(plugin.name(), false, "disabled via isEnabled()");
        perPluginMs.put(plugin.name(), (System.nanoTime() - pluginStart) / 1_000_000L);
        publishInactive(plugin);
        return;
      }
      if (!instrumentedLibraryPresent(plugin, context)) {
        inactivePlugins.add(plugin);
        perPluginMs.put(plugin.name(), (System.nanoTime() - pluginStart) / 1_000_000L);
        publishInactive(plugin);
        return;
      }
      com.github.cc11001100.weavergirl.core.registry.DefaultInterceptorRegistry.registeringAs(
          plugin, () -> plugin.registerInterceptors(registryFor(plugin, registry)));
      registerOk = true;
      loadedPlugins.add(plugin);
      // So a registration the plugin makes later, from a thread of its own, is still credited to it.
      com.github.cc11001100.weavergirl.core.registry.DefaultInterceptorRegistry.track(plugin);
      AgentStatus.getInstance().recordPluginStatus(plugin.name(), true, null);
      log.info("Plugin {} loaded successfully", plugin.name());
    } catch (Throwable e) {
      String errorMsg = e.getClass().getSimpleName() + ": " + e.getMessage();
      AgentStatus.getInstance().recordPluginStatus(plugin.name(), false, errorMsg);
      log.error("Failed to load plugin {}: {}", plugin.name(), e.getMessage(), e);
    }
    try {
      InterceptorEventPublisher.getInstance()
          .publish(
              LifecycleEvents.plugin(
                  LifecycleEvents.PHASE_REGISTER_INTERCEPTORS,
                  plugin.name(),
                  registerOk,
                  initOk ? "" : "init-failed"));
    } catch (Throwable t) {
      log.debug("Lifecycle event publish failed: {}", t.getMessage());
    }
    perPluginMs.put(plugin.name(), (System.nanoTime() - pluginStart) / 1_000_000L);
  }
}
