package com.github.cc11001100.weavergirl.api.plugin;

/**
 * Metadata about a loaded plugin, including its state and registered interceptors.
 *
 * @since 1.1.0
 */
public class PluginInfo {

  private final String name;
  private final PluginState state;
  private final String version;
  private final int interceptorCount;
  private final long loadedAt;
  private final long lastStateChangedAt;

  /**
   * Create plugin info.
   *
   * @param name plugin name
   * @param state current state
   * @param version plugin version (may be null)
   * @param interceptorCount number of registered interceptors
   * @param loadedAt timestamp when loaded (epoch millis)
   * @param lastStateChangedAt timestamp of last state change
   */
  public PluginInfo(
      String name,
      PluginState state,
      String version,
      int interceptorCount,
      long loadedAt,
      long lastStateChangedAt) {
    this.name = name;
    this.state = state;
    this.version = version;
    this.interceptorCount = interceptorCount;
    this.loadedAt = loadedAt;
    this.lastStateChangedAt = lastStateChangedAt;
  }

  /** The plugin name. */
  public String getName() {
    return name;
  }

  /** The current plugin state. */
  public PluginState getState() {
    return state;
  }

  /** The plugin version, or null if not specified. */
  public String getVersion() {
    return version;
  }

  /** Number of interceptors currently registered by this plugin. */
  public int getInterceptorCount() {
    return interceptorCount;
  }

  /** Timestamp (epoch millis) when the plugin was loaded. */
  public long getLoadedAt() {
    return loadedAt;
  }

  /** Timestamp (epoch millis) of the last state change. */
  public long getLastStateChangedAt() {
    return lastStateChangedAt;
  }

  @Override
  public String toString() {
    return "PluginInfo{name='"
        + name
        + "', state="
        + state
        + ", interceptors="
        + interceptorCount
        + "}";
  }
}
