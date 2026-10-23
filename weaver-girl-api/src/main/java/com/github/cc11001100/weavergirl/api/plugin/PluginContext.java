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
 * <p>Configuration keys are resolved in this priority order, most specific first:
 *
 * <ol>
 *   <li><strong>Namespaced system property</strong> &mdash; {@code
 *       weavergirl.plugin.&lt;pluginName&gt;.&lt;key&gt;}
 *   <li><strong>YAML plugin section</strong> &mdash; {@code plugins.&lt;pluginName&gt;.&lt;key&gt;}
 *   <li><strong>Plain system property</strong> &mdash; {@code -D&lt;key&gt;=...}, kept for launch
 *       lines written before plugin sections existed
 *   <li><strong>Agent arguments / top-level YAML options</strong> &mdash; the global key, for example
 *       {@code metricsPort=9400}
 * </ol>
 *
 * <p>Both {@link #getConfig(String)} and {@link #getConfig(String, String)} use this chain, so a
 * default value never hides a system property or a plugin-scoped YAML entry.
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
   * Get the configuration map as an unmodifiable view.
   *
   * <p>This is the flat map the agent was launched with: YAML plugin sections appear as {@code
   * plugins.&lt;name&gt;.&lt;key&gt;} entries and agent arguments keep their plain keys. System
   * properties are not copied into it, because they can change after startup; use {@link
   * #getConfig(String)} to read a key through the full precedence chain.
   *
   * @return an unmodifiable view of the merged YAML and launch configuration
   */
  Map<String, String> getAllConfig();

  /**
   * A logger named after this plugin, writing through the agent's logging.
   *
   * <p>A drop-in plugin is loaded by its own class loader, so a logger it builds itself resolves
   * against the application's logging and can end up silent or bound to a different configuration
   * than the agent's. This logger is created by the agent, so it always reaches the agent's log.
   *
   * <p>Default implementation returns a logger that writes to standard error, which is what a test
   * double of this interface gets. The agent's own implementation returns its real logger.
   *
   * @return a logger for this plugin, never null
   * @since 1.0.0
   */
  default PluginLogger getLogger() {
    return new PluginLogger() {
      @Override
      public void info(String message) {
        System.err.println("[weaver-girl] [" + getPluginName() + "] " + message);
      }

      @Override
      public void warn(String message) {
        System.err.println("[weaver-girl] [" + getPluginName() + "] WARN " + message);
      }

      @Override
      public void error(String message, Throwable error) {
        System.err.println("[weaver-girl] [" + getPluginName() + "] ERROR " + message);
        if (error != null) {
          error.printStackTrace(System.err);
        }
      }
    };
  }

  /**
   * Publish {@code implementation} as this plugin's provider of {@code service}, so other plugins can find
   * it through {@link PluginServices#get}. The implementation is withdrawn automatically when this plugin
   * is unloaded or destroyed, which a registration made on {@link PluginServices} directly is not.
   *
   * <p>Default implementation delegates to {@link PluginServices#register} without remembering the plugin,
   * which is all a test double of this interface can do. The agent's implementation tracks the
   * registration and removes it at unload.
   *
   * @param service the interface other plugins look up
   * @param implementation this plugin's implementation of it
   * @since 1.0.0
   */
  default void registerService(Class<?> service, Object implementation) {
    PluginServices.register(service, implementation);
  }

  /**
   * Whether {@code className} can be seen from {@code classLoader} without loading it.
   *
   * <p>A plugin decides from this whether the library it instruments is on the classpath. Loading the class
   * to find out would define it before the transformer is installed, so the interceptor registered for it
   * would never be woven in. Presence is decided from the class file being visible as a resource.
   *
   * <p>Default implementation looks through {@code classLoader}, then the context loader, then the loader
   * that defined this interface. An agent supplies the loader its plugins were loaded by, which is the one
   * that can see both the agent and the application.
   *
   * @param classLoader the loader to look through, or null to look only through the context and system loaders
   * @param className a fully-qualified class name, for example {@code "io.grpc.Server"}
   * @return true when the class file is visible
   * @since 1.0.0
   */
  default boolean classPresent(ClassLoader classLoader, String className) {
    if (className == null || className.isEmpty()) {
      return false;
    }
    String resource = className.replace('.', '/') + ".class";
    // The loader that defined the plugin is child-first and only carries the agent, so the library it
    // instruments is visible through its parent — the application loader — and not through it. Walk the
    // whole chain. A loader is only asked once; a child-first loader delegates getResource to its parent
    // anyway, and asking twice would report the same class twice.
    java.util.Set<ClassLoader> seen = new java.util.HashSet<ClassLoader>();
    for (ClassLoader current = classLoader; current != null; current = current.getParent()) {
      if (seen.add(current) && current.getResource(resource) != null) {
        return true;
      }
    }
    ClassLoader context = Thread.currentThread().getContextClassLoader();
    if (context != null && seen.add(context) && context.getResource(resource) != null) {
      return true;
    }
    ClassLoader api = PluginContext.class.getClassLoader();
    return api != null && seen.add(api) && api.getResource(resource) != null;
  }

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
