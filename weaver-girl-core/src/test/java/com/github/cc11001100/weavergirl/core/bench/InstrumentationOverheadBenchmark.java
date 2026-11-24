package com.github.cc11001100.weavergirl.core.bench;

import com.github.cc11001100.weavergirl.api.interceptor.Interceptor;
import com.github.cc11001100.weavergirl.api.interceptor.InterceptorDefinition;
import com.github.cc11001100.weavergirl.api.interceptor.MethodInvocation;
import com.github.cc11001100.weavergirl.api.matcher.ClassMatcher;
import com.github.cc11001100.weavergirl.api.matcher.MethodMatcher;
import com.github.cc11001100.weavergirl.api.pointcut.Pointcut;
import com.github.cc11001100.weavergirl.core.InterceptAdvice;
import com.github.cc11001100.weavergirl.core.InterceptorHolder;
import com.github.cc11001100.weavergirl.core.registry.DefaultInterceptorRegistry;
import com.github.cc11001100.weavergirl.core.sampling.SamplingController;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;
import net.bytebuddy.ByteBuddy;
import net.bytebuddy.asm.Advice;
import net.bytebuddy.dynamic.loading.ClassLoadingStrategy;
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
 * End-to-end cost of a real woven method: the target class is actually transformed with the same
 * {@link InterceptAdvice} the agent installs, and every benchmark iteration calls it through an
 * interface.
 *
 * <p>Unlike {@link DispatchBenchmark}, which isolates the registry lookup, this measures the whole
 * inlined advice path — kill switch, sampling counter, dispatch lookup, pooled invocation,
 * interceptor callbacks, metrics recording, return-value versioning and MDC bridging. The
 * scenarios split that total so the dominant term is visible:
 *
 * <ul>
 *   <li>{@code plain} — the same method without weaving, the floor.
 *   <li>{@code no-registry} — woven, no registry installed: advice runs but finds nothing.
 *   <li>{@code empty-registry} — woven, empty registry: same as above through the registry path.
 *   <li>{@code one-hook} — one matching interceptor with empty callbacks.
 *   <li>{@code one-hook-disabled} — one matching interceptor but the global kill switch is off.
 *   <li>{@code one-hook-1000} / {@code one-hook-5000} — the matching hook plus 1k/5k unrelated
 *       exact-name pointcuts, to show whether scale changes the per-call cost.
 * </ul>
 *
 * <p>Run with: {@code ./mvnw -Pbench -DskipTests -Djmh.include=InstrumentationOverheadBenchmark
 * integration-test -pl weaver-girl-core}.
 */
@BenchmarkMode(Mode.AverageTime)
@OutputTimeUnit(TimeUnit.NANOSECONDS)
@Warmup(iterations = 3, time = 1)
@Measurement(iterations = 5, time = 1)
@Fork(0)
@State(Scope.Benchmark)
public class InstrumentationOverheadBenchmark {

  /** Interface boundary so the unwoven and woven classes are called the same way. */
  public interface Workload {
    String work(String input);
  }

  /** The class that gets woven. Its name is what the pointcuts below match. */
  public static class PlainTarget implements Workload {
    @Override
    public String work(String input) {
      return "result:" + input;
    }
  }

  @Param({
    "plain",
    "no-registry",
    "empty-registry",
    "one-hook",
    "one-hook-with-trace",
    "one-hook-disabled",
    "one-hook-1000",
    "one-hook-5000"
  })
  public String scenario;

  private static final String TARGET_CLASS = PlainTarget.class.getName();
  private static final AtomicLong BEFORE_CALLS = new AtomicLong();
  private static final AtomicLong AFTER_CALLS = new AtomicLong();

  private Workload target;
  private DefaultInterceptorRegistry registry;

  @Setup(Level.Trial)
  public void setUp() throws Exception {
    InterceptorHolder.setRegistry(null);
    InterceptorHolder.setInterceptionEnabled(true, "bench");
    SamplingController.getInstance().setSamplingRate(1);
    SamplingController.getInstance().resetCounter();
    BEFORE_CALLS.set(0);
    AFTER_CALLS.set(0);

    Class<? extends Workload> woven = weave();

    if ("plain".equals(scenario)) {
      target = new PlainTarget();
      return;
    }
    target = woven.newInstance();

    if ("no-registry".equals(scenario)) {
      return;
    }

    registry = new DefaultInterceptorRegistry();
    if (!"empty-registry".equals(scenario)) {
      registry.register(matchingHook());
      int extras = 0;
      if ("one-hook-1000".equals(scenario)) {
        extras = 1000;
      } else if ("one-hook-5000".equals(scenario)) {
        extras = 5000;
      }
      for (int i = 0; i < extras; i++) {
        registry.register(
            new InterceptorDefinition(
                "other-" + i,
                new Pointcut(ClassMatcher.byName("bench.Other" + i), MethodMatcher.byName("handle")),
                new Interceptor() {},
                0));
      }
      if ("one-hook-disabled".equals(scenario)) {
        InterceptorHolder.setInterceptionEnabled(false, "bench");
      }
    }
    InterceptorHolder.setRegistry(registry);
    if ("one-hook-with-trace".equals(scenario)) {
      // Exercises the full MDC bridge: the thread carries trace context, so inject() copies the
      // MDC map and writes the bridge keys instead of taking the no-op fast path.
      com.github.cc11001100.weavergirl.api.context.ThreadContext.put("traceId", "trace-bench");
    }

    // Prove the scenario is what it claims: one call either reaches the hook or it does not.
    boolean expectHook = "one-hook".equals(scenario)
        || "one-hook-with-trace".equals(scenario)
        || "one-hook-1000".equals(scenario)
        || "one-hook-5000".equals(scenario);
    target.work("probe");
    long before = BEFORE_CALLS.get();
    long after = AFTER_CALLS.get();
    if (expectHook && (before != 1 || after != 1)) {
      throw new IllegalStateException(
          "scenario " + scenario + " did not invoke the hook: before=" + before + " after=" + after);
    }
    if (!expectHook && (before != 0 || after != 0)) {
      throw new IllegalStateException(
          "scenario " + scenario + " invoked the hook unexpectedly: before=" + before);
    }
    BEFORE_CALLS.set(0);
    AFTER_CALLS.set(0);
  }

  @TearDown(Level.Trial)
  public void tearDown() {
    InterceptorHolder.setInterceptionEnabled(true, "bench");
    InterceptorHolder.setRegistry(null);
    com.github.cc11001100.weavergirl.api.context.ThreadContext.clear();
  }

  @Benchmark
  public String call() {
    return target.work("x");
  }

  private static InterceptorDefinition matchingHook() {
    return new InterceptorDefinition(
        "bench-hook",
        new Pointcut(ClassMatcher.byName(TARGET_CLASS), MethodMatcher.byName("work")),
        new Interceptor() {
          @Override
          public void before(MethodInvocation invocation) {
            BEFORE_CALLS.incrementAndGet();
          }

          @Override
          public void after(MethodInvocation invocation) {
            AFTER_CALLS.incrementAndGet();
          }
        },
        0);
  }

  @SuppressWarnings("unchecked")
  private static Class<? extends Workload> weave() {
    return (Class<? extends Workload>)
        new ByteBuddy()
            .redefine(PlainTarget.class)
            .visit(Advice.to(InterceptAdvice.class).on(net.bytebuddy.matcher.ElementMatchers.named("work")))
            .make()
            // CHILD_FIRST: the woven type keeps the target's name, so it has to shadow the
            // parent-loaded original in the wrapper loader. WRAPPER delegates parent-first and
            // then refuses to define a class the parent already has.
            .load(
                InstrumentationOverheadBenchmark.class.getClassLoader(),
                ClassLoadingStrategy.Default.CHILD_FIRST)
            .getLoaded();
  }
}
