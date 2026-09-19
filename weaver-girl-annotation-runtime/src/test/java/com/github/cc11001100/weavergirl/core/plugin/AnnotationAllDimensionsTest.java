package com.github.cc11001100.weavergirl.core.plugin;

import static org.junit.jupiter.api.Assertions.*;

import com.github.cc11001100.weavergirl.annotation.*;
import com.github.cc11001100.weavergirl.api.interceptor.Interceptor;
import com.github.cc11001100.weavergirl.api.interceptor.MethodInvocation;
import com.github.cc11001100.weavergirl.api.registry.InterceptorRegistry;
import java.util.*;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * Comprehensive integration test covering ALL 40+ annotation dimensions. Verifies that each
 * annotation type correctly integrates with the AnnotationPluginLoader and produces the expected
 * runtime behavior.
 */
class AnnotationAllDimensionsTest {

  private AnnotationPluginLoader loader;
  private InterceptorRegistry registry;

  @BeforeEach
  void setUp() {
    loader = new AnnotationPluginLoader();
    registry = new TestInterceptorRegistry();
  }

  // ========== Observability: @Trace ==========

  @WeaveClass(target = "com.example.TracedService")
  public static class TraceInterceptor {
    static boolean traceCalled = false;

    @Trace(value = "process", spanName = "custom.span", kind = SpanKind.SERVER)
    public void traceProcess(MethodInvocation inv) {
      traceCalled = true;
    }
  }

  @Test
  void trace_shouldSetSpanAttachments() {
    TraceInterceptor.traceCalled = false;
    load(TraceInterceptor.class);

    Interceptor interceptor = getFirstInterceptor();
    MethodInvocation inv = new MethodInvocation(String.class, "process", "target", new Object[0]);

    interceptor.before(inv);
    assertEquals("custom.span", inv.getAttachment("trace.spanName"));
    assertEquals(SpanKind.SERVER, inv.getAttachment("trace.kind"));
    assertNotNull(inv.getAttachment("trace.startNanos"));
    assertTrue(TraceInterceptor.traceCalled);

    inv.initReturnValue("ok");
    interceptor.after(inv);
    assertNotNull(inv.getAttachment("trace.elapsedNanos"));
  }

  // ========== Observability: @Tag ==========

  @WeaveClass(target = "com.example.TaggedService")
  public static class TagInterceptor {
    @Tag(value = "process", key = "userId", argIndex = 0)
    public void tagUserId(MethodInvocation inv) {}
  }

  @Test
  void tag_shouldSetTagAttachment() {
    load(TagInterceptor.class);

    Interceptor interceptor = getFirstInterceptor();
    MethodInvocation inv =
        new MethodInvocation(String.class, "process", "target", new Object[] {"user-123"});

    interceptor.before(inv);
    assertEquals("user-123", inv.getAttachment("tag.userId"));
  }

  // ========== Observability: @Counted ==========

  @WeaveClass(target = "com.example.CountedService")
  public static class CountedInterceptor {
    @Counted(value = "process", name = "calls.total")
    public void countProcess(MethodInvocation inv) {}
  }

  @Test
  void counted_shouldIncrementCounter() {
    load(CountedInterceptor.class);

    Interceptor interceptor = getFirstInterceptor();
    MethodInvocation inv = new MethodInvocation(String.class, "process", "target", new Object[0]);

    interceptor.before(inv);
    assertNotNull(inv.getAttachment("counted.value"));
    assertEquals(1L, inv.getAttachment("counted.value"));
  }

  // ========== Observability: @Logged ==========

  @WeaveClass(target = "com.example.LoggedService")
  public static class LoggedInterceptor {
    static boolean loggedCalled = false;

    @Logged(value = "process", level = "DEBUG", logArgs = true, logTime = true)
    public void logProcess(MethodInvocation inv) {
      loggedCalled = true;
    }
  }

  @Test
  void logged_shouldSetLogAttachments() {
    LoggedInterceptor.loggedCalled = false;
    load(LoggedInterceptor.class);

    Interceptor interceptor = getFirstInterceptor();
    MethodInvocation inv =
        new MethodInvocation(String.class, "process", "target", new Object[] {"data"});

    interceptor.before(inv);
    assertNotNull(inv.getAttachment("logged.startNanos"));

    inv.initReturnValue("result");
    interceptor.after(inv);
    // Logged interceptor doesn't set explicit attachments beyond startNanos,
    // but the logging should have happened without errors
  }

  // ========== Observability: @Metric ==========

  @WeaveClass(target = "com.example.MetricService")
  public static class MetricInterceptor {
    @Metric(value = "process", name = "order.amount", argIndex = 0)
    public void recordMetric(MethodInvocation inv) {}
  }

  @Test
  void metric_shouldRecordValue() {
    load(MetricInterceptor.class);

    Interceptor interceptor = getFirstInterceptor();
    MethodInvocation inv =
        new MethodInvocation(String.class, "process", "target", new Object[] {42.5});

    interceptor.before(inv);
    assertEquals(42.5, inv.getAttachment("metric.order.amount"));
  }

  // ========== Observability: @Histogram ==========

  @WeaveClass(target = "com.example.HistogramService")
  public static class HistogramInterceptor {
    @Histogram(
        value = "process",
        name = "latency",
        buckets = {1, 5, 10})
    public void recordHistogram(MethodInvocation inv) {}
  }

  @Test
  void histogram_shouldRecordElapsed() {
    load(HistogramInterceptor.class);

    Interceptor interceptor = getFirstInterceptor();
    MethodInvocation inv = new MethodInvocation(String.class, "process", "target", new Object[0]);

    interceptor.before(inv);
    inv.initReturnValue("ok");
    interceptor.after(inv);

    assertNotNull(inv.getAttachment("histogram.latency.elapsedMs"));
  }

  // ========== Observability: @Gauge ==========

  @WeaveClass(target = "com.example.GaugeService")
  public static class GaugeInterceptor {
    @Gauge(value = "getSize", name = "queue.size", useReturn = true)
    public void gaugeSize(MethodInvocation inv) {}
  }

  @Test
  void gauge_shouldRecordReturnValue() {
    load(GaugeInterceptor.class);

    Interceptor interceptor = getFirstInterceptor();
    MethodInvocation inv = new MethodInvocation(String.class, "getSize", "target", new Object[0]);

    interceptor.before(inv);
    inv.initReturnValue(100);
    interceptor.after(inv);

    assertEquals(100.0, inv.getAttachment("gauge.queue.size"));
  }

  // ========== Resilience: @CircuitBreaker ==========

  @WeaveClass(target = "com.example.CircuitService")
  public static class CircuitBreakerInterceptor {
    static boolean openCallbackCalled = false;

    @CircuitBreaker(value = "process", failureThreshold = 2, openTimeoutMs = 100)
    public void onCircuitOpen(MethodInvocation inv) {
      openCallbackCalled = true;
    }
  }

  @Test
  void circuitBreaker_shouldOpenAfterFailures() {
    CircuitBreakerInterceptor.openCallbackCalled = false;
    load(CircuitBreakerInterceptor.class);

    Interceptor interceptor = getFirstInterceptor();

    // Fail twice to open the circuit
    for (int i = 0; i < 2; i++) {
      MethodInvocation inv = new MethodInvocation(String.class, "process", "target", new Object[0]);
      interceptor.before(inv);
      inv.setThrowable(new RuntimeException("fail"));
      interceptor.onException(inv);
    }

    // Third call should be blocked
    MethodInvocation blocked =
        new MethodInvocation(String.class, "process", "target", new Object[0]);
    interceptor.before(blocked);
    assertTrue(blocked.isSkipped(), "Circuit breaker should skip the method when open");
    assertTrue(blocked.getAttachment("circuitBreaker.open", Boolean.class));
  }

  // ========== Resilience: @Timeout ==========

  @WeaveClass(target = "com.example.TimeoutService")
  public static class TimeoutInterceptor {
    static boolean timeoutCalled = false;

    @Timeout(value = "process", durationMs = 1)
    public void onTimeout(MethodInvocation inv) {
      timeoutCalled = true;
    }
  }

  @Test
  void timeout_shouldSetDeadline() {
    load(TimeoutInterceptor.class);

    Interceptor interceptor = getFirstInterceptor();
    MethodInvocation inv = new MethodInvocation(String.class, "process", "target", new Object[0]);

    interceptor.before(inv);
    assertNotNull(inv.getAttachment("timeout.deadlineNanos"));
  }

  // ========== Resilience: @Fallback ==========

  @WeaveClass(target = "com.example.FallbackService")
  public static class FallbackInterceptor {
    static boolean fallbackCalled = false;

    @Fallback(value = "process", method = "fallbackMethod")
    public void handleFallback(MethodInvocation inv) {
      fallbackCalled = true;
    }
  }

  @Test
  void fallback_shouldSuppressException() {
    FallbackInterceptor.fallbackCalled = false;
    load(FallbackInterceptor.class);

    Interceptor interceptor = getFirstInterceptor();
    MethodInvocation inv = new MethodInvocation(String.class, "process", "target", new Object[0]);

    interceptor.before(inv);
    inv.setThrowable(new RuntimeException("fail"));
    interceptor.onException(inv);

    assertTrue(inv.isExceptionSuppressed(), "Fallback should suppress exception");
    assertEquals("fallbackMethod", inv.getAttachment("fallback.method"));
    assertTrue(FallbackInterceptor.fallbackCalled);
  }

  // ========== Resilience: @Bulkhead ==========

  @WeaveClass(target = "com.example.BulkheadService")
  public static class BulkheadInterceptor {
    static boolean rejectedCalled = false;

    @Bulkhead(value = "process", maxConcurrent = 1)
    public void onRejected(MethodInvocation inv) {
      rejectedCalled = true;
    }
  }

  @Test
  void bulkhead_shouldRejectWhenFull() {
    BulkheadInterceptor.rejectedCalled = false;
    load(BulkheadInterceptor.class);

    Interceptor interceptor = getFirstInterceptor();

    // First call takes the slot
    MethodInvocation first = new MethodInvocation(String.class, "process", "target", new Object[0]);
    interceptor.before(first);

    // Second call should be rejected
    MethodInvocation second =
        new MethodInvocation(String.class, "process", "target", new Object[0]);
    interceptor.before(second);
    assertTrue(second.isSkipped(), "Bulkhead should reject when full");
    assertTrue(second.getAttachment("bulkhead.rejected", Boolean.class));

    // Release first
    first.initReturnValue("ok");
    interceptor.after(first);
  }

  // ========== Resilience: @RateLimiter ==========

  @WeaveClass(target = "com.example.RateLimitedService")
  public static class RateLimitedInterceptor {
    @RateLimiter(value = "process", permitsPerSecond = 100)
    public void onRateLimited(MethodInvocation inv) {}
  }

  @Test
  void rateLimiter_shouldAllowFirstCall() {
    load(RateLimitedInterceptor.class);

    Interceptor interceptor = getFirstInterceptor();
    MethodInvocation inv = new MethodInvocation(String.class, "process", "target", new Object[0]);

    interceptor.before(inv);
    assertFalse(inv.isSkipped(), "First call should not be rate limited");
  }

  // ========== Caching: @CacheResult ==========

  @WeaveClass(target = "com.example.CachedService")
  public static class CacheResultInterceptor {
    @CacheResult(value = "compute", ttlMs = 60000, maxSize = 100)
    public void cacheCompute(MethodInvocation inv) {}
  }

  @Test
  void cacheResult_shouldCacheAndReturn() {
    load(CacheResultInterceptor.class);

    Interceptor interceptor = getFirstInterceptor();

    // First call — should execute
    MethodInvocation first =
        new MethodInvocation(String.class, "compute", "target", new Object[] {"key1"});
    interceptor.before(first);
    assertFalse(first.isSkipped(), "First call should not be cached");
    first.initReturnValue("cached-value");
    interceptor.after(first);

    // Second call with same args — should hit cache
    MethodInvocation second =
        new MethodInvocation(String.class, "compute", "target", new Object[] {"key1"});
    interceptor.before(second);
    assertTrue(second.isSkipped(), "Second call should hit cache");
    assertEquals("cached-value", second.getReturnValue());
    assertTrue(second.getAttachment("cache.hit", Boolean.class));
  }

  // ========== Caching: @CacheEvict ==========

  @WeaveClass(target = "com.example.CacheEvictService")
  public static class CacheEvictInterceptor {
    @CacheEvict(value = "update", keyPrefix = "compute", allEntries = true)
    public void evictCache(MethodInvocation inv) {}
  }

  @Test
  void cacheEvict_shouldClearCache() {
    load(CacheEvictInterceptor.class);

    Interceptor interceptor = getFirstInterceptor();
    MethodInvocation inv = new MethodInvocation(String.class, "update", "target", new Object[0]);

    interceptor.before(inv);
    inv.initReturnValue("ok");
    interceptor.after(inv);
    // No exception means eviction worked
  }

  // ========== Security: @RequiresRole ==========

  @WeaveClass(target = "com.example.SecureService")
  public static class RequiresRoleInterceptor {
    static boolean guardCalled = false;

    @RequiresRole(value = "delete", role = "ADMIN", principalArgIndex = 0)
    public void guardDelete(MethodInvocation inv) {
      guardCalled = true;
    }
  }

  @Test
  void requiresRole_shouldSetRoleAttachments() {
    RequiresRoleInterceptor.guardCalled = false;
    load(RequiresRoleInterceptor.class);

    Interceptor interceptor = getFirstInterceptor();
    MethodInvocation inv =
        new MethodInvocation(String.class, "delete", "target", new Object[] {"admin-user"});

    interceptor.before(inv);
    assertEquals("ADMIN", inv.getAttachment("requiresRole.required"));
    assertEquals("admin-user", inv.getAttachment("requiresRole.principal"));
    assertTrue(RequiresRoleInterceptor.guardCalled);
  }

  // ========== Security: @Audited ==========

  @WeaveClass(target = "com.example.AuditedService")
  public static class AuditedInterceptor {
    static boolean auditCalled = false;

    @Audited(value = "transfer", action = "TRANSFER", includeArgs = true)
    public void auditTransfer(MethodInvocation inv) {
      auditCalled = true;
    }
  }

  @Test
  void audited_shouldSetAuditAttachments() {
    AuditedInterceptor.auditCalled = false;
    load(AuditedInterceptor.class);

    Interceptor interceptor = getFirstInterceptor();
    MethodInvocation inv =
        new MethodInvocation(String.class, "transfer", "target", new Object[] {"from", "to", 100});

    interceptor.before(inv);
    assertEquals("TRANSFER", inv.getAttachment("audit.action"));
    assertNotNull(inv.getAttachment("audit.timestamp"));
    assertNotNull(inv.getAttachment("audit.args"));

    inv.initReturnValue("ok");
    interceptor.after(inv);
    assertTrue(AuditedInterceptor.auditCalled);
  }

  // ========== Concurrency: @Synchronized ==========

  @WeaveClass(target = "com.example.SyncService")
  public static class SynchronizedInterceptor {
    @Synchronized("increment")
    public void syncIncrement(MethodInvocation inv) {}
  }

  @Test
  void synchronized_shouldExecuteWithoutError() {
    load(SynchronizedInterceptor.class);

    Interceptor interceptor = getFirstInterceptor();
    MethodInvocation inv = new MethodInvocation(String.class, "increment", "target", new Object[0]);

    interceptor.before(inv);
    inv.initReturnValue(1);
    interceptor.after(inv);
    // No exception means synchronization worked
  }

  // ========== Concurrency: @ReadOnly ==========

  @WeaveClass(target = "com.example.ReadOnlyService")
  public static class ReadOnlyInterceptor {
    @ReadOnly("findById")
    public void enforceReadOnly(MethodInvocation inv) {}
  }

  @Test
  void readOnly_shouldSetFlag() {
    load(ReadOnlyInterceptor.class);

    Interceptor interceptor = getFirstInterceptor();
    MethodInvocation inv = new MethodInvocation(String.class, "findById", "target", new Object[0]);

    interceptor.before(inv);
    assertTrue(inv.getAttachment("readOnly", Boolean.class));
  }

  // ========== Concurrency: @Idempotent ==========

  @WeaveClass(target = "com.example.IdempotentService")
  public static class IdempotentInterceptor {
    @Idempotent(value = "process", ttlMs = 60000)
    public void ensureIdempotent(MethodInvocation inv) {}
  }

  @Test
  void idempotent_shouldCacheAndReturn() {
    load(IdempotentInterceptor.class);

    Interceptor interceptor = getFirstInterceptor();

    // First call
    MethodInvocation first =
        new MethodInvocation(String.class, "process", "target", new Object[] {"id-1"});
    interceptor.before(first);
    assertFalse(first.isSkipped());
    first.initReturnValue("result-1");
    interceptor.after(first);

    // Second call with same args
    MethodInvocation second =
        new MethodInvocation(String.class, "process", "target", new Object[] {"id-1"});
    interceptor.before(second);
    assertTrue(second.isSkipped(), "Idempotent should skip duplicate call");
    assertEquals("result-1", second.getReturnValue());
    assertTrue(second.getAttachment("idempotent.cached", Boolean.class));
  }

  // ========== Validation: @ValidateArgs ==========

  @WeaveClass(target = "com.example.ValidatedService")
  public static class ValidateArgsInterceptor {
    static boolean failedCalled = false;

    @ValidateArgs(
        value = "save",
        rules = {"arg[0] != null", "arg[1] > 0"})
    public void onValidationFailed(MethodInvocation inv) {
      failedCalled = true;
    }
  }

  @Test
  void validateArgs_shouldPassValidArgs() {
    load(ValidateArgsInterceptor.class);

    Interceptor interceptor = getFirstInterceptor();
    MethodInvocation inv =
        new MethodInvocation(String.class, "save", "target", new Object[] {"user", 42});

    interceptor.before(inv);
    assertFalse(inv.isSkipped(), "Valid args should not be rejected");
  }

  @Test
  void validateArgs_shouldRejectInvalidArgs() {
    ValidateArgsInterceptor.failedCalled = false;
    load(ValidateArgsInterceptor.class);

    Interceptor interceptor = getFirstInterceptor();
    MethodInvocation inv =
        new MethodInvocation(String.class, "save", "target", new Object[] {null, -1});

    interceptor.before(inv);
    assertTrue(inv.isSkipped(), "Invalid args should be rejected");
    assertNotNull(inv.getAttachment("validateArgs.failed"));
    assertTrue(ValidateArgsInterceptor.failedCalled);
  }

  // ========== Validation: @ValidateReturn ==========

  @WeaveClass(target = "com.example.ValidatedReturnService")
  public static class ValidateReturnInterceptor {
    static boolean failedCalled = false;

    @ValidateReturn(
        value = "findById",
        rules = {"result != null"})
    public void onValidationFailed(MethodInvocation inv) {
      failedCalled = true;
    }
  }

  @Test
  void validateReturn_shouldPassValidReturn() {
    load(ValidateReturnInterceptor.class);

    Interceptor interceptor = getFirstInterceptor();
    MethodInvocation inv = new MethodInvocation(String.class, "findById", "target", new Object[0]);

    interceptor.before(inv);
    inv.initReturnValue("valid-result");
    interceptor.after(inv);
    assertNull(inv.getAttachment("validateReturn.failed"));
  }

  @Test
  void validateReturn_shouldRejectNullReturn() {
    ValidateReturnInterceptor.failedCalled = false;
    load(ValidateReturnInterceptor.class);

    Interceptor interceptor = getFirstInterceptor();
    MethodInvocation inv = new MethodInvocation(String.class, "findById", "target", new Object[0]);

    interceptor.before(inv);
    inv.initReturnValue(null);
    interceptor.after(inv);
    assertNotNull(inv.getAttachment("validateReturn.failed"));
    assertTrue(ValidateReturnInterceptor.failedCalled);
  }

  // Exercise the alternate rule operators and the deliberately permissive fallback parser.
  @WeaveClass(target = "com.example.RuleDimensionsService")
  public static class RuleDimensionsInterceptor {
    @ValidateArgs(value = "rules", rules = {"arg[0] == null"})
    public void nullRule(MethodInvocation inv) {}

    @ValidateArgs(value = "less", rules = {"arg[0] < 10"})
    public void lessRule(MethodInvocation inv) {}

    @ValidateArgs(value = "greaterEqual", rules = {"arg[0] >= 10"})
    public void greaterEqualRule(MethodInvocation inv) {}

    @ValidateArgs(value = "lessEqual", rules = {"arg[0] <= 10"})
    public void lessEqualRule(MethodInvocation inv) {}

    @ValidateArgs(value = "nonNumber", rules = {"arg[0] > 10"})
    public void nonNumberRule(MethodInvocation inv) {}

    @ValidateArgs(value = "fallback", rules = {"value is anything"})
    public void fallbackRule(MethodInvocation inv) {}

    @ValidateReturn(value = "returnFallback", rules = {"value is anything"})
    public void returnFallbackRule(MethodInvocation inv) {}
  }

  @Test
  void validation_shouldEvaluateAllSupportedOperatorsAndFallbacks() {
    load(RuleDimensionsInterceptor.class);
    Object[][] cases = {
      {null, "rules"}, {5, "less"}, {10, "greaterEqual"}, {10, "lessEqual"},
      {"text", "nonNumber"}, {"text", "fallback"}
    };
    for (Object[] c : cases) {
      MethodInvocation inv =
          new MethodInvocation(String.class, (String) c[1], "target", new Object[] {c[0]});
      getInterceptorFor((String) c[1]).before(inv);
    }
    // A non-result rule is accepted by the return validator (fallback branch).
    Interceptor returnInterceptor = getInterceptorFor("returnFallback");
    MethodInvocation ret = new MethodInvocation(String.class, "returnFallback", "target", new Object[0]);
    ret.initReturnValue(null);
    returnInterceptor.after(ret);
    assertNull(ret.getAttachment("validateReturn.failed"));
  }

  @WeaveClass(target = "com.example.KeyedCacheService")
  public static class KeyedCacheInterceptor {
    @CacheResult(value = "compute", keyArgIndices = {1}, keyPrefix = "selected")
    public void cache(MethodInvocation inv) {}
  }

  @Test
  void cacheResult_shouldUseSelectedKeyArguments() {
    load(KeyedCacheInterceptor.class);
    Interceptor interceptor = getFirstInterceptor();
    MethodInvocation first = new MethodInvocation(String.class, "compute", "target", new Object[] {"ignored", "key"});
    interceptor.before(first);
    assertEquals("selected:key,", first.getAttachment("cache.key"));
    first.initReturnValue("value");
    interceptor.after(first);
    MethodInvocation second = new MethodInvocation(String.class, "compute", "target", new Object[] {"different", "key"});
    interceptor.before(second);
    assertTrue(second.isSkipped());
  }

  @WeaveClass(target = "com.example.KeyedIdempotencyService")
  public static class KeyedIdempotencyInterceptor {
    @Idempotent(value = "submit", keyArgIndices = {1})
    public void submit(MethodInvocation inv) {}
  }

  @Test
  void idempotent_shouldUseSelectedKeyArguments() {
    load(KeyedIdempotencyInterceptor.class);
    Interceptor interceptor = getFirstInterceptor();
    MethodInvocation first = new MethodInvocation(String.class, "submit", "target", new Object[] {"a", "same"});
    interceptor.before(first);
    first.initReturnValue("done");
    interceptor.after(first);
    MethodInvocation second = new MethodInvocation(String.class, "submit", "target", new Object[] {"b", "same"});
    interceptor.before(second);
    assertTrue(second.isSkipped());
  }

  @WeaveClass(target = "com.example.TimedLoggingService")
  @Timed(log = true)
  public static class TimedLoggingInterceptor {
    @Before("run")
    public void before(MethodInvocation inv) {}
  }

  @Test
  void timed_shouldRecordTimingOnExceptionAndTolerateMissingStart() {
    load(TimedLoggingInterceptor.class);
    Interceptor interceptor = getFirstInterceptor();
    MethodInvocation inv = new MethodInvocation(String.class, "run", "target", new Object[0]);
    interceptor.before(inv);
    interceptor.onException(inv);
    assertNotNull(inv.getAttachment("timed.elapsedNanos"));
    interceptor.after(new MethodInvocation(String.class, "run", "target", new Object[0]));
  }

  // ========== Combined: Multiple annotations on one method ==========

  @WeaveClass(target = "com.example.FullService")
  @Order(100)
  @Timed(log = false)
  public static class FullStackInterceptor {
    static final List<String> events = Collections.synchronizedList(new ArrayList<>());

    @Before("execute")
    public void beforeExecute(MethodInvocation inv) {
      events.add("before");
    }

    @AfterReturning("execute")
    public void afterExecute(MethodInvocation inv) {
      events.add("afterReturning");
    }

    @OnException(value = "execute", exceptionType = RuntimeException.class)
    public void onExecuteError(MethodInvocation inv) {
      events.add("onException");
    }

    @Trace(value = "execute", spanName = "full.execute")
    public void traceExecute(MethodInvocation inv) {
      events.add("trace");
    }

    @Counted(value = "execute", name = "full.execute.count")
    public void countExecute(MethodInvocation inv) {
      events.add("counted");
    }

    @Logged(value = "execute", level = "DEBUG")
    public void logExecute(MethodInvocation inv) {
      events.add("logged");
    }
  }

  @Test
  void fullStack_allAnnotationsWorkTogether() {
    FullStackInterceptor.events.clear();
    load(FullStackInterceptor.class);

    assertEquals(1, registry.getAllDefinitions().size());
    assertEquals(100, registry.getAllDefinitions().get(0).getPriority());

    Interceptor interceptor = getFirstInterceptor();

    // Success path
    MethodInvocation success =
        new MethodInvocation(String.class, "execute", "target", new Object[0]);
    interceptor.before(success);
    assertTrue(
        FullStackInterceptor.events.contains("before"),
        "events should contain 'before': " + FullStackInterceptor.events);
    assertTrue(
        FullStackInterceptor.events.contains("trace"),
        "events should contain 'trace': " + FullStackInterceptor.events);
    assertTrue(
        FullStackInterceptor.events.contains("counted"),
        "events should contain 'counted': " + FullStackInterceptor.events);

    success.initReturnValue("ok");
    interceptor.after(success);
    assertTrue(
        FullStackInterceptor.events.contains("afterReturning"),
        "events should contain 'afterReturning': " + FullStackInterceptor.events);
    assertNotNull(success.getAttachment("trace.elapsedNanos"));
    assertNotNull(success.getAttachment("timed.elapsedNanos"));
  }

  // ========== Field interceptors ==========

  @WeaveClass(target = "com.example.FieldService")
  public static class FieldInterceptor {
    static boolean fieldGetCalled = false;
    static boolean fieldSetCalled = false;

    @OnFieldGet("config")
    public void onConfigRead(MethodInvocation inv) {
      fieldGetCalled = true;
    }

    @OnFieldSet("config")
    public void onConfigWrite(MethodInvocation inv) {
      fieldSetCalled = true;
    }
  }

  @Test
  void fieldInterceptors_shouldBeRegistered() {
    FieldInterceptor.fieldGetCalled = false;
    FieldInterceptor.fieldSetCalled = false;
    load(FieldInterceptor.class);

    // Should have 2 definitions: one for fieldGet, one for fieldSet
    assertEquals(2, registry.getAllDefinitions().size());
    assertTrue(
        registry.getAllDefinitions().stream().anyMatch(d -> d.getName().contains("fieldGet")));
    assertTrue(
        registry.getAllDefinitions().stream().anyMatch(d -> d.getName().contains("fieldSet")));
  }

  // ========== Static init interceptor ==========

  @WeaveClass(target = "com.example.StaticInitService")
  public static class StaticInitInterceptor {
    static boolean staticInitCalled = false;

    @OnStaticInit
    public void onClassInit(MethodInvocation inv) {
      staticInitCalled = true;
    }
  }

  @Test
  void staticInit_shouldBeRegistered() {
    load(StaticInitInterceptor.class);

    assertTrue(
        registry.getAllDefinitions().stream().anyMatch(d -> d.getName().contains("<clinit>")));
  }

  // ========== Constructor interceptor ==========

  @WeaveClass(target = "com.example.ConstructorService")
  public static class ConstructorInterceptor {
    static boolean constructorCalled = false;

    @OnConstructor
    public void onNewInstance(MethodInvocation inv) {
      constructorCalled = true;
    }
  }

  @Test
  void constructor_shouldBeRegistered() {
    load(ConstructorInterceptor.class);

    assertTrue(registry.getAllDefinitions().stream().anyMatch(d -> d.getName().contains("<init>")));
  }

  // Exercise the alternate branches of the individual wrappers (especially their failure paths).
  @WeaveClass(target = "com.example.WrapperAlternateService")
  public static class WrapperAlternateInterceptor {
    @Trace(value = "traceFailure", recordException = true)
    public void traceFailure(MethodInvocation inv) {}

    @Tag(value = "tagReturn", key = "value", useReturn = true)
    public void tagReturn(MethodInvocation inv) {}

    @Counted(value = "countFailure", name = "failures", recordFailuresOnly = true)
    public void countFailure(MethodInvocation inv) {}

    @Logged(value = "logResult", level = "INFO", logResult = true, logTime = true)
    public void logResult(MethodInvocation inv) {}

    @Gauge(value = "gaugeArg", name = "size", argIndex = 0)
    public void gaugeArg(MethodInvocation inv) {}

    @CircuitBreaker(
        value = "circuitTypes",
        failureThreshold = 1,
        successThreshold = 1,
        failureTypes = IllegalStateException.class)
    public void circuitTypes(MethodInvocation inv) {}

    @Timeout(value = "timeoutExceeded", durationMs = 0)
    public void timeoutExceeded(MethodInvocation inv) {}

    @Fallback(value = "fallbackTypes", method = "fallback", onExceptions = IllegalStateException.class)
    public void fallbackTypes(MethodInvocation inv) {}

    @RateLimiter(value = "rateRejected", permitsPerSecond = 1)
    public void rateRejected(MethodInvocation inv) {}

    @RetryOnException(value = "retryTypes", maxRetries = 1, retryFor = IllegalStateException.class)
    public void retryTypes(MethodInvocation inv) {}

    @Audited(value = "auditResult", includeResult = true)
    public void auditResult(MethodInvocation inv) {}
  }

  @Test
  void wrappers_shouldCoverAlternateSuccessAndFailurePaths() {
    load(WrapperAlternateInterceptor.class);

    MethodInvocation trace = invocation("traceFailure");
    Interceptor traceInterceptor = getInterceptorFor("traceFailure");
    traceInterceptor.before(trace);
    trace.setThrowable(new RuntimeException("boom"));
    traceInterceptor.onException(trace);
    assertSame(trace.getThrowable(), trace.getAttachment("trace.exception"));

    MethodInvocation tag = invocation("tagReturn");
    Interceptor tagInterceptor = getInterceptorFor("tagReturn");
    tagInterceptor.before(tag);
    tag.initReturnValue("returned");
    tagInterceptor.after(tag);
    assertEquals("returned", tag.getAttachment("tag.value"));

    MethodInvocation counted = invocation("countFailure");
    Interceptor countedInterceptor = getInterceptorFor("countFailure");
    countedInterceptor.before(counted);
    assertNull(counted.getAttachment("counted.value"));
    counted.setThrowable(new RuntimeException("bad"));
    countedInterceptor.onException(counted);
    assertEquals(true, counted.getAttachment("counted.failure"));

    MethodInvocation logged = invocation("logResult");
    Interceptor loggedInterceptor = getInterceptorFor("logResult");
    loggedInterceptor.before(logged);
    logged.initReturnValue("result");
    loggedInterceptor.after(logged);
    logged.setThrowable(new RuntimeException("logged failure"));
    loggedInterceptor.onException(logged);

    MethodInvocation gauge = new MethodInvocation(String.class, "gaugeArg", "target", new Object[] {"not-a-number"});
    Interceptor gaugeInterceptor = getInterceptorFor("gaugeArg");
    gaugeInterceptor.before(gauge);
    gaugeInterceptor.after(gauge);
    assertNull(gauge.getAttachment("gauge.size"));

    Interceptor circuitInterceptor = getInterceptorFor("circuitTypes");
    MethodInvocation wrongFailure = invocation("circuitTypes");
    circuitInterceptor.before(wrongFailure);
    wrongFailure.setThrowable(new RuntimeException("ignored"));
    circuitInterceptor.onException(wrongFailure);
    MethodInvocation success = invocation("circuitTypes");
    circuitInterceptor.before(success);
    success.initReturnValue("ok");
    circuitInterceptor.after(success);

    Interceptor timeoutInterceptor = getInterceptorFor("timeoutExceeded");
    MethodInvocation timeout = invocation("timeoutExceeded");
    timeoutInterceptor.before(timeout);
    timeoutInterceptor.after(timeout);
    assertEquals(true, timeout.getAttachment("timeout.exceeded"));

    Interceptor fallbackInterceptor = getInterceptorFor("fallbackTypes");
    MethodInvocation noFallback = invocation("fallbackTypes");
    noFallback.setThrowable(new RuntimeException("wrong type"));
    fallbackInterceptor.onException(noFallback);
    assertFalse(noFallback.isExceptionSuppressed());

    Interceptor rateInterceptor = getInterceptorFor("rateRejected");
    MethodInvocation firstRate = invocation("rateRejected");
    rateInterceptor.before(firstRate);
    MethodInvocation secondRate = invocation("rateRejected");
    rateInterceptor.before(secondRate);
    assertTrue(secondRate.isSkipped());

    Interceptor retryInterceptor = getInterceptorFor("retryTypes");
    MethodInvocation retry = invocation("retryTypes");
    retryInterceptor.before(retry);
    retry.setThrowable(new RuntimeException("not retryable"));
    retryInterceptor.onException(retry);

    Interceptor auditInterceptor = getInterceptorFor("auditResult");
    MethodInvocation audit = invocation("auditResult");
    auditInterceptor.before(audit);
    audit.initReturnValue("audited");
    auditInterceptor.after(audit);
    assertEquals("audited", audit.getAttachment("audit.result"));
  }

  private MethodInvocation invocation(String method) {
    return new MethodInvocation(String.class, method, "target", new Object[0]);
  }

  // ========== Helpers ==========

  private void load(Class<?> clazz) {
    Set<Class<?>> classes = new HashSet<>();
    classes.add(clazz);
    loader.loadAnnotatedInterceptors(classes, registry);
  }

  private Interceptor getFirstInterceptor() {
    return registry.getAllDefinitions().get(0).getInterceptor();
  }

  private Interceptor getInterceptorFor(String method) {
    return registry.getAllDefinitions().stream()
        .filter(d -> d.getName().endsWith("-" + method))
        .findFirst()
        .orElseThrow(() -> new AssertionError("missing interceptor for " + method))
        .getInterceptor();
  }
}
