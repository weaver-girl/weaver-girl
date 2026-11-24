package com.github.cc11001100.weavergirl.api.config;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Switches for the per-invocation work that is observable but not required for interception to
 * work.
 *
 * <p>Every cost this class gates is <em>observation</em> or convenience: an intercepted method runs
 * and its hooks fire identically with all of them off. What changes is what the agent records, what
 * it bridges into the logging context, and whether the circuit breaker can open. Each switch is
 * something a deployment may legitimately not want to pay for, and the measured cost of each is
 * documented in {@code docs/benchmarks/baseline-v1.md}.
 *
 * <p>A switch can be set four ways, in order of precedence (later wins):
 *
 * <ol>
 *   <li>a system property {@code -Dweavergirl.<key>.enabled=false};
 *   <li>a boolean agent argument, e.g. {@code telemetry=false};
 *   <li>a YAML top-level key of the same name;
 *   <li>at runtime through {@link #setEnabled(String, boolean)} (the dynamic-config listener).
 * </ol>
 *
 * <p>Hot-path access is one {@code volatile} read per switch, so the inlined advice can ask
 * without a map lookup. The class lives in the API module rather than in the engine because both
 * sides need it: the engine's advice reads it, and {@code ReturnVersion} — which the advice calls
 * from the application's class loader — reads the return-snapshot switch from the same place.
 *
 * <p>Defaults are all {@code true}, so an agent that configures nothing behaves exactly as before.
 *
 * @since 1.0.0
 */
public final class PerformanceSwitches {

  /** Per-hook timing reads and the two metric writers. The largest single cost (~200 ns/callback). */
  public static final String TELEMETRY = "telemetry";

  /** The {@code ReturnSnapshot} object built on every successful return (~40 ns/call). */
  public static final String RETURN_SNAPSHOT = "returnSnapshot";

  /** The ThreadContext-to-MDC bridge applied around each intercepted call. */
  public static final String MDC_BRIDGE = "mdc";

  /** The plugin-level metrics mirror written on every callback (on top of the per-hook metrics). */
  public static final String PLUGIN_METRICS = "pluginMetrics";

  /** The per-hook circuit breaker (failure counting and slow-call tripping). */
  public static final String CIRCUIT_BREAKER = "circuitBreaker";

  private static volatile boolean telemetry = readDefault(TELEMETRY, true);
  private static volatile boolean returnSnapshot = readDefault(RETURN_SNAPSHOT, true);
  private static volatile boolean mdcBridge = readDefault(MDC_BRIDGE, true);
  private static volatile boolean pluginMetrics = readDefault(PLUGIN_METRICS, true);
  private static volatile boolean circuitBreaker = readDefault(CIRCUIT_BREAKER, true);

  private PerformanceSwitches() {}

  private static boolean readDefault(String key, boolean fallback) {
    String value = System.getProperty("weavergirl." + key + ".enabled");
    if (value == null) {
      return fallback;
    }
    return "true".equalsIgnoreCase(value.trim());
  }

  // --- Hot-path accessors: one volatile read each ---

  /** @return whether per-hook timing and metric recording should run */
  public static boolean isTelemetryEnabled() {
    return telemetry;
  }

  /** @return whether a return snapshot should be built on each successful return */
  public static boolean isReturnSnapshotEnabled() {
    return returnSnapshot;
  }

  /** @return whether the ThreadContext-to-MDC bridge should be applied */
  public static boolean isMdcBridgeEnabled() {
    return mdcBridge;
  }

  /** @return whether the plugin-level metrics mirror should be written */
  public static boolean isPluginMetricsEnabled() {
    return pluginMetrics;
  }

  /** @return whether the circuit breaker should be consulted and fed */
  public static boolean isCircuitBreakerEnabled() {
    return circuitBreaker;
  }

  /**
   * Apply a switch by its key. Unknown keys are ignored so a shared config map can be passed
   * wholesale.
   *
   * @param key one of the {@code *_ENABLED} constants' key names ({@code telemetry},
   *     {@code returnSnapshot}, {@code mdc}, {@code pluginMetrics}, {@code circuitBreaker})
   * @param value the new value
   * @return true when the key named a known switch
   */
  public static boolean setEnabled(String key, boolean value) {
    switch (key) {
      case TELEMETRY:
        telemetry = value;
        return true;
      case RETURN_SNAPSHOT:
        returnSnapshot = value;
        return true;
      case MDC_BRIDGE:
        mdcBridge = value;
        return true;
      case PLUGIN_METRICS:
        pluginMetrics = value;
        return true;
      case CIRCUIT_BREAKER:
        circuitBreaker = value;
        return true;
      default:
        return false;
    }
  }

  /**
   * Apply the boolean-valued switches found in a config map (agent arguments or flat YAML
   * options). A switch that is also set as a system property is left at that value, so
   * {@code -Dweavergirl.<name>.enabled} always wins — an operator overriding a YAML file from the
   * command line must not be undone by the file it is overriding.
   *
   * <p>Keys that are not switch names are ignored, so a whole config map can be passed in.
   *
   * @param config the configuration map; may be null
   */
  public static void applyFrom(Map<String, String> config) {
    if (config == null) {
      return;
    }
    for (Map.Entry<String, String> entry : config.entrySet()) {
      String key = entry.getKey();
      String value = entry.getValue();
      if (value == null || !isKnownKey(key)) {
        continue;
      }
      if (System.getProperty("weavergirl." + key + ".enabled") != null) {
        continue; // the -D takes precedence
      }
      setEnabled(key, parseBoolean(value));
    }
  }

  /** Whether a key names a switch. */
  public static boolean isKnownKey(String key) {
    return TELEMETRY.equals(key)
        || RETURN_SNAPSHOT.equals(key)
        || MDC_BRIDGE.equals(key)
        || PLUGIN_METRICS.equals(key)
        || CIRCUIT_BREAKER.equals(key);
  }

  /** Lenient boolean parse: anything that is not clearly true reads as false, as agent args do. */
  private static boolean parseBoolean(String value) {
    return "true".equalsIgnoreCase(value.trim());
  }

  /**
   * The current state of every switch, for the status report and diagnostics.
   *
   * @return an ordered map of key to enabled
   */
  public static Map<String, Boolean> snapshot() {
    Map<String, Boolean> state = new LinkedHashMap<>();
    state.put(TELEMETRY, telemetry);
    state.put(RETURN_SNAPSHOT, returnSnapshot);
    state.put(MDC_BRIDGE, mdcBridge);
    state.put(PLUGIN_METRICS, pluginMetrics);
    state.put(CIRCUIT_BREAKER, circuitBreaker);
    return state;
  }

  /** Re-read every system-property default. For tests. */
  public static void reloadFromSystemPropertiesForTest() {
    telemetry = readDefault(TELEMETRY, true);
    returnSnapshot = readDefault(RETURN_SNAPSHOT, true);
    mdcBridge = readDefault(MDC_BRIDGE, true);
    pluginMetrics = readDefault(PLUGIN_METRICS, true);
    circuitBreaker = readDefault(CIRCUIT_BREAKER, true);
  }

  /** Restore every switch to its default (all on). For tests. */
  public static void resetForTest() {
    telemetry = true;
    returnSnapshot = true;
    mdcBridge = true;
    pluginMetrics = true;
    circuitBreaker = true;
  }
}
