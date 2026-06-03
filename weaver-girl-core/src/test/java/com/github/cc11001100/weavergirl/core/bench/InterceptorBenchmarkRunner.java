package com.github.cc11001100.weavergirl.core.bench;

import org.openjdk.jmh.runner.Runner;
import org.openjdk.jmh.runner.options.Options;
import org.openjdk.jmh.runner.options.OptionsBuilder;

/**
 * Convenience runner for all JMH benchmarks.
 * Run with: mvn test-compile exec:java -Dexec.mainClass="com.github.cc11001100.weavergirl.core.bench.InterceptorBenchmarkRunner" -pl weaver-girl-core
 */
public class InterceptorBenchmarkRunner {
    public static void main(String[] args) throws Exception {
        Options opt = new OptionsBuilder()
                .include(InterceptorBenchmark.class.getSimpleName())
                .include(MatcherBenchmark.class.getSimpleName())
                .include(CoreComponentBenchmark.class.getSimpleName())
                .build();
        new Runner(opt).run();
    }
}