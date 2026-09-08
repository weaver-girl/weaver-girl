package com.github.cc11001100.weavergirl.core.plugin;

import com.github.cc11001100.weavergirl.api.ValidationUtils;
import com.github.cc11001100.weavergirl.api.plugin.PluginContext;
import com.github.cc11001100.weavergirl.api.registry.InterceptorRegistry;
import java.util.Collections;
import java.util.Map;

/** Default implementation of PluginContext. */
public class DefaultPluginContext implements PluginContext {

  private final InterceptorRegistry registry;
  private final Map<String, String> config;
  private final String pluginName;

  public DefaultPluginContext(
      InterceptorRegistry registry, Map<String, String> config, String pluginName) {
    this.registry = ValidationUtils.requireNonNull(registry, "registry");
    this.config = config != null ? config : Collections.emptyMap();
    this.pluginName = ValidationUtils.requireNonEmpty(pluginName, "pluginName");
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
}
