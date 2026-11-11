package com.github.cc11001100.weavergirl.core.bench;

import com.github.cc11001100.weavergirl.core.InterceptorHolder;
import com.github.cc11001100.weavergirl.core.interceptor.MethodInvocationPool;
import com.github.cc11001100.weavergirl.core.sampling.SamplingController;
import java.util.concurrent.TimeUnit;
import org.openjdk.jmh.annotations.*;

/**
 * JMH benchmarks for core runtime components: SamplingController, CircuitBreaker, and
 * MethodInvocationPool.
 */
@BenchmarkMode(Mode.AverageTime)
@OutputTimeUnit(TimeUnit.NANOSECONDS)
@Warmup(iterations = 3, time = 1)
@Measurement(iterations = 5, time = 1)
@Fork(1)
@State(Scope.Benchmark)
public class CoreComponentBenchmark {

  private SamplingController samplingController;

  @Setup
  public void setup() {
    samplingController = SamplingController.getInstance();
    samplingController.setSamplingRate(1);
  }

  // --- SamplingController ---

  @Benchmark
  public boolean sampling_shouldSample_rate1() {
    return samplingController.shouldSample();
  }

  /**
   * Hot path at a 1-in-10 sampling rate. The rate is changed once per iteration, not inside the
   * benchmarked operation: {@link SamplingController#setSamplingRate(int)} logs on every change,
   * so measuring it per invocation would time the logger instead of {@code shouldSample()}.
   */
  @Benchmark
  public boolean sampling_shouldSample_rate10(Rate10State state) {
    return samplingController.shouldSample();
  }

  @State(Scope.Benchmark)
  public static class Rate10State {
    private final SamplingController controller = SamplingController.getInstance();

    @Setup(Level.Iteration)
    public void raiseRate() {
      controller.setSamplingRate(10);
    }

    @TearDown(Level.Iteration)
    public void restoreRate() {
      controller.setSamplingRate(1);
    }
  }

  // --- Circuit Breaker (InterceptorHolder) ---

  @Benchmark
  public boolean circuitBreaker_shouldInvoke_fresh() {
    return InterceptorHolder.shouldInvoke("bench-fresh-" + Thread.currentThread().getId());
  }

  @Benchmark
  public void circuitBreaker_recordSuccess() {
    InterceptorHolder.recordInterceptorSuccess("bench-success-" + Thread.currentThread().getId());
  }

  // --- MethodInvocationPool ---

  @Benchmark
  public Object pool_acquireRelease() {
    Object target = "target";
    java.lang.reflect.Method method = String.class.getMethods()[0];
    MethodInvocationPool.acquire(String.class, "toString", method, target, new Object[0]);
    // Simulate use
    com.github.cc11001100.weavergirl.api.interceptor.MethodInvocation inv =
        MethodInvocationPool.acquire(String.class, "toString", method, target, new Object[0]);
    MethodInvocationPool.release(inv);
    return inv;
  }
}
