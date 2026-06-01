package com.github.cc11001100.weavergirl.core.plugin;

import com.github.cc11001100.weavergirl.api.plugin.PluginContext;
import com.github.cc11001100.weavergirl.api.registry.InterceptorRegistry;

import java.util.Collections;
import java.util.Map;

/**
 * Default implementation of PluginContext.
 */
public class DefaultPluginContext implements PluginContext {

    private final InterceptorRegistry registry;
    private final Map<String, String> config;
    private final String pluginName;

    public DefaultPluginContext(InterceptorRegistry registry, Map<String, String> config, String pluginName) {
        this.registry = registry;
        this.config = config != null ? config : Collections.emptyMap();
        this.pluginName = pluginName;
    }

    @Override
    public InterceptorRegistry getRegistry() {
        return registry;
    }

    @Override
    public String getConfig(String key) {
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
