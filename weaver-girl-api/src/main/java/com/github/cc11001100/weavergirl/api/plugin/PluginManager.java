package com.github.cc11001100.weavergirl.api.plugin;

import java.util.List;
import java.util.Optional;

/**
 * Manages plugin lifecycle at runtime — load, enable, disable, and unload.
 *
 * <p>Complements {@link PluginLoader} which handles initial loading. The PluginManager enables
 * runtime management of already-loaded plugins.
 *
 * <h3>Example usage:</h3>
 *
 * <pre>
 * PluginManager pm = PluginManager.getInstance();
 *
 * // Disable a plugin at runtime
 * pm.disablePlugin("redis");
 *
 * // Re-enable it later
 * pm.enablePlugin("redis");
 *
 * // Query state
 * PluginInfo info = pm.getPluginInfo("redis");
 * pm.getAllPluginInfo().forEach(System.out::println);
 * </pre>
 *
 * @since 1.1.0
 */
public interface PluginManager {

  /**
   * Get the singleton instance.
   *
   * @return the global PluginManager instance
   */
  static PluginManager getInstance() {
    return PluginManagerHolder.getInstance();
  }

  /**
   * Disable a plugin at runtime. Its interceptors will be unregistered but the plugin instance is
   * retained for potential re-enable.
   *
   * @param pluginName the plugin to disable
   * @return true if the plugin was successfully disabled
   */
  boolean disablePlugin(String pluginName);

  /**
   * Re-enable a previously disabled plugin. Its interceptors will be re-registered.
   *
   * @param pluginName the plugin to enable
   * @return true if the plugin was successfully enabled
   */
  boolean enablePlugin(String pluginName);

  /**
   * Completely unload and destroy a plugin. Cannot be re-enabled.
   *
   * @param pluginName the plugin to unload
   * @return true if the plugin was successfully unloaded
   */
  boolean unloadPlugin(String pluginName);

  /**
   * Get information about a specific plugin.
   *
   * @param pluginName the plugin name
   * @return plugin info, or empty if not found
   */
  Optional<PluginInfo> getPluginInfo(String pluginName);

  /**
   * Get information about all managed plugins.
   *
   * @return list of all plugin info
   */
  List<PluginInfo> getAllPluginInfo();

  /**
   * Check if a plugin is currently active.
   *
   * @param pluginName the plugin name
   * @return true if the plugin exists and is in ACTIVE state
   */
  boolean isPluginActive(String pluginName);

  /**
   * Get the number of active plugins.
   *
   * @return count of plugins in ACTIVE state
   */
  int getActivePluginCount();
}
