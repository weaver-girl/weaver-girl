# weaver-girl Performance Baseline v1

This is the first published overhead baseline for the weaver-girl interceptor engine.
It captures the per-operation cost of every hot-path component so future changes can
be checked for regressions.

> **How to re-measure:** `./mvnw -Pbench -DskipTests -Djmh.wi=2 -Djmh.i=3 -Djmh.f=0 integration-test -pl weaver-girl-core`
> (or `scripts/verify-benchmarks.sh` for a pass/fail regression guard).

## Measurement environment

| Item | Value |
|------|-------|
| JMH | 1.37 |
| JDK | OpenJDK 17.0.19 (Ubuntu build 17.0.19+10-1-24.04.2) |
| CPU | AMD Ryzen 9 5950X (16 cores / 32 threads) |
| OS | Linux 6.17 (Ubuntu 24.04) |
| Forks | **0 (in-process)** |
| Warmup | 2 iterations × 1 s |
| Measurement | 3 iterations × 1 s |

### About in-process (forks=0) numbers

These numbers were captured in-process (`forks=0`) because JMH's forked runner does not
reliably assemble its classpath under `exec:java` in this build. In-process measurements
are **valid for regression detection** (relative comparison of the same harness across
commits) but are **not authoritative absolute figures** — a real forked run on a quiet,
isolated box is needed before quoting absolute ns/op externally. JMH itself prints a
warning about non-forked runs; that caveat applies here.

## Results — `avgt` mode (lower is better)

All values are **nanoseconds per operation (ns/op)**, shown as `score ± 99.9% error`.

### InterceptorBenchmark — the core interception fast-path

| Benchmark | Score (ns/op) | Error (±) |
|-----------|--------------:|----------:|
| `baseline_noRegistry` | 5.632 | 1.331 |
| `pointcutMatching_hit` | 74.243 | 4.404 |
| `pointcutMatching_miss` | 72.195 | 4.883 |
| `registryLookup_emptyRegistry` | 10.699 | 2.013 |
| `registryLookup_withInterceptor` | 20.847 | 0.381 |

### MatcherBenchmark — class/method pointcut matching

| Benchmark | Score (ns/op) | Error (±) |
|-----------|--------------:|----------:|
| `classMatcher_exactMatch` | 1.181 | 0.292 |
| `classMatcher_exactMiss` | 1.478 | 0.045 |
| `classMatcher_patternMatch` | 133.748 | 26.590 |
| `classMatcher_patternMiss` | 29.722 | 3.133 |
| `methodMatcher_exactMatch` | 1.176 | 0.025 |
| `methodMatcher_patternMatch` | 44.508 | 8.822 |

### CoreComponentBenchmark — supporting hot-path components

| Benchmark | Score (ns/op) | Error (±) |
|-----------|--------------:|----------:|
| `circuitBreaker_recordSuccess` | 24.416 | 2.299 |
| `circuitBreaker_shouldInvoke_fresh` | 25.028 | 3.335 |
| `pool_acquireRelease` | 759.948 | 17.271 |
| `sampling_shouldSample_rate1` | 2.740 | 0.191 |
| `sampling_shouldSample_rate10` | 7.271 | 0.960 |

## Interpretation for APM / Hook-base use

These numbers describe the **per-instrumented-call** cost the engine adds on top of the
target method. The picture that emerges for an APM/Hook base:

- **Cheap paths dominate.** Exact matchers (~1.2 ns), the sampling check (~2.7 ns at 100%
  sample, ~7.3 ns at 10× counter), the empty-registry fast exit (~10.7 ns), and the
  circuit-breaker checks (~25 ns) are all well under a hundred nanoseconds. A method that
  matches nothing or is not sampled pays almost nothing.
- **The hot path is registry lookup + pointcut match.** A real hit costs ~20.8 ns for the
  lookup and ~74 ns for the pointcut match — i.e. roughly **~100 ns per matched call** on
  this machine before the interceptor body itself runs. That is the figure to watch.
- **Pattern matchers are the expensive case.** `classMatcher_patternMatch` at ~134 ns is
  an order of magnitude costlier than exact matching. Workloads that rely heavily on
  wildcard class patterns should expect higher overhead — prefer exact class matchers
  where possible.
- **Pool acquire/release is the outlier (~760 ns).** `MethodInvocation` acquire+release
  dominates the per-call cost when the full advice runs (enter + exit). This is the single
  largest performance lever for future optimization (e.g. cheaper pooling, or skipping pool
  allocation on the no-op path).

### Regression thresholds

`scripts/verify-benchmarks.sh` enforces a **1.5×** regression budget over this baseline.
Any benchmark exceeding `baseline × 1.5` fails the guard. The intent is to catch
unintended slowdowns, not to gate on normal run-to-run noise — in-process runs do vary,
so if a number drifts but stays under 1.5×, it is treated as acceptable.
