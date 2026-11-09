package com.github.cc11001100.weavergirl.api.plugin;

/**
 * Metadata about a loaded plugin, including its state, registered interceptors, and performance metrics.
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
  private final long invocationCount;
  private final long errorCount;
  private final double avgDurationMs;
  private final long lastInvocationAt;

  /**
   * Create plugin info with performance metrics.
   *
   * @param name plugin name
   * @param state current state
   * @param version plugin version (may be null)
   * @param interceptorCount number of registered interceptors
   * @param loadedAt timestamp when loaded (epoch millis)
   * @param lastStateChangedAt timestamp of last state change
   * @param invocationCount total number of interceptor invocations
   * @param errorCount total number of interceptor errors
   * @param avgDurationMs average callback duration in milliseconds
   * @param lastInvocationAt timestamp of last interceptor invocation
   * @since 1.2.0
   */
  public PluginInfo(
      String name,
      PluginState state,
      String version,
      int interceptorCount,
      long loadedAt,
      long lastStateChangedAt,
      long invocationCount,
      long errorCount,
      double avgDurationMs,
      long lastInvocationAt) {
    this.name = name;
    this.state = state;
    this.version = version;
    this.interceptorCount = interceptorCount;
    this.loadedAt = loadedAt;
    this.lastStateChangedAt = lastStateChangedAt;
    this.invocationCount = invocationCount;
    this.errorCount = errorCount;
    this.avgDurationMs = avgDurationMs;
    this.lastInvocationAt = lastInvocationAt;
  }

  /**
   * Create plugin info without performance metrics (backward compatibility).
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
    this(name, state, version, interceptorCount, loadedAt, lastStateChangedAt, 0, 0, 0.0, 0);
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

  /** Total number of interceptor invocations for this plugin. */
  public long getInvocationCount() {
    return invocationCount;
  }

  /** Total number of interceptor errors for this plugin. */
  public long getErrorCount() {
    return errorCount;
  }

  /** Average callback duration in milliseconds. */
  public double getAvgDurationMs() {
    return avgDurationMs;
  }

  /** Timestamp (epoch millis) of the last interceptor invocation. */
  public long getLastInvocationAt() {
    return lastInvocationAt;
  }

  @Override
  public String toString() {
    return "PluginInfo{name='"
        + name
        + "', state="
        + state
        + ", interceptors="
        + interceptorCount
        + ", invocations="
        + invocationCount
        + ", errors="
        + errorCount
        + ", avgDurationMs="
        + String.format("%.2f", avgDurationMs)
        + "}";
  }
}
