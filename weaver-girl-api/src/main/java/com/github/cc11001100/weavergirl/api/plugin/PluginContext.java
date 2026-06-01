package com.github.cc11001100.weavergirl.api.plugin;

import com.github.cc11001100.weavergirl.api.registry.InterceptorRegistry;

import java.util.Map;

/**
 * Context provided to plugins during initialization, giving them access to
 * agent services and configuration.
 *
 * <p>This interface is the primary gateway for plugins to interact with the agent
 * runtime. It is passed to {@link WeaverPlugin#init(PluginContext)} before interceptors
 * are registered.</p>
 *
 * <h3>Configuration sources</h3>
 * <p>Configuration keys come from two sources, resolved in this priority order:</p>
 * <ol>
 *   <li><strong>System properties</strong> &mdash; keys prefixed with
 *       {@code weavergirl.plugin.&lt;pluginName&gt;.} override YAML values</li>
 *   <li><strong>YAML config file</strong> &mdash; the {@code plugins.&lt;pluginName&gt;} section
 *       of the weaver-girl YAML configuration</li>
 * </ol>
 *
 * <h3>Thread safety</h3>
 * <p>The {@link #getRegistry()} method returns the same registry instance for all calls.
 * The registry itself is thread-safe. Configuration values are immutable after initialization.</p>
 *
 * <h3>Usage example</h3>
 * <pre>
 * &#64;Override
 * public void init(PluginContext context) {
 *     String threshold = context.getConfig("threshold", "1000");
 *     String mode = context.getConfig("mode"); // may return null
 *     InterceptorRegistry registry = context.getRegistry();
 * }</pre>
 *
 * @see WeaverPlugin
 * @see InterceptorRegistry
 * @since 1.0.0
 */
public interface PluginContext {

    /**
     * Returns the interceptor registry.
     *
     * <p>Plugins can use this to dynamically register or unregister interceptors at runtime,
     * beyond what is done in {@link WeaverPlugin#registerInterceptors(InterceptorRegistry)}.</p>
     *
     * @return the interceptor registry, never null
     */
    InterceptorRegistry getRegistry();

    /**
     * Get a configuration value by key.
     *
     * <p>Configuration keys are derived from the YAML config or system properties.
     * System properties override YAML values. Returns {@code null} if the key is not set
     * in either source.</p>
     *
     * @param key the configuration key
     * @return the configuration value, or null if not set
     */
    String getConfig(String key);

    /**
     * Get a configuration value by key with a default fallback.
     *
     * <p>If the key is not set in either the YAML config or system properties,
     * the provided default value is returned.</p>
     *
     * @param key         the configuration key
     * @param defaultValue the value to return if the key is not set
     * @return the configuration value, or defaultValue if not set
     */
    String getConfig(String key, String defaultValue);

    /**
     * Get all configuration properties as an unmodifiable map.
     *
     * <p>The returned map contains the merged view of YAML config and system properties,
     * with system properties taking precedence over YAML values.</p>
     *
     * @return an unmodifiable view of all configuration properties
     */
    Map<String, String> getAllConfig();

    /**
     * Returns the plugin's own name (same as {@link WeaverPlugin#name()}).
     *
     * <p>This is useful for scoping configuration keys or logging messages
     * with the plugin identity.</p>
     *
     * @return the plugin name, never null
     */
    String getPluginName();
}