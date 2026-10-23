package com.github.cc11001100.weavergirl.core.plugin;

import com.github.cc11001100.weavergirl.api.ValidationUtils;
import com.github.cc11001100.weavergirl.api.alert.AlertChannel;
import com.github.cc11001100.weavergirl.api.alert.AlertEngine;
import com.github.cc11001100.weavergirl.api.alert.AlertRule;
import com.github.cc11001100.weavergirl.api.observability.HealthCheck;
import com.github.cc11001100.weavergirl.api.observability.HealthChecks;
import com.github.cc11001100.weavergirl.api.observability.MetricSource;
import com.github.cc11001100.weavergirl.api.observability.MetricSources;
import com.github.cc11001100.weavergirl.api.observability.ResourceAttributeProvider;
import com.github.cc11001100.weavergirl.api.observability.ResourceAttributes;
import com.github.cc11001100.weavergirl.api.observability.SpanSink;
import com.github.cc11001100.weavergirl.api.observability.SpanSinks;
import com.github.cc11001100.weavergirl.api.plugin.PluginContext;
import com.github.cc11001100.weavergirl.api.plugin.PluginLogger;
import com.github.cc11001100.weavergirl.api.plugin.PluginServices;
import com.github.cc11001100.weavergirl.api.registry.InterceptorRegistry;
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

  private final InterceptorRegistry registry;
  private final Map<String, String> config;
  private final String pluginName;

  /** The services this plugin published, so they can be withdrawn when the plugin goes away. */
  private final List<Object> publishedServices = new CopyOnWriteArrayList<>();

  /** Observability extensions this plugin registered; withdrawn with the plugin. */
  private final List<SpanSink> spanSinks = new CopyOnWriteArrayList<>();

  private final List<SpanProcessor> spanProcessors = new CopyOnWriteArrayList<>();

  private final List<HealthCheck> healthChecks = new CopyOnWriteArrayList<>();

  private final List<AlertChannel> alertChannels = new CopyOnWriteArrayList<>();
  private final List<AlertRule> alertRules = new CopyOnWriteArrayList<>();

  private final List<MetricSource> metricSources = new CopyOnWriteArrayList<>();
  private final List<ResourceAttributeProvider> resourceAttributeProviders =
      new CopyOnWriteArrayList<>();

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
    SpanSinks.register(sink);
    spanSinks.add(sink);
  }

  @Override
  public void registerSpanProcessor(SpanProcessor processor) {
    SpanProcessors.register(processor);
    spanProcessors.add(processor);
  }

  @Override
  public void registerHealthCheck(HealthCheck check) {
    HealthChecks.register(check);
    healthChecks.add(check);
  }

  @Override
  public void registerAlertChannel(AlertChannel channel) {
    AlertEngine.addChannel(channel);
    alertChannels.add(channel);
  }

  @Override
  public void registerAlertRule(AlertRule rule) {
    AlertEngine.addRule(rule);
    alertRules.add(rule);
  }

  @Override
  public void registerMetricSource(MetricSource source) {
    MetricSources.register(source);
    metricSources.add(source);
  }

  @Override
  public void registerResourceAttributes(ResourceAttributeProvider provider) {
    ResourceAttributes.register(provider);
    resourceAttributeProviders.add(provider);
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
    for (AlertChannel channel : alertChannels) {
      AlertEngine.removeChannel(channel);
    }
    alertChannels.clear();
    for (AlertRule rule : alertRules) {
      // Only withdraw our instance: a replacement registered later under the same name stays.
      AlertEngine.removeRule(rule);
    }
    alertRules.clear();
    for (MetricSource source : metricSources) {
      MetricSources.unregister(source);
    }
    metricSources.clear();
    for (ResourceAttributeProvider provider : resourceAttributeProviders) {
      ResourceAttributes.unregister(provider);
    }
    resourceAttributeProviders.clear();
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
