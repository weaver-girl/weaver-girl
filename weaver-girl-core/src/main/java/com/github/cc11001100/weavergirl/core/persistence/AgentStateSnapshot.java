package com.github.cc11001100.weavergirl.core.persistence;

import java.io.*;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Captures and restores Agent runtime state to/from disk. Enables state survival across JVM
 * restarts.
 *
 * <p>Captured state includes:
 *
 * <ul>
 *   <li>Interceptor registration counts
 *   <li>Plugin states (loaded, active, disabled)
 *   <li>Sampling controller state (current rate)
 *   <li>Circuit breaker states (open/closed)
 *   <li>Configuration snapshot
 *   <li>Custom metrics counters
 *   <li>Uptime and total invocation counts
 * </ul>
 *
 * <p>Persistence format: Java Properties file for human readability and editability.
 */
public class AgentStateSnapshot {

  private static final Logger log = LoggerFactory.getLogger(AgentStateSnapshot.class);

  private static final String STATE_DIR = "weaver-girl-state";
  private static final String STATE_FILE = "agent-state.properties";
  private static final String HEADER = "Weaver-Girl Agent State Snapshot";

  // State keys
  public static final String KEY_VERSION = "agent.version";
  public static final String KEY_SNAPSHOT_TIME = "snapshot.time";
  public static final String KEY_INTERCEPTOR_COUNT = "interceptor.count";
  public static final String KEY_TRANSFORMED_CLASS_COUNT = "transformed.class.count";
  public static final String KEY_TOTAL_INVOCATION_COUNT = "invocation.total";
  public static final String KEY_TOTAL_ERROR_COUNT = "error.total";
  public static final String KEY_AGENT_UPTIME_MS = "agent.uptime.ms";
  public static final String KEY_SAMPLING_RATE = "sampling.rate";
  public static final String KEY_CIRCUIT_BREAKER_THRESHOLD = "circuitbreaker.threshold";
  public static final String KEY_CONFIG_PREFIX = "config.";
  public static final String KEY_PLUGIN_PREFIX = "plugin.";
  public static final String KEY_METRIC_PREFIX = "metric.";
  public static final String KEY_HEALTH_PREFIX = "health.";

  private final Map<String, String> state;
  private final long snapshotTimeMs;

  public AgentStateSnapshot() {
    this.state = new ConcurrentHashMap<>();
    this.snapshotTimeMs = System.currentTimeMillis();
    state.put(KEY_SNAPSHOT_TIME, String.valueOf(snapshotTimeMs));
    state.put(KEY_VERSION, "1.0.0");
  }

  public AgentStateSnapshot(Map<String, String> state) {
    this.state = new ConcurrentHashMap<>(state);
    this.snapshotTimeMs = parseLong(state.get(KEY_SNAPSHOT_TIME), System.currentTimeMillis());
  }

  // --- Setters ---

  public AgentStateSnapshot setInterceptorCount(int count) {
    state.put(KEY_INTERCEPTOR_COUNT, String.valueOf(count));
    return this;
  }

  public AgentStateSnapshot setTransformedClassCount(int count) {
    state.put(KEY_TRANSFORMED_CLASS_COUNT, String.valueOf(count));
    return this;
  }

  public AgentStateSnapshot setTotalInvocationCount(long count) {
    state.put(KEY_TOTAL_INVOCATION_COUNT, String.valueOf(count));
    return this;
  }

  public AgentStateSnapshot setTotalErrorCount(long count) {
    state.put(KEY_TOTAL_ERROR_COUNT, String.valueOf(count));
    return this;
  }

  public AgentStateSnapshot setAgentUptimeMs(long uptimeMs) {
    state.put(KEY_AGENT_UPTIME_MS, String.valueOf(uptimeMs));
    return this;
  }

  public AgentStateSnapshot setSamplingRate(int rate) {
    state.put(KEY_SAMPLING_RATE, String.valueOf(rate));
    return this;
  }

  public AgentStateSnapshot setCircuitBreakerThreshold(int threshold) {
    state.put(KEY_CIRCUIT_BREAKER_THRESHOLD, String.valueOf(threshold));
    return this;
  }

  public AgentStateSnapshot setConfig(String key, String value) {
    state.put(KEY_CONFIG_PREFIX + key, value != null ? value : "");
    return this;
  }

  public AgentStateSnapshot setPluginState(String pluginName, String pluginState) {
    state.put(KEY_PLUGIN_PREFIX + pluginName, pluginState != null ? pluginState : "UNKNOWN");
    return this;
  }

  public AgentStateSnapshot setMetric(String metricName, long value) {
    state.put(KEY_METRIC_PREFIX + metricName, String.valueOf(value));
    return this;
  }

  public AgentStateSnapshot setHealth(String componentName, String healthStatus) {
    state.put(KEY_HEALTH_PREFIX + componentName, healthStatus != null ? healthStatus : "UNKNOWN");
    return this;
  }

  public AgentStateSnapshot setCustom(String key, String value) {
    if (key != null && !key.startsWith("agent.")) {
      state.put("custom." + key, value != null ? value : "");
    }
    return this;
  }

  // --- Getters ---

  public int getInterceptorCount() {
    return (int) parseLong(state.get(KEY_INTERCEPTOR_COUNT), 0);
  }

  public int getTransformedClassCount() {
    return (int) parseLong(state.get(KEY_TRANSFORMED_CLASS_COUNT), 0);
  }

  public long getTotalInvocationCount() {
    return parseLong(state.get(KEY_TOTAL_INVOCATION_COUNT), 0);
  }

  public long getTotalErrorCount() {
    return parseLong(state.get(KEY_TOTAL_ERROR_COUNT), 0);
  }

  public long getAgentUptimeMs() {
    return parseLong(state.get(KEY_AGENT_UPTIME_MS), 0);
  }

  public int getSamplingRate() {
    return (int) parseLong(state.get(KEY_SAMPLING_RATE), 1);
  }

  public int getCircuitBreakerThreshold() {
    return (int) parseLong(state.get(KEY_CIRCUIT_BREAKER_THRESHOLD), 5);
  }

  public long getSnapshotTimeMs() {
    return snapshotTimeMs;
  }

  public String getConfig(String key) {
    return state.get(KEY_CONFIG_PREFIX + key);
  }

  public String getPluginState(String pluginName) {
    return state.getOrDefault(KEY_PLUGIN_PREFIX + pluginName, "UNKNOWN");
  }

  public long getMetric(String metricName) {
    return parseLong(state.get(KEY_METRIC_PREFIX + metricName), 0);
  }

  public String getHealth(String componentName) {
    return state.getOrDefault(KEY_HEALTH_PREFIX + componentName, "UNKNOWN");
  }

  public String getCustom(String key) {
    return state.get("custom." + key);
  }

  public Map<String, String> getAllConfig() {
    Map<String, String> configs = new HashMap<>();
    for (Map.Entry<String, String> entry : state.entrySet()) {
      if (entry.getKey().startsWith(KEY_CONFIG_PREFIX)) {
        configs.put(entry.getKey().substring(KEY_CONFIG_PREFIX.length()), entry.getValue());
      }
    }
    return configs;
  }

  public Map<String, String> getAllPluginStates() {
    Map<String, String> plugins = new HashMap<>();
    for (Map.Entry<String, String> entry : state.entrySet()) {
      if (entry.getKey().startsWith(KEY_PLUGIN_PREFIX)) {
        plugins.put(entry.getKey().substring(KEY_PLUGIN_PREFIX.length()), entry.getValue());
      }
    }
    return plugins;
  }

  public Map<String, String> getAllMetrics() {
    Map<String, String> metrics = new HashMap<>();
    for (Map.Entry<String, String> entry : state.entrySet()) {
      if (entry.getKey().startsWith(KEY_METRIC_PREFIX)) {
        metrics.put(entry.getKey().substring(KEY_METRIC_PREFIX.length()), entry.getValue());
      }
    }
    return metrics;
  }

  public Map<String, String> getAllHealth() {
    Map<String, String> health = new HashMap<>();
    for (Map.Entry<String, String> entry : state.entrySet()) {
      if (entry.getKey().startsWith(KEY_HEALTH_PREFIX)) {
        health.put(entry.getKey().substring(KEY_HEALTH_PREFIX.length()), entry.getValue());
      }
    }
    return health;
  }

  public Map<String, String> getAllState() {
    return Collections.unmodifiableMap(state);
  }

  public int size() {
    return state.size();
  }

  // --- Persistence ---

  /** Save state snapshot to the default location. */
  public Path save() throws IOException {
    return save(Paths.get(STATE_DIR));
  }

  /** Save state snapshot to the specified directory. */
  public Path save(Path directory) throws IOException {
    Files.createDirectories(directory);
    Path file = directory.resolve(STATE_FILE);

    Properties props = new Properties();
    props.putAll(state);

    try (OutputStream os = Files.newOutputStream(file)) {
      props.store(os, HEADER + " - " + new Date(snapshotTimeMs));
    }

    log.info("[AgentState] Snapshot saved to {} ({} entries)", file, state.size());
    return file;
  }

  /** Load state snapshot from the default location. Returns null if no snapshot exists. */
  public static AgentStateSnapshot load() throws IOException {
    return load(Paths.get(STATE_DIR));
  }

  /** Load state snapshot from the specified directory. Returns null if no snapshot exists. */
  public static AgentStateSnapshot load(Path directory) throws IOException {
    Path file = directory.resolve(STATE_FILE);
    if (!Files.exists(file)) {
      return null;
    }

    Properties props = new Properties();
    try (InputStream is = Files.newInputStream(file)) {
      props.load(is);
    }

    Map<String, String> state = new HashMap<>();
    for (String key : props.stringPropertyNames()) {
      state.put(key, props.getProperty(key));
    }

    log.info("[AgentState] Snapshot loaded from {} ({} entries)", file, state.size());
    return new AgentStateSnapshot(state);
  }

  /** Delete the state snapshot from the default location. */
  public static boolean delete() {
    return delete(Paths.get(STATE_DIR));
  }

  /** Delete the state snapshot from the specified directory. */
  public static boolean delete(Path directory) {
    try {
      Path file = directory.resolve(STATE_FILE);
      boolean deleted = Files.deleteIfExists(file);
      if (deleted) {
        log.info("[AgentState] Snapshot deleted: {}", file);
      }
      return deleted;
    } catch (IOException e) {
      log.warn("[AgentState] Failed to delete snapshot: {}", e.getMessage());
      return false;
    }
  }

  /** Check if a state snapshot exists at the default location. */
  public static boolean exists() {
    return exists(Paths.get(STATE_DIR));
  }

  /** Check if a state snapshot exists at the specified directory. */
  public static boolean exists(Path directory) {
    return Files.exists(directory.resolve(STATE_FILE));
  }

  @Override
  public String toString() {
    return "AgentStateSnapshot{"
        + "entries="
        + state.size()
        + ", snapshotTime="
        + new Date(snapshotTimeMs)
        + ", interceptors="
        + getInterceptorCount()
        + ", invocations="
        + getTotalInvocationCount()
        + '}';
  }

  private static long parseLong(String value, long defaultValue) {
    if (value == null || value.isEmpty()) return defaultValue;
    try {
      return Long.parseLong(value);
    } catch (NumberFormatException e) {
      return defaultValue;
    }
  }
}
