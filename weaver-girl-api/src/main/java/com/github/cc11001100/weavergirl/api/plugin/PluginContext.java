package com.github.cc11001100.weavergirl.api.plugin;

import com.github.cc11001100.weavergirl.api.registry.InterceptorRegistry;
import java.util.Map;

/**
 * Context provided to plugins during initialization, giving them access to agent services and
 * configuration.
 *
 * <p>This interface is the primary gateway for plugins to interact with the agent runtime. It is
 * passed to {@link WeaverPlugin#init(PluginContext)} before interceptors are registered.
 *
 * <h3>Configuration sources</h3>
 *
 * <p>Configuration keys come from two sources, resolved in this priority order:
 *
 * <ol>
 *   <li><strong>System properties</strong> &mdash; keys prefixed with {@code
 *       weavergirl.plugin.&lt;pluginName&gt;.} override YAML values
 *   <li><strong>YAML config file</strong> &mdash; the {@code plugins.&lt;pluginName&gt;} section of
 *       the weaver-girl YAML configuration
 * </ol>
 *
 * <h3>Thread safety</h3>
 *
 * <p>The {@link #getRegistry()} method returns the same registry instance for all calls. The
 * registry itself is thread-safe. Configuration values are immutable after initialization.
 *
 * <h3>Usage example</h3>
 *
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
   * <p>Plugins can use this to dynamically register or unregister interceptors at runtime, beyond
   * what is done in {@link WeaverPlugin#registerInterceptors(InterceptorRegistry)}.
   *
   * @return the interceptor registry, never null
   */
  InterceptorRegistry getRegistry();

  /**
   * Get a configuration value by key.
   *
   * <p>Configuration keys are derived from the YAML config or system properties. System properties
   * override YAML values. Returns {@code null} if the key is not set in either source.
   *
   * @param key the configuration key
   * @return the configuration value, or null if not set
   */
  String getConfig(String key);

  /**
   * Get a configuration value by key with a default fallback.
   *
   * <p>If the key is not set in either the YAML config or system properties, the provided default
   * value is returned.
   *
   * @param key the configuration key
   * @param defaultValue the value to return if the key is not set
   * @return the configuration value, or defaultValue if not set
   */
  String getConfig(String key, String defaultValue);

  /**
   * Get all configuration properties as an unmodifiable map.
   *
   * <p>The returned map contains the merged view of YAML config and system properties, with system
   * properties taking precedence over YAML values.
   *
   * @return an unmodifiable view of all configuration properties
   */
  Map<String, String> getAllConfig();

  /**
   * Returns the plugin's own name (same as {@link WeaverPlugin#name()}).
   *
   * <p>This is useful for scoping configuration keys or logging messages with the plugin identity.
   *
   * @return the plugin name, never null
   */
  String getPluginName();

  /**
   * Get a configuration value as a long, with validation. If the value cannot be parsed, logs a
   * warning and returns the default.
   *
   * @param key the configuration key
   * @param defaultValue the value to return if the key is not set or invalid
   * @return the parsed long value, or defaultValue if invalid or missing
   */
  default long getConfigLong(String key, long defaultValue) {
    String value = getConfig(key);
    if (value == null) return defaultValue;
    try {
      return Long.parseLong(value);
    } catch (NumberFormatException e) {
      System.err.println(
          "[weaver-girl] WARNING: Invalid config '"
              + key
              + "="
              + value
              + "' for plugin '"
              + getPluginName()
              + "': expected integer, using default "
              + defaultValue);
      return defaultValue;
    }
  }

  /**
   * Get a configuration value as an int, with validation. If the value cannot be parsed, logs a
   * warning and returns the default.
   *
   * @param key the configuration key
   * @param defaultValue the value to return if the key is not set or invalid
   * @return the parsed int value, or defaultValue if invalid or missing
   */
  default int getConfigInt(String key, int defaultValue) {
    return (int) getConfigLong(key, defaultValue);
  }

  /**
   * Get a configuration value as a boolean, with validation. Accepts "true"/"false"
   * (case-insensitive). Invalid values log a warning and return the default.
   *
   * @param key the configuration key
   * @param defaultValue the value to return if the key is not set or invalid
   * @return the parsed boolean value, or defaultValue if invalid or missing
   */
  default boolean getConfigBoolean(String key, boolean defaultValue) {
    String value = getConfig(key);
    if (value == null) return defaultValue;
    if ("true".equalsIgnoreCase(value)) return true;
    if ("false".equalsIgnoreCase(value)) return false;
    System.err.println(
        "[weaver-girl] WARNING: Invalid config '"
            + key
            + "="
            + value
            + "' for plugin '"
            + getPluginName()
            + "': expected true/false, using default "
            + defaultValue);
    return defaultValue;
  }

  /**
   * Get a configuration value, validating it against a set of allowed values. If the value is not
   * in the allowed set, logs a warning and returns the default.
   *
   * @param key the configuration key
   * @param defaultValue the value to return if the key is not set or invalid
   * @param allowedValues the set of valid values
   * @return the config value, or defaultValue if invalid or missing
   */
  default String getConfigEnum(String key, String defaultValue, String... allowedValues) {
    String value = getConfig(key);
    if (value == null) return defaultValue;
    for (String allowed : allowedValues) {
      if (allowed.equalsIgnoreCase(value)) return allowed;
    }
    StringBuilder allowedList = new StringBuilder();
    for (int i = 0; i < allowedValues.length; i++) {
      if (i > 0) allowedList.append(", ");
      allowedList.append(allowedValues[i]);
    }
    System.err.println(
        "[weaver-girl] WARNING: Invalid config '"
            + key
            + "="
            + value
            + "' for plugin '"
            + getPluginName()
            + "': expected one of ["
            + allowedList
            + "], using default "
            + defaultValue);
    return defaultValue;
  }
}
