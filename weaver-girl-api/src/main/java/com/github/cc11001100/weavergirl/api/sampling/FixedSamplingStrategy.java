package com.github.cc11001100.weavergirl.api.sampling;

import java.util.concurrent.atomic.AtomicLong;

/**
 * Fixed-rate sampling: sample every Nth invocation.
 *
 * @since 1.1.0
 */
public class FixedSamplingStrategy implements SamplingStrategy {

  private final AtomicLong counter = new AtomicLong(0);
  private volatile int rate;

  /**
   * Create a fixed-rate strategy.
   *
   * @param rate sample every Nth invocation (1 = all, 10 = 1 in 10)
   */
  public FixedSamplingStrategy(int rate) {
    this.rate = Math.max(1, rate);
  }

  @Override
  public String name() {
    return "fixed";
  }

  @Override
  public boolean shouldSample() {
    return counter.incrementAndGet() % rate == 0;
  }

  @Override
  public void reset() {
    counter.set(0);
  }

  /**
   * Update the sampling rate.
   *
   * @param rate the new rate
   */
  public void setRate(int rate) {
    this.rate = Math.max(1, rate);
  }

  public int getRate() {
    return rate;
  }
}
