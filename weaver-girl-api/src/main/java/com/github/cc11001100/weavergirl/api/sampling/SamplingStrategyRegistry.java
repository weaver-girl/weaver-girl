package com.github.cc11001100.weavergirl.api.sampling;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Registry for sampling strategies.
 *
 * @since 1.1.0
 */
public final class SamplingStrategyRegistry {

  private static final ConcurrentHashMap<String, SamplingStrategy> strategies =
      new ConcurrentHashMap<>();
  private static volatile String activeStrategy = "adaptive";

  static {
    // Register built-in strategies
    strategies.put("fixed", new FixedSamplingStrategy(1));
    strategies.put("probabilistic", new ProbabilisticSamplingStrategy(1.0));
  }

  private SamplingStrategyRegistry() {}

  /**
   * Register a custom sampling strategy.
   *
   * @param strategy the strategy to register
   */
  public static void register(SamplingStrategy strategy) {
    if (strategy != null && strategy.name() != null) {
      strategies.put(strategy.name(), strategy);
    }
  }

  /**
   * Get a strategy by name.
   *
   * @param name the strategy name
   * @return the strategy, or null if not found
   */
  public static SamplingStrategy get(String name) {
    return name != null ? strategies.get(name) : null;
  }

  /**
   * Get the currently active strategy.
   *
   * @return the active strategy
   */
  public static SamplingStrategy getActive() {
    return strategies.getOrDefault(activeStrategy, strategies.get("fixed"));
  }

  /**
   * Set the active strategy by name.
   *
   * @param name the strategy to activate
   * @return true if the strategy was found and activated
   */
  public static boolean setActive(String name) {
    if (name != null && strategies.containsKey(name)) {
      activeStrategy = name;
      return true;
    }
    return false;
  }

  /** Get the name of the active strategy. */
  public static String getActiveName() {
    return activeStrategy;
  }

  /** Get all registered strategy names. */
  public static Set<String> getStrategyNames() {
    return Collections.unmodifiableSet(strategies.keySet());
  }
}
