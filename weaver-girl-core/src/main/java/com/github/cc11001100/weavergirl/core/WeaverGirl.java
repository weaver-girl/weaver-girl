package com.github.cc11001100.weavergirl.core;

import com.github.cc11001100.weavergirl.api.ValidationUtils;
import com.github.cc11001100.weavergirl.api.interceptor.Interceptor;
import com.github.cc11001100.weavergirl.api.interceptor.InterceptorDefinition;
import com.github.cc11001100.weavergirl.api.interceptor.MethodInvocation;
import com.github.cc11001100.weavergirl.api.matcher.ClassMatcher;
import com.github.cc11001100.weavergirl.api.matcher.MethodMatcher;
import com.github.cc11001100.weavergirl.api.pointcut.Pointcut;
import com.github.cc11001100.weavergirl.api.pointcut.PointcutExpression;
import com.github.cc11001100.weavergirl.api.pointcut.PointcutParser;
import com.github.cc11001100.weavergirl.api.plugin.PluginManagerHolder;
import com.github.cc11001100.weavergirl.api.plugin.WeaverPlugin;
import com.github.cc11001100.weavergirl.api.registry.InterceptorRegistry;
import com.github.cc11001100.weavergirl.core.config.DefaultDynamicConfigManager;
import com.github.cc11001100.weavergirl.core.config.WeaverConfig;
import com.github.cc11001100.weavergirl.core.management.AgentMonitor;
import com.github.cc11001100.weavergirl.api.introduction.IntroductionDefinition;
import com.github.cc11001100.weavergirl.api.introduction.IntroductionRegistry;
import com.github.cc11001100.weavergirl.core.plugin.DefaultPluginManager;
import com.github.cc11001100.weavergirl.core.plugin.PluginLoader;
import com.github.cc11001100.weavergirl.core.registry.DefaultInterceptorRegistry;
import com.github.cc11001100.weavergirl.core.graceful.GracefulDegradationManager;
import com.github.cc11001100.weavergirl.core.sampling.SamplingController;
import com.github.cc11001100.weavergirl.core.status.AgentStatus;
import com.github.cc11001100.weavergirl.core.transformer.WeaverTransformer;
import com.github.cc11001100.weavergirl.api.event.InterceptorEventPublisher;
import com.github.cc11001100.weavergirl.api.event.LifecycleEvents;
import java.lang.instrument.Instrumentation;
import java.util.Collections;
import java.util.List;
import java.util.function.Consumer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Main entry point for the weaver-girl framework. Provides both a fluent programmatic API and
 * internal bootstrap logic.
 */
public class WeaverGirl {

  private static final Logger log = LoggerFactory.getLogger(WeaverGirl.class);

  private final InterceptorRegistry registry;
  private final PluginLoader pluginLoader;
  private com.github.cc11001100.weavergirl.core.plugin.PluginDirectoryWatcher pluginDirectoryWatcher;
  /**
   * The adaptive-sampling monitor. It was written but never started, which made the documented
   * "adaptive sampling" behaviour a no-op: nothing ever adjusted the rate under load.
   */
  private com.github.cc11001100.weavergirl.core.sampling.SamplingMonitor samplingMonitor;

  /**
   * Periodic state snapshot, or null when {@code stateFile} is not configured. The class existed
   * with no production caller, so the documented "state persistence" capability never ran.
   */
  private com.github.cc11001100.weavergirl.core.persistence.AgentStatePersister statePersister;
  private final IntroductionRegistry introductionRegistry;
  private Instrumentation instrumentation;
  private WeaverTransformer transformer;

  private final DefaultDynamicConfigManager dynamicConfigManager;
  private volatile DefaultPluginManager pluginManager;
  private volatile boolean pluginManagerInitialized = false;
  private final Object pluginManagerLock = new Object();

  private WeaverGirl() {
    this.registry = new DefaultInterceptorRegistry();
    this.pluginLoader = new PluginLoader();
    this.dynamicConfigManager = new DefaultDynamicConfigManager();
    this.introductionRegistry = new IntroductionRegistry();
  }

  // P99 lazy component initialization -------------------------------------------
  void initPluginManager() {
    if (!pluginManagerInitialized) {
      synchronized (pluginManagerLock) {
        if (!pluginManagerInitialized) {
          pluginManager = new DefaultPluginManager(pluginLoader, registry);
          pluginManagerInitialized = true;
        }
      }
    }
  }

  // ------------------------------------------------------------------------------

  /**
   * Bootstrap the agent — called from premain/agentmain. Loads plugins via SPI and installs the
   * transformer.
   *
   * @param instrumentation the JVM Instrumentation instance
   * @param config agent configuration properties (may be empty)
   */
  public static WeaverGirl bootstrap(
      Instrumentation instrumentation, java.util.Map<String, String> config) {
    return bootstrap(instrumentation, config, null);
  }

  /**
   * Bootstrap the agent — called from premain/agentmain. Loads plugins via SPI and installs the
   * transformer, applying excludedClasses from WeaverConfig to the transformer's ignore matcher.
   *
   * @param instrumentation the JVM Instrumentation instance
   * @param config agent configuration properties (may be empty)
   * @param weaverConfig YAML configuration (may be null)
   */
  public static WeaverGirl bootstrap(
      Instrumentation instrumentation,
      java.util.Map<String, String> config,
      WeaverConfig weaverConfig) {
    // Measure total bootstrap + per-phase timing (see StartupMetrics / AgentMonitor).
    com.github.cc11001100.weavergirl.core.management.StartupMetrics.begin();
    try {
      ValidationUtils.requireNonNull(instrumentation, "instrumentation");
      // Published before plugins load, so the taint plugin can instrument JDK classes from its init.
      // Taint tracking is that plugin's job (taint=true); installing it here would instrument String on
      // every launch whether or not anything reads the taint.
      AgentInstrumentation.set(instrumentation);
      log.info("WeaverGirl agent starting...");
      WeaverGirl weaverGirl = new WeaverGirl();
      weaverGirl.instrumentation = instrumentation;

      InterceptorHolder.setRegistry(weaverGirl.registry);
      // Wire registry reload hook so InterceptorHolder invalidates its snapshot
      // whenever any interceptor is registered/unregistered at runtime.
      if (weaverGirl.registry instanceof DefaultInterceptorRegistry) {
        ((DefaultInterceptorRegistry) weaverGirl.registry)
            .addReloadHook(() -> InterceptorHolder.invalidateSnapshot());
      }

      java.util.Map<String, String> pluginConfig = mergePluginConfig(config, weaverConfig);

      // Before any plugin runs init(): a plugin registers its introduction there, and it reaches
      // the engine through this registrar. Publishing after the load would make every introduction
      // registered in init() fall into the "no registrar published" branch.
      publishIntroductionRegistrar(weaverGirl);

      long pluginLoadStart = System.nanoTime();
      // The loader that defined this class, not the thread context loader. Under the isolated
      // AgentClassLoader the context loader is the system loader, which cannot see the capability
      // jars — only the isolated loader, which this class was loaded from, has them.
      weaverGirl.pluginLoader.loadPlugins(
          WeaverGirl.class.getClassLoader(), weaverGirl.registry, pluginConfig);

      // Drop-in plugins (agent arg plugins=<dir>) have to be loaded here, before the transformer is
      // installed. Loading them afterwards registers their interceptors into a transformer that is
      // already installed, and at premain nothing revisits classes loaded after that, so the plugin
      // would be reported as loaded while weaving nothing. The same disabledPlugins/enabledPlugins
      // selection applies, so a capability jar dropped in the directory is still gated.
      String pluginDir = pluginConfig.get("plugins");
      if (pluginDir != null && !pluginDir.isEmpty()) {
        weaverGirl.pluginLoader.loadPluginsFromDirectory(
            pluginDir, weaverGirl.registry, pluginConfig);
        log.info("Plugins loaded from directory: {}", pluginDir);
        // A jar copied in after startup is loaded too, without restarting the process. The loader
        // remembers which jars it already loaded, so the rescan only activates the new ones.
        weaverGirl.pluginDirectoryWatcher =
            new com.github.cc11001100.weavergirl.core.plugin.PluginDirectoryWatcher(
                java.nio.file.Paths.get(pluginDir),
                () -> weaverGirl.reloadPluginDirectory(pluginDir, pluginConfig));
        weaverGirl.pluginDirectoryWatcher.start();
      }

      com.github.cc11001100.weavergirl.core.management.StartupMetrics.recordPhase(
          "pluginLoad", System.nanoTime() - pluginLoadStart);

      // P99: warm up classloader caches before transformer installation to avoid
      // repeated delegation lookups during the first wave of class transformations.
      long warmupStart = System.nanoTime();
      try {
        ClassLoader target = WeaverGirl.class.getClassLoader();
        // Touch common package prefixes that plugins are likely to match.
        String[] prefixes = {
          "java.",
          "javax.",
          "com.",
          "org.",
          "io.",
          "sun.",
          "jdk."
        };
        for (String prefix : prefixes) {
          if (target.getResource(prefix.replace('.', '/') + "/") != null) {
            // Found the package root; the classloader will cache the lookup.
          }
        }
      } catch (Exception e) {
        log.debug("Classloader warmup skipped: {}", e.getMessage());
      }
      com.github.cc11001100.weavergirl.core.management.StartupMetrics.recordPhase(
          "classloaderWarmup", System.nanoTime() - warmupStart);

      long transformerStart = System.nanoTime();
      WeaverTransformer transformer = new WeaverTransformer(weaverGirl.registry);
      // Introductions are installed from this registry, and a plugin registers one in init(), which
      // runs before the first install — so it is wired here rather than at the register call.
      transformer.setIntroductionRegistry(weaverGirl.introductionRegistry);

      if (weaverConfig != null && weaverConfig.getExcludedClasses() != null) {
        transformer.setExcludedClassPatterns(weaverConfig.getExcludedClasses());
      }

      if (weaverConfig != null) {
        transformer.setWeaverConfig(weaverConfig);
      }

      // eagerRetransform=false: at premain nothing the plugins target is loaded yet,
      // so retransforming already-loaded classes is pure startup waste (it scans
      // ~thousands of JDK classes through hasSuperType matchers). App classes are
      // transformed as they load. For dynamic attach (agentmain) the caller invokes
      // retransformLoadedClasses() explicitly, so this is correct there too.
      transformer.install(instrumentation, false);
      weaverGirl.transformer = transformer;
      com.github.cc11001100.weavergirl.core.management.StartupMetrics.recordPhase(
          "transformerInstall", System.nanoTime() - transformerStart);

      // Wire introduction registry into advice so introductions are resolved at runtime
      com.github.cc11001100.weavergirl.core.introduction.IntroductionAdvice.setIntroductionRegistry(
          weaverGirl.introductionRegistry);

      // Apply configuration to core components
      applyCoreConfig(pluginConfig);

      // Adaptive sampling: the monitor is what actually raises the sampling rate under load
      // (SamplingController.adaptRate had no caller before this). Off only when adaptiveAdapt=false.
      if (!"false".equalsIgnoreCase(pluginConfig.get("adaptiveSampling"))) {
        weaverGirl.samplingMonitor =
            new com.github.cc11001100.weavergirl.core.sampling.SamplingMonitor(
                SamplingController.getInstance());
        String interval = pluginConfig.get("samplingCheckIntervalSeconds");
        if (interval != null) {
          try {
            weaverGirl.samplingMonitor.setCheckIntervalSeconds(Long.parseLong(interval.trim()));
          } catch (NumberFormatException notANumber) {
            log.warn("Invalid samplingCheckIntervalSeconds '{}', using the default", interval);
          }
        }
        weaverGirl.samplingMonitor.start();
      }

      // Span export (otlpEndpoint / spanExport) and the version check (updateCheckEndpoint) are
      // runtime plugins. They start themselves when their config key is set and stop themselves in
      // destroy(), so starting them here too would run a second exporter and a second checker.

      // State persistence: opt-in through stateFile, which names the snapshot directory. The
      // snapshot records versions, counts and sampling rate so an operator can see what a
      // previous run looked like; it is not replayed into the new run.
      String stateFile = pluginConfig.get("stateFile");
      if (stateFile != null && !stateFile.trim().isEmpty()) {
        weaverGirl.startStatePersistence(
            stateFile.trim(), parsePositiveLong(pluginConfig.get("statePersistIntervalMs"), 60_000L));
      }

      // Initialize DynamicConfigManager with initial config and runtime listener
      weaverGirl.dynamicConfigManager.loadFromSource(pluginConfig, "bootstrap");
      weaverGirl.dynamicConfigManager.addListener(weaverGirl.new DynamicCoreConfigListener());
      weaverGirl.dynamicConfigManager.snapshot("initial-bootstrap");

      // Periodic registry refresh (refreshIntervalSeconds) is the dynamic-refresh plugin, and the agent
      // status MBean is the jmx plugin. Both are loaded above with the other plugins.

      long mgmtStart = System.nanoTime();

      // The counters are collected here regardless, but the MBean is published by the jmx plugin, so
      // jmx=false leaves nothing registered. Publishing it here too meant the switch could not turn it
      // off: this runs before the plugin and claims the same object name.
      AgentMonitor monitor = AgentMonitor.getInstance();
      monitor.setInterceptorDefinitionCount(weaverGirl.registry.getAllDefinitions().size());
      monitor.setPluginCount(weaverGirl.pluginLoader.getLoadedPlugins().size());

      // P99: defer the PluginManager to first access so bootstrap does less work when it is not
      // needed immediately.
      com.github.cc11001100.weavergirl.core.management.StartupMetrics.recordPhase(
          "managementInit", System.nanoTime() - mgmtStart);

      // The management REST API (apiPort) is no longer started here. It is the management-api
      // plugin, loaded above with the other plugins and only when apiPort is set.

      log.info(
          "WeaverGirl agent started with {} interceptor definitions",
          weaverGirl.registry.getAllDefinitions().size());
      try {
        InterceptorEventPublisher.getInstance()
            .publish(
                LifecycleEvents.registry(
                    LifecycleEvents.PHASE_INIT, "bootstrap", true,
                    "interceptors=" + weaverGirl.registry.getAllDefinitions().size()
                        + ";plugins=" + weaverGirl.pluginLoader.getLoadedPlugins().size()));
      } catch (Throwable t) {
        log.debug("Lifecycle event publish failed: {}", t.getMessage());
      }
      return weaverGirl;
    } finally {
      com.github.cc11001100.weavergirl.core.management.StartupMetrics.end();
      long startupMs =
          com.github.cc11001100.weavergirl.core.management.StartupMetrics.totalMillis();
      log.info(
          "WeaverGirl agent startup took {}ms (budget {}ms)",
          startupMs,
          com.github.cc11001100.weavergirl.core.management.StartupMetrics.STARTUP_BUDGET_MS);
      // Per-phase breakdown (ms, descending) — surfaces where bootstrap time goes so it
      // can be targeted. Plugins also publish pluginLoad.<name> entries (see PluginLoader).
      java.util.Map<String, Long> phases =
          com.github.cc11001100.weavergirl.core.management.StartupMetrics.phaseMillis();
      if (!phases.isEmpty()) {
        String breakdown =
            phases.entrySet().stream()
                .sorted(java.util.Map.Entry.<String, Long>comparingByValue().reversed())
                .map(e -> e.getKey() + "=" + e.getValue())
                .collect(java.util.stream.Collectors.joining(", "));
        log.info("Startup phase breakdown (ms): {}", breakdown);
      }
      if (com.github.cc11001100.weavergirl.core.management.StartupMetrics.overBudget()) {
        log.warn(
            "Agent startup took {}ms (budget {}ms) — see P99 startup optimization",
            startupMs,
            com.github.cc11001100.weavergirl.core.management.StartupMetrics.STARTUP_BUDGET_MS);
      }
    }
  }

  /**
   * Bootstrap the agent — called from premain/agentmain. Loads plugins via SPI and installs the
   * transformer.
   */
  public static WeaverGirl bootstrap(Instrumentation instrumentation) {
    return bootstrap(instrumentation, Collections.emptyMap(), null);
  }

  /**
   * Combine the two plugin configuration sources into the flat map {@code PluginContext} reads.
   *
   * <p>YAML can scope a setting to one plugin ({@code plugins.<name>.<key>}) and carries launch
   * options for capability plugins; agent arguments are explicit for this launch and win over an
   * equal YAML key. {@code disabledPlugins}/{@code enabledPlugins} are lists in the YAML model, so
   * they are folded into the same map unless the launch already supplied them.
   *
   * @param config agent arguments, possibly null
   * @param weaverConfig parsed YAML, possibly null
   * @return a new mutable map, never null
   */
  static java.util.Map<String, String> mergePluginConfig(
      java.util.Map<String, String> config, WeaverConfig weaverConfig) {
    java.util.Map<String, String> pluginConfig = new java.util.HashMap<>();
    if (weaverConfig != null) {
      pluginConfig.putAll(weaverConfig.getPluginConfig());
    }
    if (config != null) {
      pluginConfig.putAll(config);
    }
    if (weaverConfig != null
        && weaverConfig.getDisabledPlugins() != null
        && !weaverConfig.getDisabledPlugins().isEmpty()
        && !pluginConfig.containsKey("disabledPlugins")) {
      String disabledStr = String.join(",", weaverConfig.getDisabledPlugins());
      pluginConfig.put("disabledPlugins", disabledStr);
      log.info("Disabled plugins from config: {}", disabledStr);
    }
    if (weaverConfig != null
        && weaverConfig.getEnabledPlugins() != null
        && !weaverConfig.getEnabledPlugins().isEmpty()
        && !pluginConfig.containsKey("enabledPlugins")) {
      String enabledStr = String.join(",", weaverConfig.getEnabledPlugins());
      pluginConfig.put("enabledPlugins", enabledStr);
      log.info("Enabled plugins from config (exclusive): {}", enabledStr);
    }
    return pluginConfig;
  }

  /**
   * Shutdown the agent — destroy all plugins and clean up. Should be called from a shutdown hook.
   */
  public void shutdown() {
    log.info("WeaverGirl agent shutting down...");
    // Span export and the version check stop with their plugins, inside destroyAll() below. Stopping
    // them here would only reach a copy bootstrap no longer starts.

    if (dynamicConfigManager != null) {
      dynamicConfigManager.shutdown();
    }
    // The dynamic refresh scheduler stops with its plugin, inside destroyAll() below.
    if (pluginDirectoryWatcher != null) {
      pluginDirectoryWatcher.stop();
    }
    if (samplingMonitor != null) {
      samplingMonitor.stop();
    }
    if (statePersister != null) {
      // Take one last snapshot before the counters go away, then stop the scheduler.
      statePersister.takeSnapshot();
      statePersister.stop();
    }
    pluginLoader.destroyAll();

    DefaultPluginManager localPluginManager = this.pluginManager;
    if (localPluginManager != null) {
      try {
        for (WeaverPlugin plugin : pluginLoader.getLoadedPlugins()) {
          try {
            plugin.destroy();
          } catch (Exception e) {
            log.warn("Failed to destroy plugin {}: {}", plugin.name(), e.getMessage());
          }
        }
      } finally {
        PluginManagerHolder.setInstance(null);
      }
    }

    // The agent status MBean is removed by the jmx plugin, inside destroyAll() above.
    AgentInstrumentation.set(null);
    InterceptorHolder.setRegistry(null);
    // The management API stops itself: it is a plugin, and destroyAll() above calls its destroy().
    try {
      InterceptorEventPublisher.getInstance()
          .publish(LifecycleEvents.registry(LifecycleEvents.PHASE_SHUTDOWN, "shutdown", true, null));
    } catch (Throwable t) {
      log.debug("Lifecycle event publish failed: {}", t.getMessage());
    }
    log.info("WeaverGirl agent shut down complete");
  }

  /** Create a new WeaverGirl instance for programmatic API usage. */
  public static WeaverGirl create() {
    return new WeaverGirl();
  }

  /**
   * Connect this WeaverGirl instance to the ByteBuddy transformation pipeline. Required for
   * programmatic interceptors to actually take effect.
   *
   * @param instrumentation the JVM Instrumentation instance
   * @return this WeaverGirl instance for chaining
   */
  public WeaverGirl withInstrumentation(Instrumentation instrumentation) {
    ValidationUtils.requireNonNull(instrumentation, "instrumentation");
    this.instrumentation = instrumentation;
    InterceptorHolder.setRegistry(this.registry);
    WeaverTransformer transformer = new WeaverTransformer(this.registry);
    transformer.install(instrumentation);
    this.transformer = transformer;
    return this;
  }

  /** Start a fluent interceptor definition for the given class name. */
  public InterceptBuilder intercept(String className) {
    ValidationUtils.requireNonEmpty(className, "className");
    return new InterceptBuilder(this, className);
  }

  /**
   * Start a fluent interceptor definition for classes matching a regex name pattern.
   *
   * <p>Example: interceptClassPattern("com\\.example\\..*Service")
   *
   * @param classPattern a Java regex pattern for class names
   * @return a new InterceptBuilder with pattern-based class matching
   * @since 1.2.0
   */
  public InterceptBuilder interceptClassPattern(String classPattern) {
    ValidationUtils.requireNonEmpty(classPattern, "classPattern");
    return new InterceptBuilder(this, ClassMatcher.byNamePattern(classPattern));
  }

  /**
   * Start a fluent interceptor definition for classes annotated with the given annotation.
   *
   * <p>Example: interceptAnnotated("com.example.Monitored")
   *
   * @param annotationClassName the fully-qualified annotation class name
   * @return a new InterceptBuilder with annotation-based class matching
   * @since 1.2.0
   */
  public InterceptBuilder interceptAnnotated(String annotationClassName) {
    ValidationUtils.requireNonEmpty(annotationClassName, "annotationClassName");
    return new InterceptBuilder(this, ClassMatcher.byAnnotation(annotationClassName));
  }

  /**
   * Start a fluent interceptor definition for classes extending the given superclass.
   *
   * <p>Example: interceptSubclassOf("com.example.BaseService")
   *
   * @param superClassName the fully-qualified superclass name
   * @return a new InterceptBuilder with superclass-based class matching
   * @since 1.2.0
   */
  public InterceptBuilder interceptSubclassOf(String superClassName) {
    ValidationUtils.requireNonEmpty(superClassName, "superClassName");
    return new InterceptBuilder(this, ClassMatcher.bySuperClass(superClassName));
  }

  /**
   * Start a fluent interceptor definition for classes implementing the given interface.
   *
   * <p>Example: interceptImplementing("java.io.Serializable")
   *
   * @param interfaceName the fully-qualified interface name
   * @return a new InterceptBuilder with interface-based class matching
   * @since 1.2.0
   */
  public InterceptBuilder interceptImplementing(String interfaceName) {
    ValidationUtils.requireNonEmpty(interfaceName, "interfaceName");
    return new InterceptBuilder(this, ClassMatcher.byInterface(interfaceName));
  }

  /**
   * Register an introduction (mixin) that adds an interface implementation to matching classes.
   *
   * <p>Example: introduce("com.example.Loggable", Loggable.class, loggableDelegate)
   *
   * @param classPattern a Java regex pattern for target class names
   * @param introductionType the interface type to add
   * @param delegate the delegate instance that will handle the introduced methods
   * @return the unique name assigned to this introduction
   * @since 2.0.0
   */
  public String introduce(String classPattern, Class<?> introductionType, Object delegate) {
    ValidationUtils.requireNonEmpty(classPattern, "classPattern");
    if (introductionType == null) {
      throw new IllegalArgumentException("Introduction type must not be null");
    }
    if (delegate == null) {
      throw new IllegalArgumentException("Delegate must not be null");
    }
    if (!introductionType.isInterface()) {
      throw new IllegalArgumentException("Introduction type must be an interface: " + introductionType.getName());
    }
    String name = "intro-" + classPattern + "-" + introductionType.getSimpleName();
    IntroductionDefinition def = new IntroductionDefinition(
        name,
        ClassMatcher.byNamePattern(classPattern),
        introductionType,
        delegate
    );
    introductionRegistry.register(def);
    // The installed chain does not know about a definition added after install, so a class that
    // loads from here on would never receive the interface. Reinstall when the transformer is
    // already live; at boot this happens before install and the first install picks it up.
    if (transformer != null) {
      transformer.reinstallAndRetransform();
    }
    return name;
  }

  /**
   * Start a fluent interceptor definition using a pointcut expression.
   *
   * @since 1.1.0
   */
  public ExpressionInterceptBuilder interceptExpression(String expression) {
    ValidationUtils.requireNonEmpty(expression, "expression");
    return new ExpressionInterceptBuilder(this, expression);
  }

  public InterceptorRegistry getRegistry() {
    return registry;
  }

  /**
   * Get the IntroductionRegistry for runtime introduction/mixin registration.
   *
   * @return the introduction registry instance
   * @since 2.0.0
   */
  public com.github.cc11001100.weavergirl.api.introduction.IntroductionRegistry getIntroductionRegistry() {
    return introductionRegistry;
  }

  /**
   * Get the DynamicConfigManager for runtime configuration management.
   *
   * @return the dynamic config manager instance
   */
  public com.github.cc11001100.weavergirl.api.config.DynamicConfigManager
      getDynamicConfigManager() {
    return dynamicConfigManager;
  }

  /**
   * Get the PluginManager for runtime plugin lifecycle management.
   *
   * @return the plugin manager instance, lazily initialized
   */
  public com.github.cc11001100.weavergirl.api.plugin.PluginManager getPluginManager() {
    initPluginManager();
    return pluginManager;
  }

  // --- P103: Dynamic refresh support -----------------------------------------------

  /**
   * Publish the introduction registrar to plugins. Addressed through a parent-first holder class, so
   * a plugin in a child loader reaches the same field.
   *
   * @param agent the engine whose registry backs the registrar
   */
  private static void publishIntroductionRegistrar(final WeaverGirl agent) {
    com.github.cc11001100.weavergirl.api.introduction.IntroductionRegistrarHolder.set(
        new com.github.cc11001100.weavergirl.api.introduction.IntroductionRegistrar() {
          @Override
          public String register(
              com.github.cc11001100.weavergirl.api.introduction.IntroductionDefinition definition) {
            agent.introductionRegistry.register(definition);
            // Bind the delegate by target name here, not in the transform callback: the callback
            // only runs for a type the chain actually visits, and an exclusive-name matcher that
            // runs after another chain has already shaped the type would never see it. The name is
            // what the call-time lookup uses, so binding now is enough.
            if (definition.getClassMatcher().getMatchType()
                == com.github.cc11001100.weavergirl.api.matcher.ClassMatcher.MatchType.EXACT_NAME) {
              com.github.cc11001100.weavergirl.core.introduction.IntroductionStore.bindClassName(
                  definition.getClassMatcher().getPattern(), definition.getDelegate());
            }
            if (agent.transformer != null) {
              // The installed chain predates this definition, so a class loading from now on would
              // never be given the interface.
              agent.transformer.reinstallAndRetransform();
            }
            return definition.getName();
          }

          @Override
          public boolean unregister(String name) {
            boolean removed = agent.introductionRegistry.unregister(name);
            if (agent.transformer != null) {
              agent.transformer.reinstallAndRetransform();
            }
            return removed;
          }
        });
  }

  /**
   * Load jars added to the plugin directory since the last scan, reload a jar whose content changed,
   * and unload plugins whose jar has been removed. Classes already loaded are retransformed afterwards,
   * so a plugin added or removed at runtime applies to them too and not only to classes loaded later.
   *
   * <p>The retransform goes through this engine's own transformer. The hook on {@link InterceptorHolder}
   * is only a fallback for a reload that has no transformer of its own — a plugin-side watcher reaching
   * the agent through the shared holder.
   */
  void reloadPluginDirectory(String pluginDir, java.util.Map<String, String> pluginConfig) {
    int before = pluginLoader.getLoadedPlugins().size();
    pluginLoader.loadPluginsFromDirectory(pluginDir, registry, pluginConfig);
    java.util.List<com.github.cc11001100.weavergirl.api.plugin.WeaverPlugin> removed =
        pluginLoader.unloadMissingJars(pluginDir);
    if (pluginLoader.getLoadedPlugins().size() != before || !removed.isEmpty()) {
      if (transformer != null) {
        // A newly loaded plugin's definitions were registered after install, so the matcher chain
        // has to be rebuilt before the retransform is worth anything.
        transformer.reinstallAndRetransform();
      } else {
        Runnable retransform =
            com.github.cc11001100.weavergirl.core.InterceptorHolder.getRetransformHook();
        if (retransform != null) {
          retransform.run();
        }
      }
      log.info(
          "Plugin directory changed: {} loaded, {} removed",
          pluginLoader.getLoadedPlugins().size(),
          removed.size());
    }
  }

  /**
   * Trigger a manual refresh of the interceptor registry snapshot cache. This forces {@link
   * InterceptorHolder} to rebuild its cached class→interceptors mapping on the next interception,
   * ensuring dynamic register/unregister changes take effect immediately.
   *
   * <p>Also retransforms already-loaded classes so new/removed interceptors are applied to them.
   */
  public void refresh() {
    InterceptorHolder.invalidateSnapshot();
    if (transformer != null) {
      // A definition registered since the last install needs a rebuilt matcher chain, not just a
      // retransform: the installed chain cannot see it. reinstallAndRetransform() is a no-op
      // rebuild when nothing changed.
      transformer.reinstallAndRetransform();
    }
    log.info("Interceptor registry manually refreshed");
  }

  // -------------------------------------------------------------------------------

  /**
   * Returns a list of class names that have been transformed by this agent. Useful for diagnostic
   * purposes.
   */
  public List<String> getTransformedClasses() {
    return AgentStatus.getInstance().getTransformedClasses();
  }

  /**
   * Retransform already-loaded classes that match any registered interceptor. This is needed when
   * the agent is attached dynamically via agentmain, because classes loaded before the agent
   * started would not be transformed.
   *
   * @return the number of classes that were retransformed
   */
  /**
   * Rebuild the transform chain if definitions were added or removed since the last install, then
   * retransform the already-loaded classes that now match.
   *
   * <p>This is what every reload path calls. A plain {@link #retransformLoadedClasses()} is only
   * useful when the definition set is unchanged (dynamic attach), because the chain ByteBuddy
   * installed fixes which classes it ever visits: a definition registered afterwards is invisible to
   * it even though the advice would dispatch to it.
   *
   * @return the number of classes retransformed
   */
  public int reloadAndRetransform() {
    if (transformer != null) {
      return transformer.reinstallAndRetransform();
    }
    return 0;
  }

  /** Start periodic state snapshots into {@code directory}. Package-visible for tests. */
  void startStatePersistence(String directory, long intervalMs) {
    java.nio.file.Path dir;
    try {
      dir = java.nio.file.Paths.get(directory);
    } catch (RuntimeException invalidPath) {
      log.warn("Invalid stateFile '{}', state persistence stays off: {}", directory, invalidPath.getMessage());
      return;
    }
    statePersister =
        new com.github.cc11001100.weavergirl.core.persistence.AgentStatePersister(
            this::captureStateSnapshot,
            dir,
            intervalMs);
    statePersister.restore();
    statePersister.start();
    log.info("State persistence enabled: dir={}, intervalMs={}", directory, intervalMs);
  }

  /** The state recorded in a snapshot: versions, counts and the sampling/breaker settings. */
  private com.github.cc11001100.weavergirl.core.persistence.AgentStateSnapshot captureStateSnapshot() {
    com.github.cc11001100.weavergirl.core.status.AgentStatus status = AgentStatus.getInstance();
    com.github.cc11001100.weavergirl.core.persistence.AgentStateSnapshot snapshot =
        new com.github.cc11001100.weavergirl.core.persistence.AgentStateSnapshot();
    snapshot
        .setInterceptorCount(registry.getAllDefinitions().size())
        .setTransformedClassCount(status.getTransformedClasses().size())
        .setAgentUptimeMs(status.getUptimeSeconds() * 1000L)
        .setSamplingRate(SamplingController.getInstance().getSamplingRate())
        .setCircuitBreakerThreshold(InterceptorHolder.getCircuitBreakerFailureThreshold());
    return snapshot;
  }


  /** Package-visible accessor so a test can prove the state persister was started. */
  com.github.cc11001100.weavergirl.core.persistence.AgentStatePersister getStatePersisterForTest() {
    return statePersister;
  }

  /** Package-visible accessor so a test can prove the adaptive-sampling monitor was started. */
  com.github.cc11001100.weavergirl.core.sampling.SamplingMonitor getSamplingMonitorForTest() {
    return samplingMonitor;
  }

  public int retransformLoadedClasses() {
    if (transformer == null) {
      return 0;
    }
    return transformer.retransformLoadedClasses();
  }

  /**
   * Unweave: remove every registered interceptor and restore already-woven classes to their
   * original bytecode.
   *
   * <p>Clears the interceptor registry (invoking each interceptor's {@code destroy()}), invalidates
   * the dispatch snapshot, and resets the ByteBuddy transformer so the JVM retransforms every class
   * it had woven. After this call the classes behave exactly as they did before the agent attached.
   *
   * <p>Not reversible without re-registering interceptors and weaving again; do not call while
   * interception is actively expected on this JVM.
   *
   * @return the number of classes retransformed to restore their original bytecode; 0 when no
   *     transformer was installed
   */
  public int unweave() {
    if (transformer == null) {
      return 0;
    }
    return transformer.unweave();
  }

  /**
   * Fluent builder for programmatic interceptor registration.
   *
   * <p>Supports all 5 ClassMatcher types and 5 MethodMatcher types, matching the expressiveness of
   * {@code AbstractPlugin}'s builder API.
   *
   * @since 1.0.0
   */
  public static class InterceptBuilder {
    private final WeaverGirl weaverGirl;
    private final ClassMatcher classMatcher;
    private MethodMatcher methodMatcher;
    private int priority = 0;
    private Interceptor interceptor;

    InterceptBuilder(WeaverGirl weaverGirl, String className) {
      this.weaverGirl = weaverGirl;
      this.classMatcher = ClassMatcher.byName(className);
      this.methodMatcher = MethodMatcher.any();
    }

    InterceptBuilder(WeaverGirl weaverGirl, ClassMatcher classMatcher) {
      this.weaverGirl = weaverGirl;
      this.classMatcher = classMatcher;
      this.methodMatcher = MethodMatcher.any();
    }

    /** Match methods by exact name. Passing "*" matches all methods. */
    public InterceptBuilder method(String methodName) {
      ValidationUtils.requireNonEmpty(methodName, "methodName");
      this.methodMatcher =
          "*".equals(methodName) ? MethodMatcher.any() : MethodMatcher.byName(methodName);
      return this;
    }

    /** Match methods by regex name pattern. */
    public InterceptBuilder methodPattern(String methodPattern) {
      ValidationUtils.requireNonEmpty(methodPattern, "methodPattern");
      this.methodMatcher = MethodMatcher.byNamePattern(methodPattern);
      return this;
    }

    /** Match methods annotated with the given annotation. */
    public InterceptBuilder methodAnnotated(String annotationClassName) {
      ValidationUtils.requireNonEmpty(annotationClassName, "annotationClassName");
      this.methodMatcher = MethodMatcher.byAnnotation(annotationClassName);
      return this;
    }

    /** Match methods by signature: "methodName(paramTypes)". */
    public InterceptBuilder methodSignature(String methodName, String paramTypes) {
      ValidationUtils.requireNonEmpty(methodName, "methodName");
      this.methodMatcher = MethodMatcher.bySignature(methodName, paramTypes);
      return this;
    }

    /** Match all methods (default if no method matcher is specified). */
    public InterceptBuilder anyMethod() {
      this.methodMatcher = MethodMatcher.any();
      return this;
    }

    public InterceptBuilder before(final Consumer<MethodInvocation> callback) {
      Interceptor existing = this.interceptor;
      this.interceptor = new BuilderInterceptors.BeforeCallbackInterceptor(callback, existing);
      return this;
    }

    public InterceptBuilder after(final Consumer<MethodInvocation> callback) {
      Interceptor existing = this.interceptor;
      this.interceptor = new BuilderInterceptors.AfterCallbackInterceptor(callback, existing);
      return this;
    }

    public InterceptBuilder onException(final Consumer<MethodInvocation> callback) {
      Interceptor existing = this.interceptor;
      this.interceptor = new BuilderInterceptors.OnExceptionCallbackInterceptor(callback, existing);
      return this;
    }

    public InterceptBuilder finallyCallback(final Consumer<MethodInvocation> callback) {
      Interceptor existing = this.interceptor;
      this.interceptor = new BuilderInterceptors.FinallyCallbackInterceptor(callback, existing);
      return this;
    }

    public InterceptBuilder priority(int priority) {
      this.priority = priority;
      return this;
    }

    public WeaverGirl install() {
      if (interceptor == null) {
        interceptor = new Interceptor() {};
      }
      Pointcut pointcut = new Pointcut(classMatcher, methodMatcher);
      InterceptorDefinition definition =
          new InterceptorDefinition(
              "programmatic-" + classMatcher.getPattern() + "-" + methodMatcher.getPattern(),
              pointcut,
              interceptor,
              priority);
      weaverGirl.registry.register(definition);
      return weaverGirl;
    }
  }

  /**
   * Fluent builder for interceptor registration via pointcut expression.
   *
   * @since 1.1.0
   */
  public static class ExpressionInterceptBuilder {
    private final WeaverGirl weaverGirl;
    private final String expression;
    private Interceptor interceptor;
    private int priority = 0;

    ExpressionInterceptBuilder(WeaverGirl weaverGirl, String expression) {
      this.weaverGirl = weaverGirl;
      this.expression = expression;
    }

    public ExpressionInterceptBuilder before(final Consumer<MethodInvocation> callback) {
      Interceptor existing = this.interceptor;
      this.interceptor =
          new Interceptor() {
            @Override
            public void before(MethodInvocation invocation) {
              callback.accept(invocation);
            }

            @Override
            public void after(MethodInvocation invocation) {
              if (existing != null) existing.after(invocation);
            }

            @Override
            public void onException(MethodInvocation invocation) {
              if (existing != null) existing.onException(invocation);
            }

            @Override
            public void afterFinally(MethodInvocation invocation) {
              if (existing != null) existing.afterFinally(invocation);
            }
          };
      return this;
    }

    public ExpressionInterceptBuilder after(final Consumer<MethodInvocation> callback) {
      Interceptor existing = this.interceptor;
      this.interceptor =
          new Interceptor() {
            @Override
            public void before(MethodInvocation invocation) {
              if (existing != null) existing.before(invocation);
            }

            @Override
            public void after(MethodInvocation invocation) {
              callback.accept(invocation);
            }

            @Override
            public void onException(MethodInvocation invocation) {
              if (existing != null) existing.onException(invocation);
            }

            @Override
            public void afterFinally(MethodInvocation invocation) {
              if (existing != null) existing.afterFinally(invocation);
            }
          };
      return this;
    }

    public ExpressionInterceptBuilder onException(final Consumer<MethodInvocation> callback) {
      Interceptor existing = this.interceptor;
      this.interceptor =
          new Interceptor() {
            @Override
            public void before(MethodInvocation invocation) {
              if (existing != null) existing.before(invocation);
            }

            @Override
            public void after(MethodInvocation invocation) {
              if (existing != null) existing.after(invocation);
            }

            @Override
            public void onException(MethodInvocation invocation) {
              callback.accept(invocation);
            }

            @Override
            public void afterFinally(MethodInvocation invocation) {
              if (existing != null) existing.afterFinally(invocation);
            }
          };
      return this;
    }

    public ExpressionInterceptBuilder afterFinally(final Consumer<MethodInvocation> callback) {
      Interceptor existing = this.interceptor;
      this.interceptor =
          new Interceptor() {
            @Override
            public void before(MethodInvocation invocation) {
              if (existing != null) existing.before(invocation);
            }

            @Override
            public void after(MethodInvocation invocation) {
              if (existing != null) existing.after(invocation);
            }

            @Override
            public void onException(MethodInvocation invocation) {
              if (existing != null) existing.onException(invocation);
            }

            @Override
            public void afterFinally(MethodInvocation invocation) {
              callback.accept(invocation);
            }
          };
      return this;
    }

    public ExpressionInterceptBuilder priority(int priority) {
      this.priority = priority;
      return this;
    }

    public WeaverGirl install() {
      PointcutExpression expr = PointcutParser.getInstance().parse(expression);
      Pointcut pointcut = expr.toPointcut();
      if (interceptor == null) {
        interceptor = new Interceptor() {};
      }
      InterceptorDefinition definition =
          new InterceptorDefinition(
              "expr-" + expression.hashCode(), pointcut, interceptor, priority);
      weaverGirl.registry.register(definition);
      return weaverGirl;
    }
  }

  /**
   * Apply agent configuration to core runtime components. Reads config keys from the agent args map
   * and configures:
   *
   * <ul>
   *   <li>{@code samplingRate} — initial sampling rate (default: 1)
   *   <li>{@code samplingMaxRate} — max sampling rate under load (default: 100)
   *   <li>{@code samplingThreshold} — invocations/sec threshold for adaptation (default: 10000)
   *   <li>{@code circuitBreakerThreshold} — consecutive failures to open breaker (default: 5)
   *   <li>{@code circuitBreakerCooldownMs} — cooldown before retry (default: 60000)
   *   <li>{@code metricsPath} — Prometheus metrics endpoint path (default: /metrics)
   * </ul>
   */
  static void applyCoreConfig(java.util.Map<String, String> config) {
    if (config == null) return;

    // Log configuration with sensitive values masked
    java.util.Map<String, String> safeConfig = SecurityUtils.sanitizeForLogging(config);
    log.info("Agent configuration: {}", safeConfig);

    // P102: emergency disable switch
    String emergencyDisable = config.get("weavergirl.emergency.disable");
    if ("true".equalsIgnoreCase(emergencyDisable)) {
      GracefulDegradationManager.enterEmergencyMode("config");
    }

    // SamplingController configuration
    SamplingController sampling = SamplingController.getInstance();
    String samplingRate = config.get("samplingRate");
    if (samplingRate != null) {
      try {
        sampling.setSamplingRate(Integer.parseInt(samplingRate.trim()));
        log.info("Config: samplingRate={}", samplingRate);
      } catch (NumberFormatException e) {
        log.warn("Invalid samplingRate '{}', using default", samplingRate);
      }
    }
    String samplingMaxRate = config.get("samplingMaxRate");
    if (samplingMaxRate != null) {
      try {
        sampling.setMaxRate(Integer.parseInt(samplingMaxRate.trim()));
        log.info("Config: samplingMaxRate={}", samplingMaxRate);
      } catch (NumberFormatException e) {
        log.warn("Invalid samplingMaxRate '{}', using default", samplingMaxRate);
      }
    }
    String samplingThreshold = config.get("samplingThreshold");
    if (samplingThreshold != null) {
      try {
        sampling.setThresholdInvocationsPerSecond(Long.parseLong(samplingThreshold.trim()));
        log.info("Config: samplingThreshold={}", samplingThreshold);
      } catch (NumberFormatException e) {
        log.warn("Invalid samplingThreshold '{}', using default", samplingThreshold);
      }
    }

    // Circuit breaker configuration. The breaker is a static singleton (the inlined advice reaches
    // it parent-first), so this retunes the live instance rather than "new instances" that never
    // exist. Previously both values were only logged, which made the documented arguments inert.
    applyCircuitBreakerConfig(config);
  }

  /** Retune the per-hook breaker from the two documented arguments. */
  private static void applyCircuitBreakerConfig(java.util.Map<String, String> config) {
    int threshold = parsePositiveInt(config.get("circuitBreakerThreshold"), 0);
    long cooldown = parsePositiveLong(config.get("circuitBreakerCooldownMs"), 0L);
    if (threshold > 0 || cooldown > 0) {
      InterceptorHolder.configureCircuitBreaker(threshold, cooldown);
      log.info(
          "Config: circuitBreakerThreshold={}, circuitBreakerCooldownMs={}",
          InterceptorHolder.getCircuitBreakerFailureThreshold(),
          InterceptorHolder.getCircuitBreakerCooldownMillis());
    }
  }

  private static int parsePositiveInt(String value, int fallback) {
    if (value == null) {
      return fallback;
    }
    try {
      int parsed = Integer.parseInt(value.trim());
      return parsed > 0 ? parsed : fallback;
    } catch (NumberFormatException notANumber) {
      log.warn("Invalid integer '{}', keeping the current value", value);
      return fallback;
    }
  }

  private static long parsePositiveLong(String value, long fallback) {
    if (value == null) {
      return fallback;
    }
    try {
      long parsed = Long.parseLong(value.trim());
      return parsed > 0 ? parsed : fallback;
    } catch (NumberFormatException notANumber) {
      log.warn("Invalid long '{}', keeping the current value", value);
      return fallback;
    }
  }

  /**
   * Internal listener that applies dynamic config changes to core components (SamplingController,
   * CircuitBreaker) at runtime.
   */
  private class DynamicCoreConfigListener
      implements com.github.cc11001100.weavergirl.api.config.ConfigChangeListener {

    @Override
    public void onConfigChange(
        com.github.cc11001100.weavergirl.api.config.ConfigChangeEvent event) {
      String key = event.getKey();
      String value = event.getNewValue();
      if (value == null) {
        return; // key removed, keep current setting
      }

      SamplingController sampling = SamplingController.getInstance();

      switch (key) {
        case "samplingRate":
          try {
            sampling.setSamplingRate(Integer.parseInt(value.trim()));
            log.info("[DynamicConfig] Applied: samplingRate={}", value);
          } catch (NumberFormatException e) {
            log.warn("[DynamicConfig] Invalid samplingRate '{}'", value);
          }
          break;

        case "samplingMaxRate":
          try {
            sampling.setMaxRate(Integer.parseInt(value.trim()));
            log.info("[DynamicConfig] Applied: samplingMaxRate={}", value);
          } catch (NumberFormatException e) {
            log.warn("[DynamicConfig] Invalid samplingMaxRate '{}'", value);
          }
          break;

        case "samplingThreshold":
          try {
            sampling.setThresholdInvocationsPerSecond(Long.parseLong(value.trim()));
            log.info("[DynamicConfig] Applied: samplingThreshold={}", value);
          } catch (NumberFormatException e) {
            log.warn("[DynamicConfig] Invalid samplingThreshold '{}'", value);
          }
          break;

        case "circuitBreakerThreshold":
          InterceptorHolder.configureCircuitBreaker(
              parsePositiveInt(value, InterceptorHolder.getCircuitBreakerFailureThreshold()),
              InterceptorHolder.getCircuitBreakerCooldownMillis());
          log.info("[DynamicConfig] Applied: circuitBreakerThreshold={}", value);
          break;

        case "circuitBreakerCooldownMs":
          InterceptorHolder.configureCircuitBreaker(
              InterceptorHolder.getCircuitBreakerFailureThreshold(),
              parsePositiveLong(value, InterceptorHolder.getCircuitBreakerCooldownMillis()));
          log.info("[DynamicConfig] Applied: circuitBreakerCooldownMs={}", value);
          break;

        default:
          // Not a core config key — plugins may handle via their own listeners
          break;
      }
    }
  }
}
