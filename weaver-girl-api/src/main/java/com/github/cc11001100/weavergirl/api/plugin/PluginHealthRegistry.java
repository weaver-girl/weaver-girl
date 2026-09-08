package com.github.cc11001100.weavergirl.api.plugin;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Registry for tracking plugin health status.
 *
 * @since 1.2.0
 */
public final class PluginHealthRegistry {

  private static final ConcurrentHashMap<String, PluginHealth> healthMap =
      new ConcurrentHashMap<>();

  private PluginHealthRegistry() {}

  /** Report health for a plugin. */
  public static void report(PluginHealth health) {
    if (health != null && health.getPluginName() != null) {
      healthMap.put(health.getPluginName(), health);
    }
  }

  /** Report a plugin as healthy. */
  public static void reportHealthy(String pluginName) {
    healthMap.put(pluginName, PluginHealth.healthy(pluginName));
  }

  /** Report a plugin as degraded. */
  public static void reportDegraded(String pluginName, String message) {
    healthMap.put(pluginName, PluginHealth.degraded(pluginName, message));
  }

  /** Report a plugin as unhealthy. */
  public static void reportUnhealthy(String pluginName, String message) {
    healthMap.put(pluginName, PluginHealth.unhealthy(pluginName, message));
  }

  /** Get health status for a specific plugin. */
  public static PluginHealth getHealth(String pluginName) {
    return pluginName != null ? healthMap.get(pluginName) : null;
  }

  /** Get all health statuses. */
  public static Map<String, PluginHealth> getAllHealth() {
    return Collections.unmodifiableMap(new LinkedHashMap<>(healthMap));
  }

  /** Check if all plugins are healthy. */
  public static boolean allHealthy() {
    return healthMap.values().stream().allMatch(PluginHealth::isHealthy);
  }

  /** Get unhealthy plugin names. */
  public static List<String> getUnhealthyPlugins() {
    List<String> result = new ArrayList<>();
    for (Map.Entry<String, PluginHealth> e : healthMap.entrySet()) {
      if (!e.getValue().isHealthy()) {
        result.add(e.getKey());
      }
    }
    return result;
  }

  /** Clear all health data. */
  public static void clear() {
    healthMap.clear();
  }
}
