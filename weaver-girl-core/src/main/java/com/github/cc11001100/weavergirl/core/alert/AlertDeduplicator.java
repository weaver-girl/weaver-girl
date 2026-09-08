package com.github.cc11001100.weavergirl.core.alert;

import java.util.concurrent.ConcurrentHashMap;

/**
 * Prevents duplicate alerts within a configurable time window.
 *
 * @since 1.2.0
 */
public class AlertDeduplicator {

  private final ConcurrentHashMap<String, Long> lastAlertTime = new ConcurrentHashMap<>();
  private volatile long dedupWindowMs;

  /**
   * Create a deduplicator with the given suppression window.
   *
   * @param dedupWindowMs minimum milliseconds between identical alerts
   */
  public AlertDeduplicator(long dedupWindowMs) {
    this.dedupWindowMs = dedupWindowMs;
  }

  /**
   * Returns true if this alert should be suppressed (duplicate within window). Does NOT update the
   * timestamp when suppressing — only an actually-fired alert resets the window via {@link
   * #recordFire(String)}.
   *
   * @param ruleName the rule name to check
   * @return true if the alert should be suppressed
   */
  public boolean shouldSuppress(String ruleName) {
    long now = System.currentTimeMillis();
    Long last = lastAlertTime.get(ruleName);
    return last != null && (now - last) < dedupWindowMs;
  }

  /**
   * Record that an alert was actually fired, resetting the dedup window.
   *
   * @param ruleName the rule name that fired
   */
  public void recordFire(String ruleName) {
    lastAlertTime.put(ruleName, System.currentTimeMillis());
  }

  /**
   * Update the deduplication window.
   *
   * @param ms new window in milliseconds
   */
  public void setDedupWindowMs(long ms) {
    this.dedupWindowMs = ms;
  }

  /** Clear all deduplication state. */
  public void clear() {
    lastAlertTime.clear();
  }
}
