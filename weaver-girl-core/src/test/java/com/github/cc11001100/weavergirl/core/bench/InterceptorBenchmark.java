package com.github.cc11001100.weavergirl.core.bench;

import com.github.cc11001100.weavergirl.api.interceptor.Interceptor;
import com.github.cc11001100.weavergirl.api.interceptor.InterceptorDefinition;
import com.github.cc11001100.weavergirl.api.interceptor.MethodInvocation;
import com.github.cc11001100.weavergirl.api.matcher.ClassMatcher;
import com.github.cc11001100.weavergirl.api.matcher.MethodMatcher;
import com.github.cc11001100.weavergirl.api.pointcut.Pointcut;
import com.github.cc11001100.weavergirl.api.registry.InterceptorRegistry;
import com.github.cc11001100.weavergirl.core.registry.DefaultInterceptorRegistry;
import org.openjdk.jmh.annotations.*;

import java.util.concurrent.TimeUnit;

/**
 * JMH micro-benchmarks for interceptor framework overhead.
 *
 * Run with: mvn test-compile exec:java -Dexec.mainClass="com.github.cc11001100.weavergirl.core.bench.InterceptorBenchmarkRunner"
 * Or: java -cp target/test-classes:target/classes:... com.github.cc11001100.weavergirl.core.bench.InterceptorBenchmarkRunner
 */
@BenchmarkMode(Mode.AverageTime)
@OutputTimeUnit(TimeUnit.NANOSECONDS)
@Warmup(iterations = 3, time = 1)
@Measurement(iterations = 5, time = 1)
@Fork(1)
@State(Scope.Benchmark)
public class InterceptorBenchmark {

    private InterceptorRegistry registry;
    private InterceptorRegistry emptyRegistry;

    // A simple target object for benchmarking
    static class TargetService {
        public String execute(String input) {
            return "result: " + input;
        }
    }

    private TargetService target;

    @Setup
    public void setup() {
        // Registry with one interceptor
        registry = new DefaultInterceptorRegistry();
        registry.register(new InterceptorDefinition(
                "bench-interceptor",
                new Pointcut(ClassMatcher.byName("com.github.cc11001100.weavergirl.core.bench.InterceptorBenchmark$TargetService"),
                        MethodMatcher.byName("execute")),
                new Interceptor() {
                    @Override
                    public void before(MethodInvocation invocation) {
                        // Minimal overhead interceptor
                    }
                    @Override
                    public void after(MethodInvocation invocation) {
                        // Minimal overhead interceptor
                    }
                }
        ));

        // Empty registry for baseline comparison
        emptyRegistry = new DefaultInterceptorRegistry();

        target = new TargetService();
    }

    @Benchmark
    public String baseline_noRegistry() {
        return target.execute("test");
    }

    @Benchmark
    public Object registryLookup_emptyRegistry() {
        return emptyRegistry.getInterceptorsForClass("com.github.cc11001100.weavergirl.core.bench.InterceptorBenchmark$TargetService");
    }

    @Benchmark
    public Object registryLookup_withInterceptor() {
        return registry.getInterceptorsForClass("com.github.cc11001100.weavergirl.core.bench.InterceptorBenchmark$TargetService");
    }

    @Benchmark
    public Object pointcutMatching_hit() {
        return registry.getInterceptorsForClass("com.github.cc11001100.weavergirl.core.bench.InterceptorBenchmark$TargetService")
                .stream()
                .filter(d -> d.getPointcut().getMethodMatcher().matches("execute"))
                .count();
    }

    @Benchmark
    public Object pointcutMatching_miss() {
        return registry.getInterceptorsForClass("com.github.cc11001100.weavergirl.core.bench.InterceptorBenchmark$TargetService")
                .stream()
                .filter(d -> d.getPointcut().getMethodMatcher().matches("nonExistent"))
                .count();
    }
}