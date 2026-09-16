package com.github.cc11001100.weavergirl.core.plugin;

import static org.junit.jupiter.api.Assertions.*;

import com.github.cc11001100.weavergirl.annotation.Bulkhead;
import com.github.cc11001100.weavergirl.annotation.CircuitBreaker;
import com.github.cc11001100.weavergirl.annotation.Fallback;
import com.github.cc11001100.weavergirl.annotation.RateLimiter;
import com.github.cc11001100.weavergirl.annotation.RetryOnException;
import com.github.cc11001100.weavergirl.annotation.Timeout;
import com.github.cc11001100.weavergirl.annotation.WeaveClass;
import com.github.cc11001100.weavergirl.api.interceptor.Interceptor;
import com.github.cc11001100.weavergirl.api.interceptor.MethodInvocation;
import com.github.cc11001100.weavergirl.api.registry.InterceptorRegistry;
import com.github.cc11001100.weavergirl.core.registry.DefaultInterceptorRegistry;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * Exercises every branch of the resilience annotation wrappers in {@link AnnotationPluginLoader}:
 * {@code wrapWithCircuitBreaker}, {@code wrapWithTimeout}, {@code wrapWithFallback}, {@code
 * wrapWithBulkhead}, {@code wrapWithRateLimiter}, and {@code wrapWithRetry}.
 *
 * <p>The wrappers key their static state maps ({@code circuitStates}, {@code bulkheadCounters},
 * {@code rateLimitStates}) by {@code targetClass#methodName}. Every scenario below therefore uses a
 * distinct {@code methodName} (via {@link #newInvocation}) even though a single {@code targetClass}
 * is shared, so scenarios never pollute each other's state.
 */
class ResilienceWrapperCoverageTest {

  @WeaveClass(target = "com.example.resilience.ResilienceService")
  public static class ResilienceInterceptors {
    static final List<String> guardCalls = Collections.synchronizedList(new ArrayList<>());

    // ---- CircuitBreaker ----

    @CircuitBreaker(
        value = "cbOpenRecover",
        failureThreshold = 2,
        openTimeoutMs = 30,
        successThreshold = 2)
    public void guardOpenRecover(MethodInvocation inv) {
      guardCalls.add("cbOpenRecover:" + inv.getMethodName());
    }

    @CircuitBreaker(value = "cbEmptyFailureTypes", failureThreshold = 1)
    public void guardEmptyFailureTypes(MethodInvocation inv) {
      guardCalls.add("cbEmptyFailureTypes:" + inv.getMethodName());
    }

    @CircuitBreaker(
        value = "cbFailureTypesMatch",
        failureThreshold = 1,
        failureTypes = {IllegalStateException.class})
    public void guardFailureTypesMatch(MethodInvocation inv) {
      guardCalls.add("cbFailureTypesMatch:" + inv.getMethodName());
    }

    @CircuitBreaker(
        value = "cbFailureTypesNoMatch",
        failureThreshold = 1,
        failureTypes = {IllegalStateException.class})
    public void guardFailureTypesNoMatch(MethodInvocation inv) {
      guardCalls.add("cbFailureTypesNoMatch:" + inv.getMethodName());
    }

    // ---- Timeout ----

    @Timeout(value = "toNotExceeded", durationMs = 100_000)
    public void guardTimeoutNotExceeded(MethodInvocation inv) {
      guardCalls.add("toNotExceeded:" + inv.getMethodName());
    }

    @Timeout(value = "toExceeded", durationMs = 1)
    public void guardTimeoutExceeded(MethodInvocation inv) {
      guardCalls.add("toExceeded:" + inv.getMethodName());
    }

    // ---- Fallback ----

    @Fallback(value = "fbEmptyOnExceptions", method = "fallbackHandler")
    public void guardFallbackEmpty(MethodInvocation inv) {
      guardCalls.add("fbEmptyOnExceptions:" + inv.getMethodName());
    }

    @Fallback(
        value = "fbOnExceptionsMatch",
        method = "fallbackIo",
        onExceptions = {IOException.class})
    public void guardFallbackMatch(MethodInvocation inv) {
      guardCalls.add("fbOnExceptionsMatch:" + inv.getMethodName());
    }

    @Fallback(
        value = "fbOnExceptionsNoMatch",
        method = "fallbackIo2",
        onExceptions = {IOException.class})
    public void guardFallbackNoMatch(MethodInvocation inv) {
      guardCalls.add("fbOnExceptionsNoMatch:" + inv.getMethodName());
    }

    // ---- Bulkhead ----

    @Bulkhead(value = "bhMaxTwo", maxConcurrent = 2)
    public void guardBulkheadMaxTwo(MethodInvocation inv) {
      guardCalls.add("bhMaxTwo:" + inv.getMethodName());
    }

    @Bulkhead(value = "bhOnExceptionDecrement", maxConcurrent = 1)
    public void guardBulkheadOnExceptionDecrement(MethodInvocation inv) {
      guardCalls.add("bhOnExceptionDecrement:" + inv.getMethodName());
    }

    // ---- RateLimiter ----

    @RateLimiter(value = "rlOnePerSecond", permitsPerSecond = 1)
    public void guardRateLimiter(MethodInvocation inv) {
      guardCalls.add("rlOnePerSecond:" + inv.getMethodName());
    }

    // ---- RetryOnException ----

    @RetryOnException(value = "rtEmptyRetryFor", maxRetries = 2, delayMs = 2)
    public void guardRetryEmpty(MethodInvocation inv) {
      guardCalls.add("rtEmptyRetryFor:" + inv.getMethodName());
    }

    @RetryOnException(value = "rtDelayZero", maxRetries = 1, delayMs = 0)
    public void guardRetryDelayZero(MethodInvocation inv) {
      guardCalls.add("rtDelayZero:" + inv.getMethodName());
    }

    @RetryOnException(
        value = "rtRetryForMatch",
        maxRetries = 1,
        delayMs = 0,
        retryFor = {IllegalStateException.class})
    public void guardRetryForMatch(MethodInvocation inv) {
      guardCalls.add("rtRetryForMatch:" + inv.getMethodName());
    }

    @RetryOnException(
        value = "rtRetryForNoMatch",
        maxRetries = 1,
        delayMs = 0,
        retryFor = {IllegalStateException.class})
    public void guardRetryForNoMatch(MethodInvocation inv) {
      guardCalls.add("rtRetryForNoMatch:" + inv.getMethodName());
    }
  }

  private AnnotationPluginLoader loader;
  private InterceptorRegistry registry;

  @BeforeEach
  void setUp() {
    loader = new AnnotationPluginLoader();
    registry = new DefaultInterceptorRegistry();
    ResilienceInterceptors.guardCalls.clear();
    Set<Class<?>> classes = new HashSet<>();
    classes.add(ResilienceInterceptors.class);
    loader.loadAnnotatedInterceptors(classes, registry);
  }

  private Interceptor interceptorFor(String targetMethod) {
    return registry.getAllDefinitions().stream()
        .filter(d -> d.getName().endsWith("-" + targetMethod))
        .findFirst()
        .orElseThrow(() -> new AssertionError("no definition for " + targetMethod))
        .getInterceptor();
  }

  /**
   * Builds an invocation whose method name matches the scenario's own key so that each scenario's
   * static circuit/bulkhead/rate-limiter state is isolated from every other scenario.
   */
  private MethodInvocation newInvocation(String methodName, Object... args) {
    return new MethodInvocation(ResilienceInterceptors.class, methodName, "target", args);
  }

  // ==================== wrapWithCircuitBreaker ====================

  @Test
  void circuitBreaker_opensAfterFailureThreshold_thenSkipsAndHalfOpensAfterTimeout()
      throws InterruptedException {
    Interceptor interceptor = interceptorFor("cbOpenRecover");

    // First call: circuit is new/closed -> not skipped.
    MethodInvocation inv1 = newInvocation("cbOpenRecover");
    interceptor.before(inv1);
    assertFalse(inv1.isSkipped());
    assertNull(inv1.getAttachment("circuitBreaker.open"));

    // First failure: below failureThreshold(2), circuit stays closed.
    MethodInvocation failInv1 = newInvocation("cbOpenRecover");
    failInv1.setThrowable(new RuntimeException("boom1"));
    interceptor.onException(failInv1);

    MethodInvocation inv2 = newInvocation("cbOpenRecover");
    interceptor.before(inv2);
    assertFalse(inv2.isSkipped(), "circuit should still be closed after only 1 failure");

    // Second failure: reaches failureThreshold(2) -> circuit opens.
    MethodInvocation failInv2 = newInvocation("cbOpenRecover");
    failInv2.setThrowable(new RuntimeException("boom2"));
    interceptor.onException(failInv2);

    // Circuit now open and timeout not elapsed -> before() skips.
    MethodInvocation inv3 = newInvocation("cbOpenRecover");
    interceptor.before(inv3);
    assertTrue(inv3.isSkipped());
    assertNull(inv3.getReturnValue());
    assertEquals(true, inv3.getAttachment("circuitBreaker.open"));
    assertTrue(ResilienceInterceptors.guardCalls.contains("cbOpenRecover:cbOpenRecover"));

    // Wait past openTimeoutMs(30) -> circuit half-opens -> before() no longer skips.
    Thread.sleep(60);
    MethodInvocation inv4 = newInvocation("cbOpenRecover");
    interceptor.before(inv4);
    assertFalse(inv4.isSkipped(), "circuit should half-open once the timeout elapses");
    assertNull(inv4.getAttachment("circuitBreaker.open"));

    // Two successes reach successThreshold(2) and fully close/reset the circuit.
    MethodInvocation okInv1 = newInvocation("cbOpenRecover");
    interceptor.after(okInv1);
    MethodInvocation okInv2 = newInvocation("cbOpenRecover");
    interceptor.after(okInv2);

    MethodInvocation inv5 = newInvocation("cbOpenRecover");
    interceptor.before(inv5);
    assertFalse(inv5.isSkipped(), "circuit should remain closed after recording successes");
  }

  @Test
  void circuitBreaker_shouldCount_emptyFailureTypesAlwaysCounts() {
    Interceptor interceptor = interceptorFor("cbEmptyFailureTypes");

    MethodInvocation failInv = newInvocation("cbEmptyFailureTypes");
    failInv.setThrowable(new IOException("io failure"));
    interceptor.onException(failInv);

    // failureThreshold=1 with empty failureTypes -> any exception type opens the circuit.
    MethodInvocation inv = newInvocation("cbEmptyFailureTypes");
    interceptor.before(inv);
    assertTrue(inv.isSkipped(), "empty failureTypes should count every exception");
  }

  @Test
  void circuitBreaker_shouldCount_matchingFailureTypeCounts() {
    Interceptor interceptor = interceptorFor("cbFailureTypesMatch");

    MethodInvocation failInv = newInvocation("cbFailureTypesMatch");
    failInv.setThrowable(new IllegalStateException("matches"));
    interceptor.onException(failInv);

    MethodInvocation inv = newInvocation("cbFailureTypesMatch");
    interceptor.before(inv);
    assertTrue(inv.isSkipped(), "matching failureType should count toward the threshold");
  }

  @Test
  void circuitBreaker_shouldCount_nonMatchingFailureTypeDoesNotCount() {
    Interceptor interceptor = interceptorFor("cbFailureTypesNoMatch");

    MethodInvocation failInv = newInvocation("cbFailureTypesNoMatch");
    failInv.setThrowable(new IllegalArgumentException("does not match"));
    interceptor.onException(failInv);

    MethodInvocation inv = newInvocation("cbFailureTypesNoMatch");
    interceptor.before(inv);
    assertFalse(inv.isSkipped(), "non-matching failureType should not count toward the threshold");
  }

  // ==================== wrapWithTimeout ====================

  @Test
  void timeout_afterDoesNotFlagExceeded_whenWithinDuration() {
    Interceptor interceptor = interceptorFor("toNotExceeded");

    MethodInvocation inv = newInvocation("toNotExceeded");
    interceptor.before(inv);
    assertNotNull(inv.getAttachment("timeout.deadlineNanos"));

    interceptor.after(inv);
    assertNull(inv.getAttachment("timeout.exceeded"));
  }

  @Test
  void timeout_afterFlagsExceeded_whenDeadlinePassed() throws InterruptedException {
    Interceptor interceptor = interceptorFor("toExceeded");

    MethodInvocation inv = newInvocation("toExceeded");
    interceptor.before(inv);
    Thread.sleep(15);

    interceptor.after(inv);
    assertEquals(true, inv.getAttachment("timeout.exceeded"));
    assertTrue(ResilienceInterceptors.guardCalls.contains("toExceeded:toExceeded"));
  }

  // ==================== wrapWithFallback ====================

  @Test
  void fallback_shouldFallback_emptyOnExceptionsAlwaysTriggers() {
    Interceptor interceptor = interceptorFor("fbEmptyOnExceptions");

    MethodInvocation inv = newInvocation("fbEmptyOnExceptions");
    inv.setThrowable(new RuntimeException("anything"));
    interceptor.onException(inv);

    assertEquals("fallbackHandler", inv.getAttachment("fallback.method"));
    assertTrue(inv.isExceptionSuppressed());
    assertTrue(ResilienceInterceptors.guardCalls.contains("fbEmptyOnExceptions:fbEmptyOnExceptions"));
  }

  @Test
  void fallback_shouldFallback_matchingOnExceptionTriggersAndSuppresses() {
    Interceptor interceptor = interceptorFor("fbOnExceptionsMatch");

    MethodInvocation inv = newInvocation("fbOnExceptionsMatch");
    inv.setThrowable(new IOException("matches"));
    interceptor.onException(inv);

    assertEquals("fallbackIo", inv.getAttachment("fallback.method"));
    assertTrue(inv.isExceptionSuppressed());
  }

  @Test
  void fallback_shouldFallback_nonMatchingOnExceptionDoesNotSuppress() {
    Interceptor interceptor = interceptorFor("fbOnExceptionsNoMatch");

    MethodInvocation inv = newInvocation("fbOnExceptionsNoMatch");
    inv.setThrowable(new RuntimeException("does not match IOException"));
    interceptor.onException(inv);

    assertNull(inv.getAttachment("fallback.method"));
    assertFalse(inv.isExceptionSuppressed());
    assertFalse(
        ResilienceInterceptors.guardCalls.contains("fbOnExceptionsNoMatch:fbOnExceptionsNoMatch"));
  }

  // ==================== wrapWithBulkhead ====================

  @Test
  void bulkhead_rejectsWhenOverMaxConcurrent_andAcceptsOtherwise() {
    Interceptor interceptor = interceptorFor("bhMaxTwo");

    MethodInvocation inv1 = newInvocation("bhMaxTwo");
    interceptor.before(inv1);
    assertFalse(inv1.isSkipped(), "1st concurrent call should be accepted (maxConcurrent=2)");

    MethodInvocation inv2 = newInvocation("bhMaxTwo");
    interceptor.before(inv2);
    assertFalse(inv2.isSkipped(), "2nd concurrent call should be accepted (maxConcurrent=2)");

    // 3rd concurrent call (neither inv1 nor inv2 has been released yet) must be rejected.
    MethodInvocation inv3 = newInvocation("bhMaxTwo");
    interceptor.before(inv3);
    assertTrue(inv3.isSkipped());
    assertNull(inv3.getReturnValue());
    assertEquals(true, inv3.getAttachment("bulkhead.rejected"));
    assertTrue(ResilienceInterceptors.guardCalls.contains("bhMaxTwo:bhMaxTwo"));

    // Releasing inv1 via after() frees a slot for a new call.
    interceptor.after(inv1);
    MethodInvocation inv4 = newInvocation("bhMaxTwo");
    interceptor.before(inv4);
    assertFalse(inv4.isSkipped(), "slot freed by after() should allow a new call through");
  }

  @Test
  void bulkhead_onExceptionDecrementsCounter_freeingASlot() {
    Interceptor interceptor = interceptorFor("bhOnExceptionDecrement");

    MethodInvocation inv1 = newInvocation("bhOnExceptionDecrement");
    interceptor.before(inv1);
    assertFalse(inv1.isSkipped());

    MethodInvocation inv2 = newInvocation("bhOnExceptionDecrement");
    interceptor.before(inv2);
    assertTrue(inv2.isSkipped(), "maxConcurrent=1, inv1's slot has not been released yet");

    inv1.setThrowable(new RuntimeException("boom"));
    interceptor.onException(inv1);

    MethodInvocation inv3 = newInvocation("bhOnExceptionDecrement");
    interceptor.before(inv3);
    assertFalse(
        inv3.isSkipped(), "slot freed by onException() decrement should allow a new call through");
  }

  // ==================== wrapWithRateLimiter ====================

  @Test
  void rateLimiter_acceptsFirstCall_thenRejectsRapidFollowUpCalls() {
    Interceptor interceptor = interceptorFor("rlOnePerSecond");

    MethodInvocation inv1 = newInvocation("rlOnePerSecond");
    interceptor.before(inv1);
    assertFalse(inv1.isSkipped(), "first call should consume the initial full token bucket");
    assertNull(inv1.getAttachment("rateLimiter.rejected"));

    MethodInvocation inv2 = newInvocation("rlOnePerSecond");
    interceptor.before(inv2);
    assertTrue(inv2.isSkipped(), "immediate 2nd call should exhaust the 1-permit/sec bucket");
    assertNull(inv2.getReturnValue());
    assertEquals(true, inv2.getAttachment("rateLimiter.rejected"));
    assertTrue(ResilienceInterceptors.guardCalls.contains("rlOnePerSecond:rlOnePerSecond"));

    MethodInvocation inv3 = newInvocation("rlOnePerSecond");
    interceptor.before(inv3);
    assertTrue(inv3.isSkipped(), "3rd rapid call should still be rejected");
  }

  // ==================== wrapWithRetry ====================

  @Test
  void retry_withinMaxRetries_suppressesException_thenExhaustedPropagates() {
    Interceptor interceptor = interceptorFor("rtEmptyRetryFor");

    // Attempt 1/2: retried and suppressed. delayMs=2 exercises the delayMs>0 sleep branch.
    MethodInvocation inv1 = newInvocation("rtEmptyRetryFor");
    inv1.setThrowable(new RuntimeException("fail1"));
    interceptor.onException(inv1);
    assertTrue(inv1.isExceptionSuppressed());
    assertEquals(1L, inv1.getAttachment("retry.attempt"));
    assertTrue(ResilienceInterceptors.guardCalls.contains("rtEmptyRetryFor:rtEmptyRetryFor"));

    // Attempt 2/2: still within maxRetries(2) -> retried and suppressed again.
    MethodInvocation inv2 = newInvocation("rtEmptyRetryFor");
    inv2.setThrowable(new RuntimeException("fail2"));
    interceptor.onException(inv2);
    assertTrue(inv2.isExceptionSuppressed());
    assertEquals(2L, inv2.getAttachment("retry.attempt"));

    // Attempt 3: exceeds maxRetries(2) -> retries exhausted, exception propagates (not suppressed).
    MethodInvocation inv3 = newInvocation("rtEmptyRetryFor");
    inv3.setThrowable(new RuntimeException("fail3"));
    interceptor.onException(inv3);
    assertFalse(inv3.isExceptionSuppressed());
    assertNull(inv3.getAttachment("retry.attempt"));
  }

  @Test
  void retry_delayZero_skipsSleepButStillRetries() {
    Interceptor interceptor = interceptorFor("rtDelayZero");

    MethodInvocation inv = newInvocation("rtDelayZero");
    inv.setThrowable(new RuntimeException("fail"));
    interceptor.onException(inv);

    assertTrue(inv.isExceptionSuppressed());
    assertEquals(1L, inv.getAttachment("retry.attempt"));
  }

  @Test
  void retry_shouldRetryFor_matchingTypeRetries() {
    Interceptor interceptor = interceptorFor("rtRetryForMatch");

    MethodInvocation inv = newInvocation("rtRetryForMatch");
    inv.setThrowable(new IllegalStateException("matches"));
    interceptor.onException(inv);

    assertTrue(inv.isExceptionSuppressed());
    assertEquals(1L, inv.getAttachment("retry.attempt"));
  }

  @Test
  void retry_shouldRetryFor_nonMatchingTypeDoesNotRetry() {
    Interceptor interceptor = interceptorFor("rtRetryForNoMatch");

    MethodInvocation inv = newInvocation("rtRetryForNoMatch");
    inv.setThrowable(new RuntimeException("does not match IllegalStateException"));
    interceptor.onException(inv);

    assertFalse(inv.isExceptionSuppressed());
    assertNull(inv.getAttachment("retry.attempt"));
  }
}
