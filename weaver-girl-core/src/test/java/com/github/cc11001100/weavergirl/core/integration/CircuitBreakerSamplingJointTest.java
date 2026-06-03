package com.github.cc11001100.weavergirl.core.integration;

import com.github.cc11001100.weavergirl.core.InterceptorHolder;
import com.github.cc11001100.weavergirl.core.sampling.SamplingController;
import org.junit.jupiter.api.*;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Joint integration tests for Circuit Breaker + Sampling Controller.
 * Verifies coordinated behavior under various load conditions.
 *
 * <p>Note: Circuit breaker default threshold is 5 consecutive failures.</p>
 */
class CircuitBreakerSamplingJointTest {

    private static final int CIRCUIT_BREAKER_THRESHOLD = 5;

    private SamplingController samplingController;
    private int originalSamplingRate;

    @BeforeEach
    void setUp() {
        samplingController = SamplingController.getInstance();
        originalSamplingRate = samplingController.getSamplingRate();
        samplingController.resetCounter();
    }

    @AfterEach
    void tearDown() {
        samplingController.setSamplingRate(originalSamplingRate);
        samplingController.resetCounter();
    }

    @Test
    @DisplayName("Circuit breaker opens after threshold failures")
    void circuitBreakerOpensAfterThreshold() {
        String name = "joint-test-open";
        // Default threshold is 5
        for (int i = 0; i < CIRCUIT_BREAKER_THRESHOLD; i++) {
            InterceptorHolder.recordInterceptorFailure(name);
        }
        assertFalse(InterceptorHolder.shouldInvoke(name));
    }

    @Test
    @DisplayName("Circuit breaker stays closed below threshold")
    void circuitBreakerStaysClosedBelowThreshold() {
        String name = "joint-test-closed";
        // Record fewer failures than threshold
        for (int i = 0; i < CIRCUIT_BREAKER_THRESHOLD - 1; i++) {
            InterceptorHolder.recordInterceptorFailure(name);
        }
        assertTrue(InterceptorHolder.shouldInvoke(name));
    }

    @Test
    @DisplayName("Circuit breaker should work even when sampling is active")
    void circuitBreakerWorksWithSampling() {
        samplingController.setSamplingRate(1);
        String interceptorName = "joint-test-cb";
        for (int i = 0; i < CIRCUIT_BREAKER_THRESHOLD; i++) {
            InterceptorHolder.recordInterceptorFailure(interceptorName);
        }
        assertFalse(InterceptorHolder.shouldInvoke(interceptorName));
    }

    @Test
    @DisplayName("Successful invocations reset failure count")
    void successResetsFailures() {
        String interceptorName = "joint-test-recover";
        // Add some failures (below threshold)
        for (int i = 0; i < CIRCUIT_BREAKER_THRESHOLD - 1; i++) {
            InterceptorHolder.recordInterceptorFailure(interceptorName);
        }
        // Record success resets failure count
        InterceptorHolder.recordInterceptorSuccess(interceptorName);
        // Now need full threshold again to open
        for (int i = 0; i < CIRCUIT_BREAKER_THRESHOLD - 1; i++) {
            InterceptorHolder.recordInterceptorFailure(interceptorName);
        }
        // Should still be closed
        assertTrue(InterceptorHolder.shouldInvoke(interceptorName));
    }

    @Test
    @DisplayName("Sampling rate adaptation under load")
    void samplingRateAdaptation() {
        samplingController.setSamplingRate(1);
        int sampled = 0;
        int total = 1000;
        for (int i = 0; i < total; i++) {
            if (samplingController.shouldSample()) {
                sampled++;
            }
        }
        assertTrue(sampled > total * 0.95,
                "Expected >95% sampling with rate=1, got " + (sampled * 100.0 / total) + "%");

        samplingController.setSamplingRate(10);
        sampled = 0;
        for (int i = 0; i < total; i++) {
            if (samplingController.shouldSample()) {
                sampled++;
            }
        }
        assertTrue(sampled < total * 0.25,
                "Expected <25% sampling with rate=10, got " + (sampled * 100.0 / total) + "%");
    }

    @Test
    @DisplayName("Independent circuit breakers per interceptor")
    void independentCircuitBreakers() {
        String interceptorA = "joint-test-A";
        String interceptorB = "joint-test-B";
        for (int i = 0; i < CIRCUIT_BREAKER_THRESHOLD; i++) {
            InterceptorHolder.recordInterceptorFailure(interceptorA);
        }
        assertFalse(InterceptorHolder.shouldInvoke(interceptorA));
        assertTrue(InterceptorHolder.shouldInvoke(interceptorB));
    }
}
