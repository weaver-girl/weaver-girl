package com.github.cc11001100.weavergirl.core.plugin;

import com.github.cc11001100.weavergirl.api.ValidationUtils;
import com.github.cc11001100.weavergirl.api.alert.AlertChannel;
import com.github.cc11001100.weavergirl.api.alert.AlertEngine;
import com.github.cc11001100.weavergirl.api.alert.AlertRule;
import com.github.cc11001100.weavergirl.api.event.EventTransformers;
import com.github.cc11001100.weavergirl.api.event.InterceptorEventListener;
import com.github.cc11001100.weavergirl.api.event.InterceptorEventPublisher;
import com.github.cc11001100.weavergirl.api.event.InterceptorEventTransformer;
import com.github.cc11001100.weavergirl.api.exporter.DataExporter;
import com.github.cc11001100.weavergirl.api.exporter.ExporterRegistry;
import com.github.cc11001100.weavergirl.api.observability.ConfigurableExtension;
import com.github.cc11001100.weavergirl.api.observability.ExtensionPerformanceTracker;
import com.github.cc11001100.weavergirl.api.observability.HealthCheck;
import com.github.cc11001100.weavergirl.api.observability.HealthCheckExtension;
import com.github.cc11001100.weavergirl.api.observability.HealthChecks;
import com.github.cc11001100.weavergirl.api.observability.MetricProcessor;
import com.github.cc11001100.weavergirl.api.observability.MetricProcessors;
import com.github.cc11001100.weavergirl.api.observability.MetricSource;
import com.github.cc11001100.weavergirl.api.observability.MetricSources;
import com.github.cc11001100.weavergirl.api.observability.ResourceAttributeProvider;
import com.github.cc11001100.weavergirl.api.observability.ResourceAttributes;
import com.github.cc11001100.weavergirl.api.observability.SpanSink;
import com.github.cc11001100.weavergirl.api.observability.SpanSinks;
import com.github.cc11001100.weavergirl.api.observability.TagEnricher;
import com.github.cc11001100.weavergirl.api.observability.TagEnrichers;
import com.github.cc11001100.weavergirl.api.plugin.PluginContext;
import com.github.cc11001100.weavergirl.api.plugin.PluginLogger;
import com.github.cc11001100.weavergirl.api.plugin.PluginServices;
import com.github.cc11001100.weavergirl.api.registry.InterceptorRegistry;
import com.github.cc11001100.weavergirl.api.sampling.SamplingStrategy;
import com.github.cc11001100.weavergirl.api.sampling.SamplingStrategyRegistry;
import com.github.cc11001100.weavergirl.api.tracing.SpanProcessor;
import com.github.cc11001100.weavergirl.api.tracing.SpanProcessors;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/** Default implementation of PluginContext. */
public class DefaultPluginContext implements PluginContext {

  private static final Logger log = LoggerFactory.getLogger(DefaultPluginContext.class);

  private final InterceptorRegistry registry;
  private final Map<String, String> config;
  private final String pluginName;

  /** The services this plugin published, so they can be withdrawn when the plugin goes away. */
  private final List<Object> publishedServices = new CopyOnWriteArrayList<>();

  /** Observability extensions this plugin registered; withdrawn with the plugin. */
  private final List<SpanSink> spanSinks = new CopyOnWriteArrayList<>();

  private final List<SpanProcessor> spanProcessors = new CopyOnWriteArrayList<>();

  private final List<HealthCheck> healthChecks = new CopyOnWriteArrayList<>();

  private final List<HealthCheckExtension> healthCheckExtensions = new CopyOnWriteArrayList<>();

  private final List<TagEnricher> tagEnrichers = new CopyOnWriteArrayList<>();

  private final List<AlertChannel> alertChannels = new CopyOnWriteArrayList<>();
  private final List<AlertRule> alertRules = new CopyOnWriteArrayList<>();

  private final List<SamplingStrategy> samplingStrategies = new CopyOnWriteArrayList<>();

  private final List<MetricSource> metricSources = new CopyOnWriteArrayList<>();
  private final List<ResourceAttributeProvider> resourceAttributeProviders =
      new CopyOnWriteArrayList<>();

  private final List<InterceptorEventListener> eventListeners = new CopyOnWriteArrayList<>();
  private final List<DataExporter> dataExporters = new CopyOnWriteArrayList<>();
  private final List<InterceptorEventTransformer> eventTransformers = new CopyOnWriteArrayList<>();
  private final List<MetricProcessor> metricProcessors = new CopyOnWriteArrayList<>();

  public DefaultPluginContext(
      InterceptorRegistry registry, Map<String, String> config, String pluginName) {
    this.registry = ValidationUtils.requireNonNull(registry, "registry");
    this.config = config != null ? config : Collections.emptyMap();
    this.pluginName = ValidationUtils.requireNonEmpty(pluginName, "pluginName");
  }

  @Override
  public void registerService(Class<?> service, Object implementation) {
    PluginServices.register(service, implementation);
    publishedServices.add(implementation);
  }

  @Override
  public void registerSpanSink(SpanSink sink) {
    if (!isExtensionEnabled(sink)) {
      return;
    }
    SpanSinks.register(sink);
    spanSinks.add(sink);
  }

  @Override
  public void registerSpanProcessor(SpanProcessor processor) {
    if (!isExtensionEnabled(processor)) {
      return;
    }
    SpanProcessors.register(processor);
    spanProcessors.add(processor);
  }

  @Override
  public void registerHealthCheck(HealthCheck check) {
    if (!isExtensionEnabled(check)) {
      return;
    }
    HealthChecks.register(check);
    healthChecks.add(check);
  }

  @Override
  public void registerHealthCheckExtension(HealthCheckExtension extension) {
    if (!isExtensionEnabled(extension)) {
      return;
    }
    HealthChecks.register(pluginName, extension);
    if (extension != null && !containsInstance(healthCheckExtensions, extension)) {
      healthCheckExtensions.add(extension);
    }
  }

  @Override
  public void registerTagEnricher(TagEnricher enricher) {
    if (!isExtensionEnabled(enricher)) {
      return;
    }
    TagEnrichers.register(enricher);
    if (enricher != null && !containsInstance(tagEnrichers, enricher)) {
      tagEnrichers.add(enricher);
    }
  }

  @Override
  public void registerMetricProcessor(MetricProcessor processor) {
    if (!isExtensionEnabled(processor)) {
      return;
    }
    MetricProcessors.register(processor);
    if (processor != null && !containsInstance(metricProcessors, processor)) {
      metricProcessors.add(processor);
    }
  }

  @Override
  public void registerEventTransformer(InterceptorEventTransformer transformer) {
    if (!isExtensionEnabled(transformer)) {
      return;
    }
    EventTransformers.register(transformer);
    if (transformer != null && !containsInstance(eventTransformers, transformer)) {
      eventTransformers.add(transformer);
    }
  }

  @Override
  public void registerAlertChannel(AlertChannel channel) {
    if (!isExtensionEnabled(channel)) {
      return;
    }
    AlertEngine.addChannel(channel);
    alertChannels.add(channel);
  }

  @Override
  public void registerAlertRule(AlertRule rule) {
    if (!isExtensionEnabled(rule)) {
      return;
    }
    AlertEngine.addRule(rule);
    alertRules.add(rule);
  }

  @Override
  public void registerSamplingStrategy(SamplingStrategy strategy) {
    if (!isExtensionEnabled(strategy)) {
      return;
    }
    // Qualified on the interface: a plain super call to a default method fails to resolve under the
    // JDK 21 compiler.
    PluginContext.super.registerSamplingStrategy(strategy);
    samplingStrategies.add(strategy);
  }

  @Override
  public void registerEventListener(InterceptorEventListener listener) {
    if (!isExtensionEnabled(listener)) {
      return;
    }
    if (listener == null || containsInstance(eventListeners, listener)) {
      return;
    }
    // Already on the bus from a direct addListener call. That registration is not ours to withdraw.
    boolean alreadyPublished =
        containsInstance(InterceptorEventPublisher.getInstance().getListeners(), listener);
    PluginContext.super.registerEventListener(listener);
    if (!alreadyPublished) {
      eventListeners.add(listener);
    }
  }

  @Override
  public void registerDataExporter(DataExporter exporter) {
    if (!isExtensionEnabled(exporter)) {
      return;
    }
    PluginContext.super.registerDataExporter(exporter);
    if (exporter != null && exporter.name() != null && !containsInstance(dataExporters, exporter)) {
      dataExporters.add(exporter);
    }
  }

  @Override
  public void registerMetricSource(MetricSource source) {
    if (!isExtensionEnabled(source)) {
      return;
    }
    MetricSources.register(source);
    metricSources.add(source);
  }

  @Override
  public void registerResourceAttributes(ResourceAttributeProvider provider) {
    if (!isExtensionEnabled(provider)) {
      return;
    }
    ResourceAttributes.register(provider);
    resourceAttributeProviders.add(provider);
  }

  /**
   * Whether a plugin-contributed extension wants to be registered.
   *
   * <p>{@link ConfigurableExtension} lets an operator switch an extension off through configuration
   * without touching the plugin: {@code isEnabled} is asked once, here, at registration time. An
   * extension that does not implement the interface is always registered, and a predicate that
   * throws counts as disabled — a broken switch must not take plugin initialization down with it.
   *
   * @param extension the extension about to be registered, possibly null
   * @return true when the extension should be registered
   */
  private boolean isExtensionEnabled(Object extension) {
    if (!(extension instanceof ConfigurableExtension)) {
      return true;
    }
    ConfigurableExtension configurable = (ConfigurableExtension) extension;
    try {
      return configurable.isEnabled(this);
    } catch (RuntimeException e) {
      log.warn(
          "ConfigurableExtension "
              + extension.getClass().getName()
              + " isEnabled() threw; treating it as disabled",
          e);
      return false;
    }
  }

  /**
   * Withdraw every service and observability extension this plugin published, so nothing it
   * contributed outlives it. Called when the plugin is unloaded or destroyed.
   */
  public void withdrawServices() {
    for (Object service : publishedServices) {
      PluginServices.unregister(service);
    }
    publishedServices.clear();
    for (SpanSink sink : spanSinks) {
      SpanSinks.unregister(sink);
    }
    spanSinks.clear();
    for (SpanProcessor processor : spanProcessors) {
      SpanProcessors.unregister(processor);
    }
    spanProcessors.clear();
    for (HealthCheck check : healthChecks) {
      HealthChecks.unregister(check);
    }
    healthChecks.clear();
    for (HealthCheckExtension extension : healthCheckExtensions) {
      HealthChecks.unregister(extension);
      // Drop the counters too: the component is gone, so its health series must not linger.
      ExtensionPerformanceTracker.remove(
          ExtensionPerformanceTracker.KIND_HEALTH_CHECK,
          HealthChecks.componentName(pluginName, extension));
    }
    healthCheckExtensions.clear();
    for (TagEnricher enricher : tagEnrichers) {
      TagEnrichers.unregister(enricher);
      ExtensionPerformanceTracker.remove(
          ExtensionPerformanceTracker.KIND_TAG_ENRICHER, enricherName(enricher));
    }
    tagEnrichers.clear();
    for (AlertChannel channel : alertChannels) {
      AlertEngine.removeChannel(channel);
    }
    alertChannels.clear();
    for (AlertRule rule : alertRules) {
      // Only withdraw our instance: a replacement registered later under the same name stays.
      AlertEngine.removeRule(rule);
    }
    alertRules.clear();
    for (SamplingStrategy strategy : samplingStrategies) {
      // Identity-based: a replacement registered later under the same name stays active.
      SamplingStrategyRegistry.unregister(strategy);
    }
    samplingStrategies.clear();
    for (MetricSource source : metricSources) {
      MetricSources.unregister(source);
    }
    metricSources.clear();
    for (ResourceAttributeProvider provider : resourceAttributeProviders) {
      ResourceAttributes.unregister(provider);
    }
    resourceAttributeProviders.clear();
    for (InterceptorEventListener listener : eventListeners) {
      InterceptorEventPublisher.getInstance().removeListener(listener);
    }
    eventListeners.clear();
    for (DataExporter exporter : dataExporters) {
      // Identity: a different instance later registered under this name stays active.
      ExporterRegistry.unregister(exporter);
    }
    dataExporters.clear();
    for (InterceptorEventTransformer transformer : eventTransformers) {
      EventTransformers.unregister(transformer);
      ExtensionPerformanceTracker.remove(
          ExtensionPerformanceTracker.KIND_EVENT_TRANSFORMER, transformerName(transformer));
    }
    eventTransformers.clear();
    for (MetricProcessor processor : metricProcessors) {
      MetricProcessors.unregister(processor);
      ExtensionPerformanceTracker.remove(
          ExtensionPerformanceTracker.KIND_METRIC_PROCESSOR, processorName(processor));
    }
    metricProcessors.clear();
  }

  private static String transformerName(InterceptorEventTransformer transformer) {
    try {
      String name = transformer.name();
      return name == null || name.trim().isEmpty() ? "unknown" : name.trim();
    } catch (RuntimeException e) {
      return "unknown";
    }
  }

  private static String processorName(MetricProcessor processor) {
    try {
      String name = processor.name();
      return name == null || name.trim().isEmpty() ? "unknown" : name.trim();
    } catch (RuntimeException e) {
      return "unknown";
    }
  }

  private static String enricherName(TagEnricher enricher) {
    try {
      String name = enricher.name();
      return name == null || name.trim().isEmpty() ? "unknown" : name.trim();
    } catch (RuntimeException e) {
      return "unknown";
    }
  }

  private static boolean containsInstance(List<?> items, Object candidate) {
    for (Object item : items) {
      if (item == candidate) {
        return true;
      }
    }
    return false;
  }

  @Override
  public InterceptorRegistry getRegistry() {
    return registry;
  }

  @Override
  public String getConfig(String key) {
    return lookup(key);
  }

  @Override
  public String getConfig(String key, String defaultValue) {
    String value = lookup(key);
    return value != null ? value : defaultValue;
  }

  /**
   * Resolve one key through the full precedence chain, most specific first:
   *
   * <ol>
   *   <li>{@code -Dweavergirl.plugin.<name>.<key>} — this plugin only
   *   <li>YAML {@code plugins.<name>.<key>} — this plugin only
   *   <li>{@code -D<key>} — a plain system property, kept for backward compatibility
   *   <li>the global config map, which carries the agent arguments
   * </ol>
   *
   * <p>Both accessors share this chain. A plugin whose key happens to collide with one another
   * capability reads is isolated by the namespaced entries, while existing launch lines that pass
   * plain keys such as {@code metricsPort=9400} keep working.
   */
  private String lookup(String key) {
    ValidationUtils.requireNonEmpty(key, "key");
    String namespacedKey = "weavergirl.plugin." + pluginName + "." + key;
    String value = System.getProperty(namespacedKey);
    if (value != null) return value;

    value = config.get("plugins." + pluginName + "." + key);
    if (value != null) return value;

    value = System.getProperty(key);
    if (value != null) return value;

    return config.get(key);
  }

  @Override
  public Map<String, String> getAllConfig() {
    return Collections.unmodifiableMap(config);
  }

  @Override
  public String getPluginName() {
    return pluginName;
  }

  @Override
  public PluginLogger getLogger() {
    // Named so a plugin's lines are filterable, and created here so it binds to the agent's logging
    // rather than to whatever the plugin's own class loader can see.
    final Logger logger = LoggerFactory.getLogger("weavergirl.plugin." + pluginName);
    return new PluginLogger() {
      @Override
      public void info(String message) {
        logger.info(message);
      }

      @Override
      public void warn(String message) {
        logger.warn(message);
      }

      @Override
      public void error(String message, Throwable error) {
        logger.error(message, error);
      }
    };
  }
}
