package com.github.cc11001100.weavergirl.api.sampling;

/**
 * Strategy interface for custom sampling decisions.
 *
 * <p>Implementations can use any criteria to decide whether
 * to sample a particular invocation: rate, URL path, latency,
 * exception rate, or business logic.</p>
 *
 * <p>Register via {@link SamplingStrategyRegistry}.</p>
 *
 * <h3>Built-in strategies:</h3>
 * <ul>
 *   <li>{@code adaptive} — counter-based with load adaptation (default)</li>
 *   <li>{@code fixed} — fixed rate sampling</li>
 *   <li>{@code probabilistic} — random percentage-based</li>
 * </ul>
 *
 * @since 1.1.0
 */
public interface SamplingStrategy {

    /**
     * Unique name for this strategy.
     *
     * @return the strategy name (e.g. "adaptive", "url-based")
     */
    String name();

    /**
     * Decide whether the current invocation should be sampled.
     *
     * <p>Called on every intercepted method invocation. Must be fast
     * and thread-safe.</p>
     *
     * @return true if the invocation should be sampled
     */
    boolean shouldSample();

    /**
     * Called periodically by the sampling monitor to update internal state.
     *
     * @param invocationCount total invocations since last update
     * @param currentRate     current invocations per second
     */
    default void updateMetrics(long invocationCount, double currentRate) {
        // Default: no-op
    }

    /**
     * Reset internal state (for testing).
     */
    default void reset() {
        // Default: no-op
    }
}
