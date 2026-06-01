package com.github.cc11001100.weavergirl.api.plugin;

import com.github.cc11001100.weavergirl.api.registry.InterceptorRegistry;

import java.util.Map;

/**
 * Context provided to plugins during initialization.
 * Gives plugins access to agent services and configuration.
 *
 * <p>This interface is the primary gateway for plugins to interact
 * with the agent runtime. It is passed to {@link WeaverPlugin#init(PluginContext)}
 * before interceptors are registered.</p>
 */
public interface PluginContext {

    /**
     * Get the interceptor registry.
     * Plugins can use this to dynamically register/unregister interceptors at runtime.
     */
    InterceptorRegistry getRegistry();

    /**
     * Get a configuration value by key.
     * Configuration keys are derived from the YAML config or system properties.
     *
     * @param key the configuration key
     * @return the configuration value, or null if not set
     */
    String getConfig(String key);

    /**
     * Get a configuration value by key with a default.
     *
     * @param key the configuration key
     * @param defaultValue the value to return if the key is not set
     * @return the configuration value, or defaultValue if not set
     */
    String getConfig(String key, String defaultValue);

    /**
     * Get all configuration properties.
     *
     * @return an unmodifiable view of all configuration properties
     */
    Map<String, String> getAllConfig();

    /**
     * Get the plugin's own name (same as {@link WeaverPlugin#name()}).
     */
    String getPluginName();
}
