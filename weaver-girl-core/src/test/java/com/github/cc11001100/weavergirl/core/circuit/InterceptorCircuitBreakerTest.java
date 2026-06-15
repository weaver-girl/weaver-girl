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

    // --- recordOutcome: latency-based (slow-call) auto-degradation ---

    @Test
    void recordOutcomeTripsAfterConsecutiveSlowCallsEvenOnSuccess() {
        // threshold 1us, trip after 3 consecutive slow calls
        InterceptorCircuitBreaker cb = new InterceptorCircuitBreaker(100, 60_000, 1_000L, 3);
        // These succeed but are slow -> should still trip via slow-call logic
        cb.recordOutcome("slow-hook", true, 10_000L);
        cb.recordOutcome("slow-hook", true, 10_000L);
        assertTrue(cb.shouldInvoke("slow-hook")); // only 2 slow, below limit

        cb.recordOutcome("slow-hook", true, 10_000L); // 3rd consecutive slow
        assertFalse(cb.shouldInvoke("slow-hook")); // tripped OPEN
    }

    @Test
    void recordOutcomeFastCallResetsConsecutiveSlowCount() {
        InterceptorCircuitBreaker cb = new InterceptorCircuitBreaker(100, 60_000, 1_000L, 3);
        cb.recordOutcome("slow-hook", true, 10_000L); // slow=1
        cb.recordOutcome("slow-hook", true, 10_000L); // slow=2
        cb.recordOutcome("slow-hook", true, 100L);    // fast -> resets to 0
        cb.recordOutcome("slow-hook", true, 10_000L); // slow=1
        cb.recordOutcome("slow-hook", true, 10_000L); // slow=2
        // Never reached 3 consecutive slow, never failed -> still allowed
        assertTrue(cb.shouldInvoke("slow-hook"));
    }

    @Test
    void recordOutcomeFailureTripsViaFailureThreshold() {
        // slow tripping disabled (threshold <= 0); rely on failure threshold only
        InterceptorCircuitBreaker cb = new InterceptorCircuitBreaker(3, 60_000, 0L, 10);
        cb.recordOutcome("failing-hook", false, 100L);
        cb.recordOutcome("failing-hook", false, 100L);
        assertTrue(cb.shouldInvoke("failing-hook"));
        cb.recordOutcome("failing-hook", false, 100L); // 3rd failure
        assertFalse(cb.shouldInvoke("failing-hook"));
    }

    @Test
    void recordOutcomeSuccessResetsFailureCount() {
        InterceptorCircuitBreaker cb = new InterceptorCircuitBreaker(3, 60_000, 0L, 10);
        cb.recordOutcome("hook", false, 100L);
        cb.recordOutcome("hook", false, 100L);
        cb.recordOutcome("hook", true, 100L); // success resets failures
        cb.recordOutcome("hook", false, 100L);
        cb.recordOutcome("hook", false, 100L); // only 2 consecutive since reset
        assertTrue(cb.shouldInvoke("hook"));
    }

    @Test
    void recordOutcomeDoesNotTripWhenSlowThresholdDisabled() {
        // threshold <= 0 disables slow-call tripping entirely
        InterceptorCircuitBreaker cb = new InterceptorCircuitBreaker(100, 60_000, 0L, 2);
        for (int i = 0; i < 50; i++) {
            cb.recordOutcome("slow-hook", true, 999_999_999L); // very slow, but disabled
        }
        assertTrue(cb.shouldInvoke("slow-hook"));
    }

    // --- snapshot: live breaker-state observability ---

    @Test
    void snapshotIsEmptyUntilAnInterceptorIsObserved() {
        InterceptorCircuitBreaker cb = new InterceptorCircuitBreaker(3, 60_000);
        assertTrue(cb.snapshot().isEmpty());
    }

    @Test
    void snapshotReportsOpenStateAndTripCountAfterFailureTrip() {
        InterceptorCircuitBreaker cb = new InterceptorCircuitBreaker(2, 60_000);
        cb.recordFailure("hook");
        cb.recordFailure("hook"); // trips OPEN
        java.util.List<InterceptorCircuitBreaker.BreakerSnapshot> snap = cb.snapshot();
        assertEquals(1, snap.size());
        InterceptorCircuitBreaker.BreakerSnapshot b = snap.get(0);
        assertEquals("hook", b.getName());
        assertTrue(b.isOpen());
        assertEquals(2, b.getConsecutiveFailures());
        assertEquals(1, b.getTimesTripped());
    }

    @Test
    void snapshotReportsTripCountAfterSlowTrip() {
        InterceptorCircuitBreaker cb = new InterceptorCircuitBreaker(100, 60_000, 1_000L, 2);
        cb.recordOutcome("slow-hook", true, 10_000L);
        cb.recordOutcome("slow-hook", true, 10_000L); // 2 consecutive slow -> trips
        InterceptorCircuitBreaker.BreakerSnapshot b = cb.snapshot().get(0);
        assertTrue(b.isOpen());
        assertEquals(2, b.getConsecutiveSlow());
        assertEquals(1, b.getTimesTripped());
    }

    @Test
    void snapshotShowsClosedForHealthyHook() {
        InterceptorCircuitBreaker cb = new InterceptorCircuitBreaker(5, 60_000, 1_000L, 5);
        cb.recordOutcome("healthy-hook", true, 100L); // fast success
        InterceptorCircuitBreaker.BreakerSnapshot b = cb.snapshot().get(0);
        assertFalse(b.isOpen());
        assertEquals(0, b.getTimesTripped());
        assertEquals(0, b.getConsecutiveFailures());
    }
}
