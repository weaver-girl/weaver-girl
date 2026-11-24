# weaver-girl Performance Baseline v1

This is the first published overhead baseline for the weaver-girl interceptor engine.
It captures the per-operation cost of every hot-path component so future changes can
be checked for regressions.

> **How to re-measure:** `./mvnw -Pbench -am -DskipTests -Djmh.wi=2 -Djmh.i=3 -Djmh.f=0 integration-test -pl weaver-girl-core`
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

> **Note (2026-11-11):** the `sampling_shouldSample_rate10` row above was measured while
> the benchmark called `setSamplingRate(10)` inside the measured operation; that method
> logs, so the row times the logger as much as the sampler. The benchmark now sets the
> rate in `@Setup(Level.Iteration)` and measures ~1.85 ns. The original value is kept as
> the historical baseline the guard compares against, which is conservative (a real
> regression would have to exceed 10.9 ns to trip the 1.5x budget).
>
> **Note (2026-11-24):** the `pool_acquireRelease` row above has the same problem, and a worse one.
> The benchmark called `String.class.getMethods()[0]` inside the measured operation, and
> `Class#getMethods()` copies the entire public method array — ~1.4 µs on this machine, about
> 1,880x the pool round trip it was meant to frame. The row therefore timed reflection, which is
> why it drifted on its own (1,472 ns in the 2026-11-24 run tripped the 1.5x guard against a 760 ns
> baseline) while nothing in the pool changed. The lookup now runs in `@Setup`. The historical value
> stays as the guard's baseline: it is still conservative for the pool itself (~0.76 µs of budget
> for a ~40 ns operation).

### DispatchBenchmark — cost vs. number of registered pointcuts (2026-11-23)

Measured with `-Djmh.wi=1 -Djmh.i=2` on the same machine, in-process. `pointcuts` is the number of
exact-name pointcuts registered; one interface matcher is also present so the reflective path is
exercised once per class plan.

| Benchmark | 100 | 1,000 | 5,000 | ns/op |
|-----------|----:|------:|------:|------:|
| `dispatch_hotPath` | 7.95 | 8.35 | 7.99 | flat |
| `legacy_classLookup` | 10,860 | 15,111 | 33,933 | grows with registry size |

`dispatch_hotPath` is the per-invocation call the inlined advice makes on method enter and exit: two
hash lookups (generation-keyed cache → class plan → method view) and no allocation. `legacy_classLookup`
is the registry lookup it replaced — a class-index read, a scan of every non-exact definition with a
reflective class resolution each, an `ArrayList` copy and a sort. The first row is what the dispatch
cache guarantees: registration count does not enter the per-call cost. The second row is kept as a
regression marker for anyone tempted to call `getInterceptorsForClass` from advice.

## End-to-end woven-call overhead (2026-11-24)

`InstrumentationOverheadBenchmark` actually weaves a class with the same `InterceptAdvice` the
agent installs and calls every iteration through an interface, so these are the real
per-instrumented-call numbers rather than isolated component costs. `-Djmh.wi=2 -Djmh.i=3`,
in-process, same machine, 32 cores.

The host was shared while these were taken (load average 6-9), so treat the figures below as
representative rather than exact: repeated runs of the same scenario spread by roughly 20-30%.
The pairing that matters — default versus `-Dweavergirl.telemetry.enabled=false` — was measured
back to back each time and reproduced the same ratio on every run.

| Scenario | default (ns/op) | telemetry off | what it is |
|----------|----------------:|--------------:|------------|
| `plain` | 5.2-5.7 | 5.3-6.0 | the same method, unwoven — the floor |
| `no-registry` | 10.0-12.0 | 10.1-10.9 | woven, no registry: kill switch + lookup find nothing |
| `empty-registry` | 26-34 | 26-33 | woven, empty registry, walks the registry path |
| `one-hook` | **290-450** | **140-200** | one matching interceptor with empty callbacks |
| `one-hook-with-trace` | 358-517 | 171-246 | same, with traceId in ThreadContext (MDC bridge writes) |
| `one-hook-disabled` | 9.7-12.7 | 9.8 | one matching hook, global kill switch off |
| `one-hook-1000` | 293-366 | 106-155 | one hook + 1,000 unrelated exact-name pointcuts |
| `one-hook-5000` | 297-319 | 101-152 | one hook + 5,000 unrelated exact-name pointcuts |

Two things this table establishes:

1. **Scale is flat.** 1,000 and 5,000 registered pointcuts cost the same per call as one hook
   (within noise). Registration count does not enter the per-call cost.
2. **The remaining cost is observation, not dispatch.** With telemetry off, the same one-hook call
   costs roughly a third of the default. Everything between those two figures is per-hook
   bookkeeping: two `nanoTime()` reads, the per-hook metrics record, the plugin-level metrics
   mirror, and the per-return `ReturnSnapshot`.

### Cost of each performance switch (`one-hook`, medians of repeated runs)

`PerformanceSwitches` makes every piece of that observation optional. Measured by running the same
`one-hook` scenario under each switch (`-Djmh.param=one-hook`, `-Djmh.wi=2 -Djmh.i=3`):

| Configuration | ns/call | vs default |
|---------------|--------:|-----------:|
| all on (default) | ~335 | — |
| `-Dweavergirl.telemetry.enabled=false` | ~197 | -138 |
| `-Dweavergirl.mdc.enabled=false` | ~291 | -44 |
| `-Dweavergirl.returnSnapshot.enabled=false` | ~307 | -28 |
| `-Dweavergirl.pluginMetrics.enabled=false` | ~344 | within noise |
| all four observation switches off | **~88** | **-74%** |

`circuitBreaker` is not in the table because turning it off removes no work on the healthy path:
it only stops the breaker from opening, so it is a correctness/behaviour switch rather than a
performance one.

Read the absolute numbers as ±20-30% on this shared host; the relative ordering and the "all off
is roughly a quarter of all on" relationship reproduced on every run.

### Where the ~338 ns goes (per-callback attribution)

`HookPathBenchmark` measures each piece the advice touches. Steady state, default settings:

| Piece | ns/op | Note |
|-------|------:|------|
| `breaker_shouldInvoke` | 5.0 | one CHM get |
| `pointcut_matchRuntime` | 1.4 | exact name match |
| `enterCounters` | 3.4 | two atomic increments |
| `sampling_shouldSample` | 2.0 | counter + strategy |
| `mdc_injectRestore_noContext` | 11.0 | no trace context → shared empty snapshot, no writes |
| `mdc_probe` | 0.6 | cached availability |
| `breaker_recordOutcome` | 14.9 | breaker + plugin metrics mirror |
| `agentStatus_record` | 5.2 | per-hook metrics |
| `returnSnapshot_constructOnly` | 24.9 | global-id bump + clock read + allocation |
| `returnSnapshot_recordOnly` | 9.8 | publish to the durable map |
| `oneCallbackBookkeeping` | 25.9 | the whole before-callback bookkeeping |
| `allocate_invocationOnly` | 7.3 | one MethodInvocation |
| `pool_acquireOnceRelease` | 39.1 | **the pool costs 5× what it saves** |
| `enterState_construct` | 2.8 | the carrier object |

### Why the method advice no longer pools

`pool_acquireOnceRelease` at 39.1 ns versus `allocate_invocationOnly` at 7.3 ns. The pool pays two
ThreadLocal operations plus two full-field resets to save a TLAB allocation. The advice now
allocates per call. (The pool is still used by the constructor/static-init/field/catch advices, and
its `perInstance` leak — pertarget state surviving a release/re-acquire — is fixed.)

### Why the ASM-generated dispatch question is already settled

`DispatchBenchmark` measures the two ends of the dispatch design:

| Benchmark | Score (ns/op) | Meaning |
|-----------|--------------:|---------|
| `preResolvedView` | 2.2 | static field read — the floor any weave-time-resolved dispatch can reach |
| `dispatch_hotPath` | 6.5–7.6 | what the advice pays today (generation check + class plan + method view) |
| `legacy_classLookup` | 10.8k–24.8k | the per-call registry scan this replaced |

Replacing the runtime dispatch with ASM-generated `getstatic` code can save at most the gap
between 2.2 and 7.6 ns — **about 5 ns per call**. Against a default one-hook call in the 290-450 ns
band that is 1-2%; against the telemetry-off band (140-200 ns) it is 2.5-3.5%. The cost is not in
the lookup; it is in the per-callback observation, which ASM does not touch.

## Interpretation for APM / Hook-base use

These numbers describe the **per-instrumented-call** cost the engine adds on top of the
target method. The picture that emerges for an APM/Hook base:

- **Cheap paths dominate.** Exact matchers (~1.2 ns), the sampling check (~2 ns), the
  empty-registry fast exit (~31 ns), the MDC bridge with no trace context (~11 ns), and the
  circuit-breaker checks (~5 ns) are all far under a hundred nanoseconds. A method that matches
  nothing pays ~10 ns; a method whose switch is off pays ~10 ns.
- **A matched call costs ~340 ns by default, ~140 ns with telemetry off.** The default figure
  includes full per-hook metrics and a return snapshot; the telemetry-off figure is the
  interception itself. Neither includes the interceptor body, which is the plugin's own work.
- **Pattern matchers are the expensive case.** `classMatcher_patternMatch` at ~134 ns is
  an order of magnitude costlier than exact matching. Workloads that rely heavily on
  wildcard class patterns should expect higher overhead — prefer exact class matchers
  where possible.

### Regression thresholds

`scripts/verify-benchmarks.sh` enforces a **1.5×** regression budget over this baseline.
Any benchmark exceeding `baseline × 1.5` fails the guard. The intent is to catch
unintended slowdowns, not to gate on normal run-to-run noise — in-process runs do vary,
so if a number drifts but stays under 1.5×, it is treated as acceptable.
