package com.github.cc11001100.weavergirl.core.bench;

import com.github.cc11001100.weavergirl.api.context.ThreadContext;
import com.github.cc11001100.weavergirl.api.interceptor.MethodInvocation;
import com.github.cc11001100.weavergirl.api.interceptor.ReturnSnapshot;
import com.github.cc11001100.weavergirl.api.interceptor.ReturnVersion;
import com.github.cc11001100.weavergirl.api.matcher.ClassMatcher;
import com.github.cc11001100.weavergirl.api.matcher.MethodMatcher;
import com.github.cc11001100.weavergirl.api.pointcut.Pointcut;
import com.github.cc11001100.weavergirl.core.InterceptorHolder;
import com.github.cc11001100.weavergirl.core.context.MdcInjector;
import com.github.cc11001100.weavergirl.core.interceptor.MethodInvocationPool;
import com.github.cc11001100.weavergirl.core.registry.DefaultInterceptorRegistry;
import com.github.cc11001100.weavergirl.core.status.AgentStatus;
import java.lang.reflect.Method;
import java.util.concurrent.TimeUnit;
import org.openjdk.jmh.annotations.Benchmark;
import org.openjdk.jmh.annotations.BenchmarkMode;
import org.openjdk.jmh.annotations.Fork;
import org.openjdk.jmh.annotations.Level;
import org.openjdk.jmh.annotations.Measurement;
import org.openjdk.jmh.annotations.Mode;
import org.openjdk.jmh.annotations.OutputTimeUnit;
import org.openjdk.jmh.annotations.Scope;
import org.openjdk.jmh.annotations.Setup;
import org.openjdk.jmh.annotations.State;
import org.openjdk.jmh.annotations.TearDown;
import org.openjdk.jmh.annotations.Warmup;

/**
 * Attribution benchmark for the per-hook bookkeeping inside {@code InterceptAdvice}.
 *
 * <p>{@link InstrumentationOverheadBenchmark} measures the total cost of one woven call; this
 * splits the ~400 ns it reports between the pieces the advice touches around each hook callback,
 * so it is visible which one is worth optimizing and which are already noise. Each benchmark
 * measures exactly what the advice does per invocation for that component:
 *
 * <ul>
 *   <li>{@code mdc_injectRestore} — the MDC bridge with and without trace context on the thread.
 *   <li>{@code pool_acquireOnceRelease} — one acquire/release pair, the cost the carrier removed
 *       the second of.
 *   <li>{@code breaker_recordOutcome} / {@code breaker_shouldInvoke} — per-callback breaker work.
 *   <li>{@code agentStatus_record} — per-callback metric recording.
 *   <li>{@code pluginMetrics_record} — the plugin-level mirror of the same callback.
 *   <li>{@code returnVersion_version} — per-successful-call snapshot publishing.
 *   <li>{@code pointcut_matchRuntime} — the per-callback match re-check.
 * </ul>
 *
 * <p>Run with: {@code ./mvnw -Pbench -am -DskipTests -Djmh.include=HookPathBenchmark
 * integration-test -pl weaver-girl-core}.
 */
@BenchmarkMode(Mode.AverageTime)
@OutputTimeUnit(TimeUnit.NANOSECONDS)
@Warmup(iterations = 2, time = 1)
@Measurement(iterations = 3, time = 1)
@Fork(0)
@State(Scope.Benchmark)
public class HookPathBenchmark {

  private static final String HOOK = "bench-hook";
  private static final String METHOD_KEY = "bench.HookPathTarget.work";
  private static final String CLASS_NAME = "bench.HookPathTarget";

  private Method method;
  private Object target;
  private Object[] arguments;
  private Pointcut pointcut;

  @Setup(Level.Trial)
  public void setUp() throws Exception {
    method = HookPathTarget.class.getMethod("work", String.class);
    target = new HookPathTarget();
    arguments = new Object[] {"x"};
    pointcut = new Pointcut(ClassMatcher.byName(CLASS_NAME), MethodMatcher.byName("work"));
    InterceptorHolder.setRegistry(new DefaultInterceptorRegistry());
    // Warm the breaker/plugin/metrics maps: the benchmark measures the steady state, which is
    // what an application sees after the first call.
    InterceptorHolder.recordOutcome(HOOK, true, 1L);
    InterceptorHolder.shouldInvoke(HOOK);
    AgentStatus.getInstance().recordInterceptorInvocation(HOOK, true, 1L);
  }

  @TearDown(Level.Trial)
  public void tearDown() {
    ThreadContext.clear();
    InterceptorHolder.setRegistry(null);
  }

  /** A target class, only here to provide a Method and an instance for the pool. */
  public static class HookPathTarget {
    public String work(String input) {
      return input;
    }
  }

  // --- MDC bridge ---

  /** MDC inject + restore with no trace context on the thread: the common case. */
  @Benchmark
  public Object mdc_injectRestore_noContext() {
    MdcInjector.MdcSnapshot snapshot = MdcInjector.inject();
    MdcInjector.restore(snapshot);
    return snapshot;
  }

  /** MDC inject + restore with trace context set, the case that writes the bridge keys. */
  @Benchmark
  public Object mdc_injectRestore_withTrace(MdcState state) {
    MdcInjector.MdcSnapshot snapshot = MdcInjector.inject();
    MdcInjector.restore(snapshot);
    return snapshot;
  }

  @State(Scope.Benchmark)
  public static class MdcState {
    @Setup(Level.Trial)
    public void putTrace() {
      ThreadContext.put("traceId", "bench-trace");
    }

    @TearDown(Level.Trial)
    public void clearTrace() {
      ThreadContext.clear();
    }
  }

  // --- Pool ---

  /** One pooled acquire and release, as the carrier now does once per call. */
  @Benchmark
  public Object pool_acquireOnceRelease() {
    MethodInvocation invocation =
        MethodInvocationPool.acquire(HookPathTarget.class, "work", method, target, arguments);
    MethodInvocationPool.release(invocation);
    return invocation;
  }

  /**
   * A fresh invocation with the same work, no pool: what the alternative to pooling costs. If this
   * is cheaper than {@link #pool_acquireOnceRelease()} the pool is paying ThreadLocal bookkeeping
   * to save an allocation TLAB already makes cheap.
   */
  @Benchmark
  public Object pool_allocateFresh() {
    MethodInvocation invocation =
        new MethodInvocation(HookPathTarget.class, "work", method, target, arguments);
    invocation.clear();
    return invocation;
  }

  /** Just the allocator cost of one MethodInvocation (arguments already built). */
  @Benchmark
  public Object allocate_invocationOnly() {
    return new MethodInvocation(HookPathTarget.class, "work", method, target, arguments);
  }

  private static final ThreadLocal<Object> PROBE = new ThreadLocal<>();

  /** ThreadLocal get, the cheapest form of thread-local access. */
  @Benchmark
  public Object threadLocal_get() {
    return PROBE.get();
  }

  /** ThreadLocal set, as release does. */
  @Benchmark
  public void threadLocal_set() {
    PROBE.set(Boolean.TRUE);
  }

  /** ThreadLocal remove, as acquire does. */
  @Benchmark
  public void threadLocal_remove() {
    PROBE.remove();
  }

  // --- Circuit breaker ---

  /** What the advice calls before each callback. */
  @Benchmark
  public boolean breaker_shouldInvoke() {
    return InterceptorHolder.shouldInvoke(HOOK);
  }

  /** What the advice calls after each callback: breaker plus plugin metrics mirror. */
  @Benchmark
  public void breaker_recordOutcome() {
    InterceptorHolder.recordOutcome(HOOK, true, 1L);
  }

  // --- Status metrics ---

  /** The per-callback AgentStatus recording. */
  @Benchmark
  public void agentStatus_record() {
    AgentStatus.getInstance().recordInterceptorInvocation(HOOK, true, 1L);
  }

  // --- Versioning ---

  /** The per-successful-call version bump plus snapshot allocation and publish. */
  @Benchmark
  public Object returnVersion_version() {
    long version = ReturnVersion.next(METHOD_KEY);
    ReturnSnapshot created = new ReturnSnapshot("x", version, "work", String.class);
    ReturnVersion.recordSnapshot(METHOD_KEY, created);
    return created;
  }

  /** Just the version counter bump, with the counter already cached by the caller. */
  @Benchmark
  public long returnVersion_nextOnly(VersionState state) {
    return state.counter.incrementAndGet();
  }

  /** Just the snapshot allocation: one global atomic bump, one clock read, one object. */
  @Benchmark
  public Object returnSnapshot_constructOnly() {
    return new ReturnSnapshot("x", 1L, "work", String.class);
  }

  /** Just the publish, with the snapshot already built. */
  @Benchmark
  public void returnSnapshot_recordOnly(SnapshotState state) {
    ReturnVersion.recordSnapshot(METHOD_KEY, state.snapshot);
  }

  @State(Scope.Benchmark)
  public static class VersionState {
    final java.util.concurrent.atomic.AtomicLong counter =
        ReturnVersion.counterFor(METHOD_KEY);
  }

  @State(Scope.Benchmark)
  public static class SnapshotState {
    final ReturnSnapshot snapshot = new ReturnSnapshot("x", 1L, "work", String.class);
  }

  // --- Plugin-level metrics ---

  /** The plugin metrics mirror the advice records through {@code recordOutcome}. */
  @Benchmark
  public void pluginTracker_record() {
    com.github.cc11001100.weavergirl.api.plugin.PluginPerformanceTracker.recordInvocationByName(
        "bench", 0L, false);
  }

  /** Only the map lookup the plugin tracker does per callback, entry already present. */
  @Benchmark
  public void pluginTracker_computeIfAbsent(PluginState state) {
    com.github.cc11001100.weavergirl.api.plugin.PluginPerformanceTracker.getMetrics("bench");
  }

  @State(Scope.Benchmark)
  public static class PluginState {
    @Setup(Level.Trial)
    public void seed() {
      com.github.cc11001100.weavergirl.api.plugin.PluginPerformanceTracker
          .recordInvocationByName("bench", 0L, false);
    }
  }

  // --- MDC pieces ---

  /** Inject alone, the work enter does. */
  @Benchmark
  public MdcInjector.MdcSnapshot mdc_injectOnly() {
    return MdcInjector.inject();
  }

  /** Restore alone, the work exit does. */
  @Benchmark
  public void mdc_restoreOnly(MdcSnapshotState state) {
    MdcInjector.restore(state.snapshot);
  }

  @State(Scope.Benchmark)
  public static class MdcSnapshotState {
    MdcInjector.MdcSnapshot snapshot = MdcInjector.inject();
  }

  /** Three ThreadContext reads: the cheapest possible "is any trace context present" probe. */
  @Benchmark
  public Object threadContext_threeReads() {
    ThreadContext.get("traceId");
    ThreadContext.get("spanId");
    return ThreadContext.get("tenantId");
  }

  // --- Pointcut match ---

  /** The per-callback {@code matchRuntime} re-check. */
  @Benchmark
  public boolean pointcut_matchRuntime() {
    return pointcut.matchRuntime(CLASS_NAME, "work", null, arguments, "x", null);
  }

  /** The full set of bookkeeping one before callback causes, end to end in one call. */
  @Benchmark
  public boolean oneCallbackBookkeeping() {
    if (!InterceptorHolder.shouldInvoke(HOOK)) {
      return false;
    }
    if (!pointcut.matchRuntime(CLASS_NAME, "work", null, arguments, null, null)) {
      return false;
    }
    InterceptorHolder.recordOutcome(HOOK, true, 5L);
    AgentStatus.getInstance().recordInterceptorInvocation(HOOK, true, 5L);
    return true;
  }

  // --- Enter-path pieces not covered above ---

  /** The top-of-call counters that run before any hook is known to match. */
  @Benchmark
  public void enterCounters() {
    InterceptorHolder.incrementInterceptorInvocationCount();
  }

  /** The sampling gate, which runs once per intercepted call. */
  @Benchmark
  public boolean sampling_shouldSample() {
    return com.github.cc11001100.weavergirl.core.sampling.SamplingController.getInstance()
        .shouldSample();
  }

  /** The MDC availability check that guards the whole bridge. */
  @Benchmark
  public boolean mdc_probe() {
    return MdcInjector.isMdcAvailable();
  }

  /** Constructing the object enter hands to exit. */
  @Benchmark
  public Object enterState_construct(EnterStateFixture fx) {
    return new com.github.cc11001100.weavergirl.core.InterceptAdvice.EnterState(
        fx.invocation, null, false, fx.view, 1L);
  }

  @State(Scope.Benchmark)
  public static class EnterStateFixture {
    MethodInvocation invocation;
    InterceptorHolder.DispatchView view = InterceptorHolder.dispatch("bench.None", "none");

    @Setup(Level.Trial)
    public void setUp() throws Exception {
      Method work = HookPathTarget.class.getMethod("work", String.class);
      invocation = new MethodInvocation(HookPathTarget.class, "work", work, null, new Object[0]);
    }
  }
}
