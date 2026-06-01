package com.github.cc11001100.weavergirl.core.bench;

import com.github.cc11001100.weavergirl.api.matcher.ClassMatcher;
import com.github.cc11001100.weavergirl.api.matcher.MethodMatcher;
import org.openjdk.jmh.annotations.*;

import java.util.concurrent.TimeUnit;

/**
 * JMH benchmarks for matcher performance.
 */
@BenchmarkMode(Mode.AverageTime)
@OutputTimeUnit(TimeUnit.NANOSECONDS)
@Warmup(iterations = 3, time = 1)
@Measurement(iterations = 5, time = 1)
@Fork(1)
@State(Scope.Benchmark)
public class MatcherBenchmark {

    private ClassMatcher exactMatcher;
    private ClassMatcher patternMatcher;
    private MethodMatcher exactMethodMatcher;
    private MethodMatcher patternMethodMatcher;

    @Setup
    public void setup() {
        exactMatcher = ClassMatcher.byName("com.example.Service");
        patternMatcher = ClassMatcher.byNamePattern("com\\.example\\..*Service");
        exactMethodMatcher = MethodMatcher.byName("execute");
        patternMethodMatcher = MethodMatcher.byNamePattern("exec.*");
    }

    @Benchmark
    public boolean classMatcher_exactMatch() {
        return exactMatcher.matches("com.example.Service");
    }

    @Benchmark
    public boolean classMatcher_exactMiss() {
        return exactMatcher.matches("com.other.Service");
    }

    @Benchmark
    public boolean classMatcher_patternMatch() {
        return patternMatcher.matches("com.example.UserService");
    }

    @Benchmark
    public boolean classMatcher_patternMiss() {
        return patternMatcher.matches("com.other.Thing");
    }

    @Benchmark
    public boolean methodMatcher_exactMatch() {
        return exactMethodMatcher.matches("execute");
    }

    @Benchmark
    public boolean methodMatcher_patternMatch() {
        return patternMethodMatcher.matches("execute");
    }
}