package com.github.cc11001100.weavergirl.core.circuit;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class InterceptorCircuitBreakerTest {

    @Test
    void shouldInvokeReturnsTrueForNewInterceptor() {
        InterceptorCircuitBreaker cb = new InterceptorCircuitBreaker();
        assertTrue(cb.shouldInvoke("unknown-interceptor"));
    }

    @Test
    void shouldInvokeReturnsTrueWhenFailuresBelowThreshold() {
        InterceptorCircuitBreaker cb = new InterceptorCircuitBreaker(5, 60_000);
        // Record 4 failures (below threshold of 5)
        for (int i = 0; i < 4; i++) {
            cb.recordFailure("my-interceptor");
        }
        assertTrue(cb.shouldInvoke("my-interceptor"));
    }

    @Test
    void shouldInvokeReturnsFalseAfterConsecutiveFailuresReachThreshold() {
        InterceptorCircuitBreaker cb = new InterceptorCircuitBreaker(3, 60_000);
        for (int i = 0; i < 3; i++) {
            cb.recordFailure("failing-interceptor");
        }
        assertFalse(cb.shouldInvoke("failing-interceptor"));
    }

    @Test
    void shouldInvokeReturnsTrueAfterCooldownExpires() throws InterruptedException {
        InterceptorCircuitBreaker cb = new InterceptorCircuitBreaker(3, 10); // 10ms cooldown
        for (int i = 0; i < 3; i++) {
            cb.recordFailure("cooldown-interceptor");
        }
        assertFalse(cb.shouldInvoke("cooldown-interceptor"));

        // Wait for cooldown to expire
        Thread.sleep(100);

        assertTrue(cb.shouldInvoke("cooldown-interceptor"));
    }

    @Test
    void recordSuccessResetsFailureCount() {
        InterceptorCircuitBreaker cb = new InterceptorCircuitBreaker(3, 60_000);
        // Record 2 failures
        cb.recordFailure("reset-interceptor");
        cb.recordFailure("reset-interceptor");

        // Record a success — resets the failure count
        cb.recordSuccess("reset-interceptor");

        // Now record 2 more failures — without the reset, total would be 4 (>=3)
        cb.recordFailure("reset-interceptor");
        cb.recordFailure("reset-interceptor");

        // Should still be allowed because the success reset the count to 0,
        // so we only have 2 consecutive failures now
        assertTrue(cb.shouldInvoke("reset-interceptor"));
    }

    @Test
    void multipleInterceptorsTrackedIndependently() {
        InterceptorCircuitBreaker cb = new InterceptorCircuitBreaker(2, 60_000);

        // Fail interceptor-A twice (reaches threshold)
        cb.recordFailure("interceptor-A");
        cb.recordFailure("interceptor-A");

        // Fail interceptor-B once (below threshold)
        cb.recordFailure("interceptor-B");

        assertFalse(cb.shouldInvoke("interceptor-A"));
        assertTrue(cb.shouldInvoke("interceptor-B"));
    }
}
