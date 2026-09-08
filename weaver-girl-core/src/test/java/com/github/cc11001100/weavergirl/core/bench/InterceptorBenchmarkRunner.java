package com.github.cc11001100.weavergirl.core.bench;

import org.openjdk.jmh.runner.Runner;
import org.openjdk.jmh.runner.options.Options;
import org.openjdk.jmh.runner.options.OptionsBuilder;

/**
 * Convenience runner for all JMH benchmarks.
 *
 * <p>Iterations are tunable via system properties so CI can trade precision for speed: {@code
 * -Djmh.wi=2 -Djmh.i=3 -Djmh.f=1} (defaults: warmup 3, measurement 5, forks 1).
 *
 * <p>Run with: {@code ./mvnw -Pbench -DskipTests integration-test -pl weaver-girl-core}
 */
public class InterceptorBenchmarkRunner {

  public static void main(String[] args) throws Exception {
    int wi = Integer.getInteger("jmh.wi", 3);
    int i = Integer.getInteger("jmh.i", 5);
    // Default to in-process (forks=0) so the run is reliable under exec:java and CI —
    // in-process numbers are valid for REGRESSION detection (relative comparison).
    // For authoritative absolute numbers, set -Djmh.f=1 (requires a real java classpath,
    // e.g. via scripts/run-benchmarks.sh, because JMH's fork needs the true classpath).
    int f = Integer.getInteger("jmh.f", 0);
    Options opt =
        new OptionsBuilder()
            .include(InterceptorBenchmark.class.getSimpleName())
            .include(MatcherBenchmark.class.getSimpleName())
            .include(CoreComponentBenchmark.class.getSimpleName())
            .warmupIterations(wi)
            .measurementIterations(i)
            .forks(f)
            .build();
    new Runner(opt).run();
  }
}
