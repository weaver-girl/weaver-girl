package com.github.cc11001100.weavergirl.core.sampling;

import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Controls interceptor sampling rate based on system load. When the invocation rate exceeds a
 * configurable threshold, the sampling rate is automatically reduced.
 *
 * <p>Sampling is based on a counter: every Nth invocation is sampled. A rate of 1 means every
 * invocation is intercepted (default). A rate of 10 means 1 in 10 invocations is intercepted.
 *
 * <p>Usage in Interceptor.before():
 *
 * <pre>
 *   if (!SamplingController.getInstance().shouldSample()) {
 *       return; // skip this invocation
 *   }
 * </pre>
 */
public class SamplingController {

  private static final SamplingController INSTANCE = new SamplingController();

  private static final Logger log = LoggerFactory.getLogger(SamplingController.class);

  private final AtomicInteger samplingRate = new AtomicInteger(1); // 1 = sample all
  private final AtomicLong invocationCounter = new AtomicLong(0);
  private volatile int maxRate = 100; // maximum sampling rate (1 in 100)
  private volatile long thresholdInvocationsPerSecond = 10000; // per-second threshold

  private SamplingController() {}

  public static SamplingController getInstance() {
    return INSTANCE;
  }

  /**
   * Check if the current invocation should be sampled.
   *
   * @return true if the invocation should be intercepted, false to skip
   */
  public boolean shouldSample() {
    long count = invocationCounter.incrementAndGet();
    int rate = samplingRate.get();
    if (rate <= 1) {
      return true; // sample everything
    }
    return count % rate == 0;
  }

  /** Get the current sampling rate. A rate of 1 means sample all, 10 means 1 in 10, etc. */
  public int getSamplingRate() {
    return samplingRate.get();
  }

  /**
   * Set the sampling rate manually.
   *
   * @param rate the sampling rate (must be >= 1)
   */
  public void setSamplingRate(int rate) {
    if (rate < 1) {
      rate = 1;
    }
    if (rate > maxRate) {
      rate = maxRate;
    }
    int oldRate = samplingRate.getAndSet(rate);
    if (oldRate != rate) {
      log.info("Sampling rate changed from {} to {}", oldRate, rate);
    }
  }

  /**
   * Adapt the sampling rate based on current load. Called periodically by the internal monitor
   * thread.
   */
  void adaptRate(double currentInvocationsPerSecond) {
    if (currentInvocationsPerSecond > thresholdInvocationsPerSecond) {
      // Increase sampling rate (sample less frequently)
      int current = samplingRate.get();
      int newRate = Math.min(current + 1, maxRate);
      if (newRate != current) {
        samplingRate.compareAndSet(current, newRate);
        log.debug(
            "High load detected ({} inv/s), sampling rate adjusted to {}",
            (long) currentInvocationsPerSecond,
            newRate);
      }
    } else if (currentInvocationsPerSecond < thresholdInvocationsPerSecond / 2) {
      // Decrease sampling rate (sample more frequently)
      int current = samplingRate.get();
      if (current > 1) {
        int newRate = current - 1;
        samplingRate.compareAndSet(current, newRate);
        log.debug(
            "Load normalized ({} inv/s), sampling rate adjusted to {}",
            (long) currentInvocationsPerSecond,
            newRate);
      }
    }
  }

  /** Get the number of invocations processed since startup. */
  public long getInvocationCount() {
    return invocationCounter.get();
  }

  /** Reset the invocation counter. Useful for testing. */
  public void resetCounter() {
    invocationCounter.set(0);
  }

  public int getMaxRate() {
    return maxRate;
  }

  public void setMaxRate(int maxRate) {
    this.maxRate = maxRate;
  }

  public long getThresholdInvocationsPerSecond() {
    return thresholdInvocationsPerSecond;
  }

  public void setThresholdInvocationsPerSecond(long threshold) {
    this.thresholdInvocationsPerSecond = threshold;
  }
}
