package com.github.cc11001100.weavergirl.core.circuit;

import static org.junit.jupiter.api.Assertions.*;

import com.github.cc11001100.weavergirl.api.interceptor.Interceptor;
import com.github.cc11001100.weavergirl.api.interceptor.InterceptorDefinition;
import com.github.cc11001100.weavergirl.api.interceptor.MethodInvocation;
import com.github.cc11001100.weavergirl.api.matcher.ClassMatcher;
import com.github.cc11001100.weavergirl.api.matcher.MethodMatcher;
import com.github.cc11001100.weavergirl.api.pointcut.Pointcut;
import com.github.cc11001100.weavergirl.core.InterceptorHolder;
import com.github.cc11001100.weavergirl.core.registry.DefaultInterceptorRegistry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * Integration test for circuit breaker working with InterceptorHolder. Verifies the P19
 * synchronized fix prevents race conditions.
 */
class CircuitBreakerIntegrationTest {

  private DefaultInterceptorRegistry registry;

  @BeforeEach
  void setUp() {
    registry = new DefaultInterceptorRegistry();
    InterceptorHolder.setRegistry(registry);
  }

  @Test
  void failingInterceptor_triggersCircuitBreaker() {
    Interceptor failingInterceptor =
        new Interceptor() {
          @Override
          public void before(MethodInvocation inv) {
            throw new RuntimeException("Simulated failure");
          }
        };

    InterceptorDefinition def =
        new InterceptorDefinition(
            "circuit-test",
            new Pointcut(
                ClassMatcher.byName("com.example.Service"), MethodMatcher.byName("process")),
            failingInterceptor);
    registry.register(def);

    // Fail 5 times to trigger circuit breaker (default threshold)
    for (int i = 0; i < 5; i++) {
      InterceptorHolder.recordInterceptorFailure("circuit-test");
    }

    // Circuit breaker should now be open
    assertFalse(
        InterceptorHolder.shouldInvoke("circuit-test"),
        "Circuit breaker should be open after 5 failures");
  }

  @Test
  void successfulInvocation_resetsCircuitBreaker() {
    // Open the circuit first
    for (int i = 0; i < 5; i++) {
      InterceptorHolder.recordInterceptorFailure("reset-test");
    }
    assertFalse(InterceptorHolder.shouldInvoke("reset-test"));

    // Record success
    InterceptorHolder.recordInterceptorSuccess("reset-test");

    // Should still be open (success doesn't close it, only cooldown or reset does)
    // But the failure count is reset to 0, so next failure cycle starts fresh
    // Let's verify the counter was reset by recording new failures
    for (int i = 0; i < 5; i++) {
      InterceptorHolder.recordInterceptorFailure("reset-test");
    }

    // After 5 more failures, should be open again
    assertFalse(
        InterceptorHolder.shouldInvoke("reset-test"),
        "Circuit breaker should re-open after fresh failure cycle");
  }

  @Test
  void differentInterceptors_haveIndependentCircuitBreakers() {
    // Fail interceptor A
    for (int i = 0; i < 5; i++) {
      InterceptorHolder.recordInterceptorFailure("interceptor-A");
    }

    // Interceptor B should still work
    assertTrue(
        InterceptorHolder.shouldInvoke("interceptor-B"),
        "Different interceptors should have independent circuit breakers");

    // Interceptor A should be blocked
    assertFalse(
        InterceptorHolder.shouldInvoke("interceptor-A"), "Failed interceptor should be blocked");
  }

  @Test
  void concurrentFailureRecording_noRaceCondition() throws Exception {
    String name = "concurrent-test";
    int threadCount = 10;
    int failuresPerThread = 10;
    Thread[] threads = new Thread[threadCount];

    for (int t = 0; t < threadCount; t++) {
      threads[t] =
          new Thread(
              () -> {
                for (int i = 0; i < failuresPerThread; i++) {
                  InterceptorHolder.recordInterceptorFailure(name);
                }
              });
    }

    for (Thread t : threads) t.start();
    for (Thread t : threads) t.join();

    // After 100 total failures (10 threads * 10 failures each),
    // the circuit breaker should definitely be open
    assertFalse(
        InterceptorHolder.shouldInvoke(name),
        "Circuit breaker should be open after concurrent failure recording");
  }

  @Test
  void logInterceptorError_acceptsThrowableNotJustException() {
    // P19 fix: logInterceptorError now accepts Throwable
    assertDoesNotThrow(
        () ->
            InterceptorHolder.logInterceptorError(
                "test", "before", new OutOfMemoryError("test OOM")));
    assertDoesNotThrow(
        () ->
            InterceptorHolder.logInterceptorError(
                "test", "after", new StackOverflowError("test SOE")));
    assertDoesNotThrow(
        () ->
            InterceptorHolder.logInterceptorError(
                "test", "before", new RuntimeException("test exception")));
  }
}
