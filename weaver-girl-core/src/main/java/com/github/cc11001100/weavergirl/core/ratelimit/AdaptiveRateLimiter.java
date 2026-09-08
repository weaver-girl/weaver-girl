package com.github.cc11001100.weavergirl.core.ratelimit;

import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Adaptive rate limiter with sliding window algorithm.
 *
 * <p>Features:
 *
 * <ul>
 *   <li>Token bucket algorithm for rate limiting
 *   <li>Adaptive rate adjustment based on system load
 *   <li>Configurable burst capacity
 *   <li>Statistics tracking (allowed, rejected, total)
 *   <li>Smooth rate changes to avoid sudden spikes
 * </ul>
 *
 * <p>Usage:
 *
 * <pre>
 * AdaptiveRateLimiter limiter = new AdaptiveRateLimiter(1000); // 1000 req/s
 * if (limiter.tryAcquire()) {
 *     // process request
 * } else {
 *     // reject or queue
 * }
 * </pre>
 */
public class AdaptiveRateLimiter {

  private static final Logger log = LoggerFactory.getLogger(AdaptiveRateLimiter.class);

  private static final long ONE_SECOND_MS = 1000;

  // Configuration
  private volatile int permitsPerSecond;
  private volatile int maxBurst;
  private volatile double loadThreshold; // 0.0-1.0

  // Token bucket state
  private final AtomicLong tokens;
  private final AtomicLong lastRefillTime;

  // Adaptive state
  private final AtomicInteger baseRate;
  private final AtomicInteger currentRate;
  private final AtomicInteger adaptiveRate;

  // Statistics
  private final AtomicLong allowedCount;
  private final AtomicLong rejectedCount;
  private final AtomicLong totalWaitTimeMs;

  /** Create a rate limiter with the specified permits per second. */
  public AdaptiveRateLimiter(int permitsPerSecond) {
    this(permitsPerSecond, permitsPerSecond, 0.8);
  }

  /**
   * Create a rate limiter with permits, burst, and load threshold.
   *
   * @param permitsPerSecond base rate of permits per second
   * @param maxBurst maximum burst capacity
   * @param loadThreshold system load threshold (0.0-1.0) for adaptive reduction
   */
  public AdaptiveRateLimiter(int permitsPerSecond, int maxBurst, double loadThreshold) {
    if (permitsPerSecond <= 0) throw new IllegalArgumentException("permitsPerSecond must be > 0");
    if (maxBurst <= 0) throw new IllegalArgumentException("maxBurst must be > 0");

    this.permitsPerSecond = permitsPerSecond;
    this.maxBurst = maxBurst;
    this.loadThreshold = Math.max(0.0, Math.min(1.0, loadThreshold));
    this.baseRate = new AtomicInteger(permitsPerSecond);
    this.currentRate = new AtomicInteger(permitsPerSecond);
    this.adaptiveRate = new AtomicInteger(permitsPerSecond);
    this.tokens = new AtomicLong(maxBurst);
    this.lastRefillTime = new AtomicLong(System.currentTimeMillis());
    this.allowedCount = new AtomicLong();
    this.rejectedCount = new AtomicLong();
    this.totalWaitTimeMs = new AtomicLong();
  }

  /** Try to acquire a permit. Returns true if allowed, false if rate limited. */
  public boolean tryAcquire() {
    return tryAcquire(1);
  }

  /** Try to acquire N permits. */
  public boolean tryAcquire(int permits) {
    if (permits <= 0) return true;

    refillTokens();

    while (true) {
      long current = tokens.get();
      if (current < permits) {
        rejectedCount.incrementAndGet();
        return false;
      }
      if (tokens.compareAndSet(current, current - permits)) {
        allowedCount.incrementAndGet();
        return true;
      }
    }
  }

  /**
   * Try to acquire a permit with a timeout.
   *
   * @param timeoutMs maximum wait time in milliseconds
   * @return true if acquired within timeout
   */
  public boolean tryAcquire(long timeoutMs) {
    return tryAcquire(1, timeoutMs);
  }

  /** Try to acquire N permits with a timeout. */
  public boolean tryAcquire(int permits, long timeoutMs) {
    if (permits <= 0) return true;

    long deadline = System.currentTimeMillis() + timeoutMs;
    while (System.currentTimeMillis() < deadline) {
      if (tryAcquire(permits)) {
        return true;
      }
      try {
        Thread.sleep(1);
      } catch (InterruptedException e) {
        Thread.currentThread().interrupt();
        return false;
      }
    }

    totalWaitTimeMs.addAndGet(timeoutMs);
    rejectedCount.incrementAndGet();
    return false;
  }

  /**
   * Update the adaptive rate based on current system load.
   *
   * @param systemLoad current system load (0.0-1.0)
   */
  public void updateLoad(double systemLoad) {
    if (systemLoad < 0) systemLoad = 0;
    if (systemLoad > 1) systemLoad = 1;

    int base = baseRate.get();

    if (systemLoad > loadThreshold) {
      // Reduce rate proportionally to how far above threshold
      double overRatio = (systemLoad - loadThreshold) / (1.0 - loadThreshold);
      int reduction = (int) (base * overRatio * 0.5); // max 50% reduction
      int newRate = Math.max(1, base - reduction);
      adaptiveRate.set(newRate);
    } else {
      // Restore to base rate
      adaptiveRate.set(base);
    }

    currentRate.set(adaptiveRate.get());
    permitsPerSecond = adaptiveRate.get();
  }

  /** Update the base rate (non-adaptive). */
  public void setBaseRate(int newPermitsPerSecond) {
    if (newPermitsPerSecond <= 0) throw new IllegalArgumentException("Rate must be > 0");
    baseRate.set(newPermitsPerSecond);
    currentRate.set(newPermitsPerSecond);
    adaptiveRate.set(newPermitsPerSecond);
    permitsPerSecond = newPermitsPerSecond;
  }

  private void refillTokens() {
    long now = System.currentTimeMillis();
    long last = lastRefillTime.get();

    if (now <= last) return;

    if (lastRefillTime.compareAndSet(last, now)) {
      long elapsedMs = now - last;
      // Calculate tokens to add based on current rate
      double tokensToAdd = (elapsedMs / 1000.0) * permitsPerSecond;
      long newTokens = Math.min(maxBurst, tokens.get() + (long) tokensToAdd);
      tokens.set(newTokens);
    }
  }

  // --- Accessors ---

  public int getBaseRate() {
    return baseRate.get();
  }

  public int getCurrentRate() {
    return currentRate.get();
  }

  public int getAdaptiveRate() {
    return adaptiveRate.get();
  }

  public int getMaxBurst() {
    return maxBurst;
  }

  public double getLoadThreshold() {
    return loadThreshold;
  }

  public long getAllowedCount() {
    return allowedCount.get();
  }

  public long getRejectedCount() {
    return rejectedCount.get();
  }

  public long getTotalWaitTimeMs() {
    return totalWaitTimeMs.get();
  }

  public long getTotalCount() {
    return allowedCount.get() + rejectedCount.get();
  }

  /** Calculate the rejection rate (0.0 to 1.0). */
  public double getRejectionRate() {
    long total = getTotalCount();
    return total > 0 ? (double) rejectedCount.get() / total : 0.0;
  }

  /** Reset statistics counters. */
  public void resetStats() {
    allowedCount.set(0);
    rejectedCount.set(0);
    totalWaitTimeMs.set(0);
  }

  /** Get available tokens (for monitoring). */
  public long getAvailableTokens() {
    refillTokens();
    return tokens.get();
  }

  @Override
  public String toString() {
    return String.format(
        "AdaptiveRateLimiter{rate=%d/%d, allowed=%d, rejected=%d, rejectionRate=%.2f%%}",
        currentRate.get(),
        baseRate.get(),
        allowedCount.get(),
        rejectedCount.get(),
        getRejectionRate() * 100);
  }
}
