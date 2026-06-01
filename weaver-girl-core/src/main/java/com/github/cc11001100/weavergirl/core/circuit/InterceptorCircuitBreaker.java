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
 */
public class InterceptorCircuitBreaker {

    private static final Logger log = LoggerFactory.getLogger(InterceptorCircuitBreaker.class);

    private final int failureThreshold;
    private final long cooldownMillis;

    private final ConcurrentHashMap<String, State> states = new ConcurrentHashMap<>();

    public InterceptorCircuitBreaker() {
        this(5, 60_000); // 5 failures, 60 second cooldown
    }

    public InterceptorCircuitBreaker(int failureThreshold, long cooldownMillis) {
        this.failureThreshold = failureThreshold;
        this.cooldownMillis = cooldownMillis;
    }

    /**
     * Check if the interceptor with the given name should be invoked.
     */
    public boolean shouldInvoke(String interceptorName) {
        State state = states.get(interceptorName);
        if (state == null) {
            return true; // no state = never failed = allow
        }
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

    /**
     * Record a successful invocation.
     */
    public void recordSuccess(String interceptorName) {
        State state = states.get(interceptorName);
        if (state != null) {
            state.failures.set(0);
        }
    }

    /**
     * Record a failed invocation.
     */
    public void recordFailure(String interceptorName) {
        State state = states.computeIfAbsent(interceptorName, k -> new State());
        int failures = state.failures.incrementAndGet();
        if (failures >= failureThreshold && !state.open) {
            state.open = true;
            state.openedAt = System.currentTimeMillis();
            log.warn("Circuit breaker OPEN for interceptor '{}' after {} failures (cooldown: {}ms)",
                    interceptorName, failures, cooldownMillis);
        }
    }

    private static class State {
        final AtomicInteger failures = new AtomicInteger(0);
        volatile boolean open = false;
        volatile long openedAt = 0;
    }
}
