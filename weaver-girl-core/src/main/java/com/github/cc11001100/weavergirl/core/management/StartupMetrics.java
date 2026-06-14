package com.github.cc11001100.weavergirl.core.management;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Records agent startup duration and per-phase timings.
 * Read by {@link AgentMonitor} and the REST diagnostics endpoint.
 *
 * @since 1.0.0
 */
public final class StartupMetrics {

  public static final long STARTUP_BUDGET_MS = 500L;

  private static volatile long startNanos = 0L;
  private static volatile long endNanos = 0L;
  private static final Map<String, Long> phaseMillis = new LinkedHashMap<>();

  private StartupMetrics() {}

  /** Called at the very first line of premain/agentmain bootstrap. */
  public static void begin() {
    startNanos = System.nanoTime();
  }

  /** Called when bootstrap completes (after plugins loaded + transformers registered). */
  public static void end() {
    endNanos = System.nanoTime();
  }

  /** Record a named phase (e.g. "pluginLoad", "transformerInit"). */
  public static void recordPhase(String name, long durationNanos) {
    phaseMillis.put(name, durationNanos / 1_000_000L);
  }

  /** @return total bootstrap time in ms, or -1 if not finished. */
  public static long totalMillis() {
    if (startNanos == 0L || endNanos == 0L) {
      return -1L;
    }
    return Math.max(0L, (endNanos - startNanos) / 1_000_000L);
  }

  /** @return true if startup exceeded the budget. */
  public static boolean overBudget() {
    return totalMillis() > STARTUP_BUDGET_MS;
  }

  /** @return immutable copy of per-phase timings in ms. */
  public static Map<String, Long> phaseMillis() {
    return new LinkedHashMap<>(phaseMillis);
  }
}
