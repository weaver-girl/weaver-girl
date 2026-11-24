package com.github.cc11001100.weavergirl.core.bench;

import com.github.cc11001100.weavergirl.api.interceptor.Interceptor;
import com.github.cc11001100.weavergirl.api.interceptor.InterceptorDefinition;
import com.github.cc11001100.weavergirl.api.matcher.ClassMatcher;
import com.github.cc11001100.weavergirl.api.matcher.MethodMatcher;
import com.github.cc11001100.weavergirl.api.pointcut.Pointcut;
import com.github.cc11001100.weavergirl.core.InterceptorHolder;
import com.github.cc11001100.weavergirl.core.registry.DefaultInterceptorRegistry;
import java.util.concurrent.TimeUnit;
import org.openjdk.jmh.annotations.Benchmark;
import org.openjdk.jmh.annotations.BenchmarkMode;
import org.openjdk.jmh.annotations.Fork;
import org.openjdk.jmh.annotations.Level;
import org.openjdk.jmh.annotations.Measurement;
import org.openjdk.jmh.annotations.Mode;
import org.openjdk.jmh.annotations.OutputTimeUnit;
import org.openjdk.jmh.annotations.Param;
import org.openjdk.jmh.annotations.Scope;
import org.openjdk.jmh.annotations.Setup;
import org.openjdk.jmh.annotations.State;
import org.openjdk.jmh.annotations.TearDown;
import org.openjdk.jmh.annotations.Warmup;

/**
 * How the per-invocation dispatch cost scales with the number of registered pointcuts.
 *
 * <p>{@code dispatch_hotPath} is what the inlined advice calls on every intercepted method
 * enter/exit; {@code legacy_classLookup} is the per-call registry lookup it replaced (a class-index
 * read plus a scan of every non-exact definition, allocation and sort included). The first should
 * stay flat as {@code pointcuts} grows; the second is the regression this work removed.
 *
 * <p>Run with: {@code ./mvnw -Pbench -DskipTests integration-test -pl weaver-girl-core}.
 */
@BenchmarkMode(Mode.AverageTime)
@OutputTimeUnit(TimeUnit.NANOSECONDS)
@Warmup(iterations = 3, time = 1)
@Measurement(iterations = 5, time = 1)
@Fork(0)
@State(Scope.Benchmark)
public class DispatchBenchmark {

  /** Number of exact-name pointcuts registered. */
  @Param({"100", "1000", "5000"})
  public int pointcuts;

  private DefaultInterceptorRegistry registry;
  private InterceptorHolder.DispatchView preResolved;

  @Setup(Level.Trial)
  public void setUp() {
    registry = new DefaultInterceptorRegistry();
    for (int i = 0; i < pointcuts; i++) {
      registry.register(
          new InterceptorDefinition(
              "p" + i,
              new Pointcut(ClassMatcher.byName("bench.C" + i), MethodMatcher.byName("handle")),
              new Interceptor() {},
              0));
    }
    // A handful of non-exact class matchers: they are the ones that need a reflective decision,
    // once per class plan rather than once per call.
    registry.register(
        new InterceptorDefinition(
            "runnable",
            new Pointcut(ClassMatcher.byInterface("java.lang.Runnable"), MethodMatcher.any()),
            new Interceptor() {},
            0));
    InterceptorHolder.setRegistry(registry);
    // Build the plan for the measured class outside the measurement window.
    preResolved = InterceptorHolder.dispatch("bench.C0", "handle");
  }

  @TearDown(Level.Trial)
  public void tearDown() {
    InterceptorHolder.setRegistry(null);
  }

  @Benchmark
  public InterceptorHolder.DispatchView dispatch_hotPath() {
    return InterceptorHolder.dispatch("bench.C0", "handle");
  }

  @Benchmark
  public int legacy_classLookup() {
    return registry.getInterceptorsForClass("bench.C0").size();
  }

  private static final java.util.concurrent.ConcurrentHashMap<String, Object> CHM =
      new java.util.concurrent.ConcurrentHashMap<>();

  static {
    CHM.put("bench-hook", new Object());
  }

  /** Cost of the one hash lookup that the remaining per-hook bookkeeping pays several times. */
  @Benchmark
  public Object chm_get() {
    return CHM.get("bench-hook");
  }

  /**
   * The theoretical floor for any dispatch mechanism that resolves the hook set at weave time
   * (an ASM-generated {@code getstatic} of the per-method view): one static field read. Compare
   * with {@link #dispatch_hotPath()}, which is what the agent pays today.
   */
  @Benchmark
  public Object preResolvedView() {
    return preResolved;
  }

  /** {@code computeIfAbsent} on an existing key, which the metrics maps use per invocation. */
  @Benchmark
  public Object chm_computeIfAbsent_existing() {
    return CHM.computeIfAbsent("bench-hook", k -> new Object());
  }

  /** The two clock reads the advice takes around each hook callback. */
  @Benchmark
  public long nanotime_pair() {
    long start = System.nanoTime();
    return System.nanoTime() - start;
  }

  /** Coarse clock alternative, for comparison with {@link #nanotime_pair()}. */
  @Benchmark
  public long currentTimeMillis() {
    return System.currentTimeMillis();
  }
}
