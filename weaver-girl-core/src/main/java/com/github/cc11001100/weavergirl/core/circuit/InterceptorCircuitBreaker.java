package com.github.cc11001100.weavergirl.core.circuit;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Circuit breaker for interceptor callbacks.
 * If an interceptor fails N consecutive times, it is disabled
 * for a cooldown period. This prevents a broken interceptor
 * from degrading the entire application.
 *
 * <p>States:</p>
 * <ul>
 *   <li>CLOSED — interceptor is active, failures are counted</li>
 *   <li>OPEN — interceptor is disabled, calls are skipped</li>
 * </ul>
 *
 * <p>After cooldown expires, the circuit transitions back to CLOSED
 * and gives the interceptor another chance.</p>
 *
 * <p>Thread safety: State transitions are guarded by per-interceptor
 * synchronization to prevent race conditions between shouldInvoke()
 * and recordFailure().</p>
 */
public class InterceptorCircuitBreaker {

    private static final Logger log = LoggerFactory.getLogger(InterceptorCircuitBreaker.class);

    private final int failureThreshold;
    private final long cooldownMillis;

    /**
     * A single interceptor callback whose duration exceeds this is a "slow call".
     * When {@code slowConsecutiveLimit} slow calls happen in a row, the breaker
     * trips OPEN (auto-degradation), protecting the host from a hook that does not
     * throw but is too expensive to run on every invocation. Defaults to the same
     * threshold the per-hook metrics use ({@code -Dweavergirl.hook.slowThresholdNanos},
     * 50ms) so "slow" means the same thing for reporting and tripping. {@code <= 0}
     * disables slow-call tripping.
     */
    private final long slowThresholdNanos;
    private final int slowConsecutiveLimit;

    private final ConcurrentHashMap<String, State> states = new ConcurrentHashMap<>();

    public InterceptorCircuitBreaker() {
        this(5, 60_000); // 5 failures, 60 second cooldown
    }

    public InterceptorCircuitBreaker(int failureThreshold, long cooldownMillis) {
        this(failureThreshold, cooldownMillis,
                Long.getLong("weavergirl.hook.slowThresholdNanos", 50_000_000L), 10);
    }

    /**
     * @param failureThreshold    consecutive failures that trip the breaker
     * @param cooldownMillis      OPEN-state cooldown before a half-open retry
     * @param slowThresholdNanos  per-call duration that counts as a slow call ({@code <=0} disables)
     * @param slowConsecutiveLimit consecutive slow calls that trip the breaker
     */
    public InterceptorCircuitBreaker(int failureThreshold, long cooldownMillis,
                                     long slowThresholdNanos, int slowConsecutiveLimit) {
        this.failureThreshold = failureThreshold;
        this.cooldownMillis = cooldownMillis;
        this.slowThresholdNanos = slowThresholdNanos;
        this.slowConsecutiveLimit = slowConsecutiveLimit;
    }

    /**
     * Check if the interceptor with the given name should be invoked.
     */
    public boolean shouldInvoke(String interceptorName) {
        State state = states.get(interceptorName);
        if (state == null) {
            return true; // no state = never failed = allow
        }
        synchronized (state) {
            if (state.open) {
                // Check if cooldown has expired
                if (System.currentTimeMillis() - state.openedAt > cooldownMillis) {
                    // Half-open: give it another chance
                    state.open = false;
                    state.failures.set(0);
                    log.info("Circuit breaker for '{}' reset after cooldown", interceptorName);
                    return true;
                }
                return false; // still in cooldown
            }
            return true;
        }
    }

    /**
     * Record a successful invocation.
     */
    public void recordSuccess(String interceptorName) {
        State state = states.get(interceptorName);
        if (state != null) {
            synchronized (state) {
                state.failures.set(0);
            }
        }
    }

    /**
     * Record a failed invocation.
     */
    public void recordFailure(String interceptorName) {
        State state = states.computeIfAbsent(interceptorName, k -> new State());
        synchronized (state) {
            int failures = state.failures.incrementAndGet();
            if (failures >= failureThreshold && !state.open) {
                state.open = true;
                state.openedAt = System.currentTimeMillis();
                log.warn("Circuit breaker OPEN for interceptor '{}' after {} failures (cooldown: {}ms)",
                        interceptorName, failures, cooldownMillis);
            }
        }
    }

    /**
     * Record the outcome of an invocation in a single call, driving both the
     * failure-count and slow-call (auto-degradation) logic. This is the entry
     * point used by the inlined {@code @Advice}: it measures the hook's duration
     * and passes it here so a hook that is consistently too slow — but never
     * throws — is still tripped. A non-slow call resets the consecutive-slow
     * counter; a slow call increments it and trips OPEN at the limit. Success
     * resets the failure counter, mirroring {@link #recordSuccess(String)}.
     */
    public void recordOutcome(String interceptorName, boolean success, long durationNanos) {
        State state = states.computeIfAbsent(interceptorName, k -> new State());
        synchronized (state) {
            if (success) {
                state.failures.set(0);
            } else {
                int failures = state.failures.incrementAndGet();
                if (failures >= failureThreshold && !state.open) {
                    state.open = true;
                    state.openedAt = System.currentTimeMillis();
                    log.warn("Circuit breaker OPEN for interceptor '{}' after {} failures (cooldown: {}ms)",
                            interceptorName, failures, cooldownMillis);
                }
            }
            if (slowThresholdNanos > 0) {
                if (durationNanos >= slowThresholdNanos) {
                    int slow = state.consecutiveSlow.incrementAndGet();
                    if (slow >= slowConsecutiveLimit && !state.open) {
                        state.open = true;
                        state.openedAt = System.currentTimeMillis();
                        log.warn("Circuit breaker OPEN for interceptor '{}' after {} consecutive slow calls "
                                        + "({}ms >= {}ms threshold; cooldown: {}ms)",
                                interceptorName, slow,
                                durationNanos / 1_000_000, slowThresholdNanos / 1_000_000, cooldownMillis);
                    }
                } else {
                    state.consecutiveSlow.set(0);
                }
            }
        }
    }

    private static class State {
        final AtomicInteger failures = new AtomicInteger(0);
        final AtomicInteger consecutiveSlow = new AtomicInteger(0);
        volatile boolean open = false;
        volatile long openedAt = 0;
    }
}
