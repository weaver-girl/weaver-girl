package com.github.cc11001100.weavergirl.api.sampling;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Registry for sampling strategies.
 *
 * <p>The agent asks {@link #getActive()} once per intercepted invocation and lets that strategy have
 * the final say on whether the invocation is intercepted. A plugin registers its own strategy and
 * activates it; the agent's implementation withdraws it again when the plugin is unloaded.
 *
 * @since 1.1.0
 */
public final class SamplingStrategyRegistry {

  private static final ConcurrentHashMap<String, SamplingStrategy> strategies =
      new ConcurrentHashMap<>();
  private static volatile String activeStrategy = "adaptive";

  /** Invocations the active strategy accepted. */
  private static final AtomicLong sampled = new AtomicLong();

  /** Invocations the active strategy rejected. */
  private static final AtomicLong dropped = new AtomicLong();

  /** Invocations where the active strategy threw and the invocation was kept. */
  private static final AtomicLong failures = new AtomicLong();

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
   * Remove one strategy, but only when it is still the instance registered under its name, so a
   * later replacement is left in place. If the removed strategy was the active one, the registry
   * falls back to the built-in adaptive sampling.
   *
   * @param strategy the strategy to remove
   * @return true when that exact instance was removed
   */
  public static boolean unregister(SamplingStrategy strategy) {
    if (strategy == null || strategy.name() == null) {
      return false;
    }
    boolean removed = strategies.remove(strategy.name(), strategy);
    if (removed && strategy.name().equals(activeStrategy)) {
      activeStrategy = "adaptive";
    }
    return removed;
  }

  /** Remove every registered strategy and reset the decision counters. For tests. */
  public static void clear() {
    strategies.clear();
    strategies.put("fixed", new FixedSamplingStrategy(1));
    strategies.put("probabilistic", new ProbabilisticSamplingStrategy(1.0));
    activeStrategy = "adaptive";
    sampled.set(0);
    dropped.set(0);
    failures.set(0);
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

  /**
   * Apply the active strategy to one invocation and count the outcome.
   *
   * <p>Returns {@code true} — keep the invocation — when no strategy is active, so the built-in
   * adaptive sampling stays the only gate. A strategy that throws is counted and the invocation is
   * kept: a broken plugin must not silently stop interception.
   *
   * @return true when the invocation should be intercepted
   */
  public static boolean decide() {
    SamplingStrategy strategy = strategies.get(activeStrategy);
    if (strategy == null) {
      return true;
    }
    try {
      if (strategy.shouldSample()) {
        sampled.incrementAndGet();
        return true;
      }
      dropped.incrementAndGet();
      return false;
    } catch (RuntimeException e) {
      failures.incrementAndGet();
      return true;
    }
  }

  /** Invocations the active strategy accepted since the counters were last reset. */
  public static long getSampled() {
    return sampled.get();
  }

  /** Invocations the active strategy rejected since the counters were last reset. */
  public static long getDropped() {
    return dropped.get();
  }

  /** Invocations where the active strategy threw since the counters were last reset. */
  public static long getFailures() {
    return failures.get();
  }
}
