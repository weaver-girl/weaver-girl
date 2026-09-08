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
import com.github.cc11001100.weavergirl.api.plugin.PluginManager;
import com.github.cc11001100.weavergirl.api.plugin.PluginManagerHolder;
import com.github.cc11001100.weavergirl.api.plugin.WeaverPlugin;
import com.github.cc11001100.weavergirl.api.registry.InterceptorRegistry;
import com.github.cc11001100.weavergirl.api.tracing.Tracer;
import com.github.cc11001100.weavergirl.core.config.ConfigWatcher;
import com.github.cc11001100.weavergirl.core.config.DefaultDynamicConfigManager;
import com.github.cc11001100.weavergirl.core.config.WeaverConfig;
import com.github.cc11001100.weavergirl.core.exporter.OtlpHttpExporter;
import com.github.cc11001100.weavergirl.core.exporter.SpanExporter;
import com.github.cc11001100.weavergirl.core.exporter.SpanFormatter;
import com.github.cc11001100.weavergirl.core.management.AgentMonitor;
import com.github.cc11001100.weavergirl.core.plugin.DefaultPluginManager;
import com.github.cc11001100.weavergirl.core.plugin.PluginLoader;
import com.github.cc11001100.weavergirl.core.registry.DefaultInterceptorRegistry;
import com.github.cc11001100.weavergirl.core.release.AgentUpdater;
import com.github.cc11001100.weavergirl.core.release.UpdateChecker;
import com.github.cc11001100.weavergirl.core.release.VersionInfo;
import com.github.cc11001100.weavergirl.core.sampling.SamplingController;
import com.github.cc11001100.weavergirl.core.sampling.SamplingMonitor;
import com.github.cc11001100.weavergirl.core.status.AgentStatus;
import com.github.cc11001100.weavergirl.core.status.JmxRegistrar;
import com.github.cc11001100.weavergirl.core.trace.TracerSpanExporterBridge;
import com.github.cc11001100.weavergirl.core.transformer.WeaverTransformer;
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
  private Instrumentation instrumentation;
  private WeaverTransformer transformer;
  private SamplingMonitor samplingMonitor;
  private ConfigWatcher configWatcher;

  private final DefaultDynamicConfigManager dynamicConfigManager;
  private volatile DefaultPluginManager pluginManager;
  private volatile boolean pluginManagerInitialized = false;
  private final Object pluginManagerLock = new Object();
  private volatile boolean samplingMonitorInitialized = false;
  private final Object samplingMonitorLock = new Object();
  private volatile boolean configWatcherInitialized = false;
  private final Object configWatcherLock = new Object();

  private SpanExporter spanExporter;
  private TracerSpanExporterBridge spanExportBridge;
  private UpdateChecker updateChecker;

  private WeaverGirl() {
    this.registry = new DefaultInterceptorRegistry();
    this.pluginLoader = new PluginLoader();
    this.dynamicConfigManager = new DefaultDynamicConfigManager();
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

  void initSamplingMonitor() {
    if (!samplingMonitorInitialized) {
      synchronized (samplingMonitorLock) {
        if (!samplingMonitorInitialized) {
          SamplingController controller = SamplingController.getInstance();
          if (controller != null) {
            SamplingMonitor monitor = new SamplingMonitor(controller);
            monitor.start();
            samplingMonitor = monitor;
          }
          samplingMonitorInitialized = true;
        }
      }
    }
  }

  void initConfigWatcher() {
    if (!configWatcherInitialized) {
      synchronized (configWatcherLock) {
        if (!configWatcherInitialized) {
          configWatcher = new ConfigWatcher(null, registry);
          configWatcherInitialized = true;
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
      log.info("WeaverGirl agent starting...");
      WeaverGirl weaverGirl = new WeaverGirl();
      weaverGirl.instrumentation = instrumentation;

      InterceptorHolder.setRegistry(weaverGirl.registry);

      // Merge disabledPlugins from WeaverConfig into the config map for PluginLoader
      java.util.Map<String, String> pluginConfig =
          config != null ? config : new java.util.HashMap<>();
      if (weaverConfig != null
          && weaverConfig.getDisabledPlugins() != null
          && !weaverConfig.getDisabledPlugins().isEmpty()) {
        String disabledStr = String.join(",", weaverConfig.getDisabledPlugins());
        pluginConfig.put("disabledPlugins", disabledStr);
        log.info("Disabled plugins from config: {}", disabledStr);
      }

      long pluginLoadStart = System.nanoTime();
      weaverGirl.pluginLoader.loadPlugins(
          WeaverGirl.class.getClassLoader(), weaverGirl.registry, pluginConfig);
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

      // Apply configuration to core components
      applyCoreConfig(pluginConfig);

      // P79: wire span batch export (opt-in via otlpEndpoint / spanExport).
      weaverGirl.initSpanExport(pluginConfig);

      // P80: wire periodic update check (opt-in via updateCheckEndpoint).
      weaverGirl.initUpdateCheck(pluginConfig);

      // Initialize DynamicConfigManager with initial config and runtime listener
      weaverGirl.dynamicConfigManager.loadFromSource(pluginConfig, "bootstrap");
      weaverGirl.dynamicConfigManager.addListener(weaverGirl.new DynamicCoreConfigListener());
      weaverGirl.dynamicConfigManager.snapshot("initial-bootstrap");

      long mgmtStart = System.nanoTime();
      JmxRegistrar.register();

      // Register JMX monitoring MBean
      AgentMonitor monitor = AgentMonitor.getInstance();
      monitor.register();
      monitor.setInterceptorDefinitionCount(weaverGirl.registry.getAllDefinitions().size());
      monitor.setPluginCount(weaverGirl.pluginLoader.getLoadedPlugins().size());

      // P99: defer SamplingMonitor/PluginManager to first access so bootstrap does less work
      // when these are not needed immediately.
      com.github.cc11001100.weavergirl.core.management.StartupMetrics.recordPhase(
          "managementInit", System.nanoTime() - mgmtStart);

      log.info(
          "WeaverGirl agent started with {} interceptor definitions",
          weaverGirl.registry.getAllDefinitions().size());
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
   * Shutdown the agent — destroy all plugins and clean up. Should be called from a shutdown hook.
   */
  public void shutdown() {
    log.info("WeaverGirl agent shutting down...");
    stopSpanExport();
    stopUpdateCheck();

    ConfigWatcher localConfigWatcher = this.configWatcher;
    if (localConfigWatcher != null) {
      localConfigWatcher.stop();
    }

    SamplingMonitor localSamplingMonitor = this.samplingMonitor;
    if (localSamplingMonitor != null) {
      localSamplingMonitor.stop();
    }

    if (dynamicConfigManager != null) {
      dynamicConfigManager.shutdown();
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

    AgentMonitor.getInstance().unregister();
    JmxRegistrar.unregister();
    InterceptorHolder.setRegistry(null);
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

  /**
   * Wire span batch export (P79).
   * {@link OtlpHttpExporter}; {@code spanExport=true} without an endpoint enables log-only export
   * via {@link SpanFormatter.LoggingSpanFormatter}. Without either key nothing is created (zero
   * overhead, no background thread).
   *
   * <p>The bridge registers with {@link Tracer#addCompletionListener} (additive, so it coexists
   * with other consumers such as the topology bridge) and the exporter is stopped on {@link
   * #shutdown()}.
   *
   * <p>Optional tuning keys: {@code spanExportBatchSize} (default 100), {@code
   * spanExportIntervalMs} (default 5000), {@code spanExportBufferSize} (default 10000).
   */
  void initSpanExport(java.util.Map<String, String> config) {
    if (config == null) {
      return;
    }
    String endpoint = config.get("otlpEndpoint");
    boolean logOnly = "true".equalsIgnoreCase(config.get("spanExport"));
    if ((endpoint == null || endpoint.trim().isEmpty()) && !logOnly) {
      return;
    }
    try {
      SpanExporter exporter =
          new SpanExporter(
              parsePositiveInt(config.get("spanExportBatchSize"), 100),
              parsePositiveLong(config.get("spanExportIntervalMs"), 5_000L),
              parsePositiveInt(config.get("spanExportBufferSize"), 10_000));
      if (endpoint != null && !endpoint.trim().isEmpty()) {
        OtlpHttpExporter.Builder builder = OtlpHttpExporter.builder().endpoint(endpoint.trim());
        String headers = config.get("otlpHeaders");
        if (headers != null && !headers.trim().isEmpty()) {
          for (String pair : headers.split(";")) {
            int eq = pair.indexOf('=');
            if (eq > 0) {
              builder.header(pair.substring(0, eq).trim(), pair.substring(eq + 1).trim());
            }
          }
        }
        exporter.addFormatter(builder.build());
        log.info("Span export: OTLP HTTP -> {}", endpoint.trim());
      } else {
        exporter.addFormatter(new SpanFormatter.LoggingSpanFormatter());
        log.info("Span export: log-only (no otlpEndpoint configured)");
      }
      exporter.start();
      spanExportBridge = new TracerSpanExporterBridge(exporter);
      Tracer.addCompletionListener(spanExportBridge);
      spanExporter = exporter;
    } catch (Exception e) {
      log.warn("Failed to initialize span export: {}", e.getMessage());
    }
  }

  private static int parsePositiveInt(String value, int defaultValue) {
    if (value == null) {
      return defaultValue;
    }
    try {
      int parsed = Integer.parseInt(value.trim());
      return parsed > 0 ? parsed : defaultValue;
    } catch (NumberFormatException e) {
      return defaultValue;
    }
  }

  private static long parsePositiveLong(String value, long defaultValue) {
    if (value == null) {
      return defaultValue;
    }
    try {
      long parsed = Long.parseLong(value.trim());
      return parsed > 0 ? parsed : defaultValue;
    } catch (NumberFormatException e) {
      return defaultValue;
    }
  }

  /**
   * Returns the span batch exporter created by {@link #initSpanExport}, or null when span export is
   * not enabled. Primarily for tests and diagnostics.
   *
   * @return the active span exporter, or null
   */
  public SpanExporter getSpanExporter() {
    return spanExporter;
  }

  /**
   * Detach the span export bridge and stop the exporter. Called from {@link #shutdown()};
   * package-visible for tests.
   */
  void stopSpanExport() {
    if (spanExportBridge != null) {
      try {
        Tracer.removeCompletionListener(spanExportBridge);
      } catch (Exception e) {
        log.warn("Failed to detach span export bridge: {}", e.getMessage());
      }
      spanExportBridge = null;
    }
    if (spanExporter != null) {
      try {
        spanExporter.stop();
      } catch (Exception e) {
        log.warn("Failed to stop span exporter: {}", e.getMessage());
      }
      spanExporter = null;
    }
  }

  /**
   * Wire periodic remote version checks (P80).
   *
   * <p>Opt-in via {@code updateCheckEndpoint=https://.../version.json}: polls the endpoint and logs
   * when a newer version is available. Without the key nothing is created (zero overhead, no
   * background thread).
   *
   * <p>Optional tuning keys: {@code updateCheckIntervalMs} (default 24h), {@code updateStagingDir}
   * (default {@code java.io.tmpdir/weaver-girl-updates}). When {@code updateAutoStage=true} and the
   * remote descriptor carries a download URL + checksum, the new artifact is downloaded and
   * verified into the staging dir, ready for an operator-driven swap + restart (a loaded agent JAR
   * cannot replace itself).
   */
  void initUpdateCheck(java.util.Map<String, String> config) {
    if (config == null) {
      return;
    }
    String endpoint = config.get("updateCheckEndpoint");
    if (endpoint == null || endpoint.trim().isEmpty()) {
      return;
    }
    try {
      UpdateChecker.Builder builder = UpdateChecker.builder().endpoint(endpoint.trim());
      String interval = config.get("updateCheckIntervalMs");
      if (interval != null) {
        try {
          builder.checkIntervalMs(Long.parseLong(interval.trim()));
        } catch (NumberFormatException e) {
          log.warn("Invalid updateCheckIntervalMs '{}', using default", interval);
        }
      }
      UpdateChecker checker = builder.build();
      final boolean autoStage = "true".equalsIgnoreCase(config.get("updateAutoStage"));
      final String stagingDir = config.get("updateStagingDir");
      final String headers = config.get("updateHeaders");
      checker.addListener(remote -> onNewVersionAvailable(remote, autoStage, stagingDir, headers));
      checker.start();
      updateChecker = checker;
      log.info("Update check enabled (endpoint={})", endpoint.trim());
    } catch (Exception e) {
      log.warn("Failed to initialize update check: {}", e.getMessage());
    }
  }

  private static void onNewVersionAvailable(
      VersionInfo remote, boolean autoStage, String stagingDir, String headers) {
    String notes = remote.getReleaseNotes();
    log.warn(
        "[Update] New agent version available: {}{} — see release notes{}",
        remote.getVersion(),
        remote.isMandatory() ? " (MANDATORY)" : "",
        notes != null ? ": " + notes : " (none provided)");
    if (!autoStage) {
      if (remote.isDownloadable()) {
        log.warn(
            "[Update] Download manually: {} (set updateAutoStage=true to stage automatically)",
            remote.getDownloadUrl());
      }
      return;
    }
    if (!remote.isDownloadable()) {
      log.warn(
          "[Update] updateAutoStage=true but v{} has no downloadUrl — manual upgrade required",
          remote.getVersion());
      return;
    }
    try {
      java.io.File dir =
          stagingDir != null && !stagingDir.trim().isEmpty()
              ? new java.io.File(stagingDir.trim())
              : new java.io.File(System.getProperty("java.io.tmpdir"), "weaver-girl-updates");
      AgentUpdater updater = new AgentUpdater(dir);
      java.util.Map<String, String> extraHeaders = null;
      if (headers != null && !headers.trim().isEmpty()) {
        extraHeaders = new java.util.LinkedHashMap<>();
        for (String pair : headers.split(";")) {
          int eq = pair.indexOf('=');
          if (eq > 0) {
            extraHeaders.put(pair.substring(0, eq).trim(), pair.substring(eq + 1).trim());
          }
        }
      }
      AgentUpdater.UpdateResult result = updater.stageUpdate(remote, extraHeaders);
      if (!result.isSuccess()) {
        log.warn("[Update] Auto-stage failed: {}", result.getMessage());
      }
    } catch (Exception e) {
      log.warn("[Update] Auto-stage failed: {}", e.getMessage());
    }
  }

  /**
   * Returns the update checker created by {@link #initUpdateCheck}, or null when update checks are
   * not enabled. Primarily for tests and diagnostics.
   *
   * @return the active update checker, or null
   */
  public UpdateChecker getUpdateChecker() {
    return updateChecker;
  }

  /** Stop the update checker. Called from {@link #shutdown()}; package-visible for tests. */
  void stopUpdateCheck() {
    if (updateChecker != null) {
      try {
        updateChecker.stop();
      } catch (Exception e) {
        log.warn("Failed to stop update checker: {}", e.getMessage());
      }
      updateChecker = null;
    }
  }

  /** Set the ConfigWatcher so it can be stopped during shutdown. */
  public void setConfigWatcher(ConfigWatcher watcher) {
    this.configWatcher = watcher;
  }

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
  public int retransformLoadedClasses() {
    if (transformer == null) {
      return 0;
    }
    return transformer.retransformLoadedClasses();
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
          };
      return this;
    }

    public InterceptBuilder after(final Consumer<MethodInvocation> callback) {
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
          };
      return this;
    }

    public InterceptBuilder onException(final Consumer<MethodInvocation> callback) {
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
          };
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

    // Circuit breaker configuration (via InterceptorHolder)
    String cbThreshold = config.get("circuitBreakerThreshold");
    if (cbThreshold != null) {
      log.info("Config: circuitBreakerThreshold={} (applied to new instances)", cbThreshold);
    }
    String cbCooldown = config.get("circuitBreakerCooldownMs");
    if (cbCooldown != null) {
      log.info("Config: circuitBreakerCooldownMs={} (applied to new instances)", cbCooldown);
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

        default:
          // Not a core config key — plugins may handle via their own listeners
          break;
      }
    }
  }
}
