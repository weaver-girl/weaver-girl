package com.github.cc11001100.weavergirl.api.sampling;

import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Probabilistic sampling: sample with a given probability.
 *
 * @since 1.1.0
 */
public class ProbabilisticSamplingStrategy implements SamplingStrategy {

    private final AtomicLong totalInvocations = new AtomicLong(0);
    private final AtomicLong sampledInvocations = new AtomicLong(0);
    private volatile double probability;

    /**
     * Create a probabilistic strategy.
     *
     * @param probability sampling probability (0.0 to 1.0)
     */
    public ProbabilisticSamplingStrategy(double probability) {
        this.probability = Math.max(0.0, Math.min(1.0, probability));
    }

    @Override
    public String name() {
        return "probabilistic";
    }

    @Override
    public boolean shouldSample() {
        totalInvocations.incrementAndGet();
        boolean sample = ThreadLocalRandom.current().nextDouble() < probability;
        if (sample) {
            sampledInvocations.incrementAndGet();
        }
        return sample;
    }

    @Override
    public void reset() {
        totalInvocations.set(0);
        sampledInvocations.set(0);
    }

    /**
     * Update the sampling probability.
     *
     * @param probability the new probability (0.0 to 1.0)
     */
    public void setProbability(double probability) {
        this.probability = Math.max(0.0, Math.min(1.0, probability));
    }

    public double getProbability() {
        return probability;
    }

    /**
     * Get the actual sampling rate (sampled / total).
     */
    public double getActualRate() {
        long total = totalInvocations.get();
        return total > 0 ? (double) sampledInvocations.get() / total : 0.0;
    }
}
