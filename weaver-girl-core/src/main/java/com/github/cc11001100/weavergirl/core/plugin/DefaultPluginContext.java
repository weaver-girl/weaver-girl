package com.github.cc11001100.weavergirl.core.plugin;

import com.github.cc11001100.weavergirl.api.ValidationUtils;
import com.github.cc11001100.weavergirl.api.plugin.PluginContext;
import com.github.cc11001100.weavergirl.api.plugin.PluginLogger;
import com.github.cc11001100.weavergirl.api.plugin.PluginServices;
import com.github.cc11001100.weavergirl.api.registry.InterceptorRegistry;
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

  /** Withdraw every service this plugin published. Called when the plugin is unloaded or destroyed. */
  public void withdrawServices() {
    for (Object service : publishedServices) {
      PluginServices.unregister(service);
    }
    publishedServices.clear();
  }

  @Override
  public InterceptorRegistry getRegistry() {
    return registry;
  }

  @Override
  public String getConfig(String key) {
    ValidationUtils.requireNonEmpty(key, "key");
    // Check namespaced system property first
    String namespacedKey = "weavergirl.plugin." + pluginName + "." + key;
    String value = System.getProperty(namespacedKey);
    if (value != null) return value;

    // Check plain system property
    value = System.getProperty(key);
    if (value != null) return value;

    // Check config map
    return config.get(key);
  }

  @Override
  public String getConfig(String key, String defaultValue) {
    return config.getOrDefault(key, defaultValue);
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
