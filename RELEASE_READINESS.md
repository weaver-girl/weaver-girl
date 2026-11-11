# Weaver-Girl Release Readiness (verified 2026-11-11)

Honest status of the quality gates and what stands between the current tree and a
shippable release. Every row below is backed by a command run on this machine on
2026-11-11, not by an older audit.

## Verdict

**The code and packaging are release-ready.** `scripts/release-check.sh` passes on JDK 17:
a full `-Pquality-gate clean verify` over all 153 modules (3,671 tests, 0 failures),
SpotBugs/Checkstyle clean everywhere, JaCoCo met on the engine/runtime modules, all 444
`jar`/`sources`/`javadoc` publication artifacts present, and the distribution archive
verified to contain the agent and 140 capability jars.

What remains is **external to the repository**: the Maven Central credentials/GPG key and
the actual tag push + Portal publish. Those are deliberately not performed here.

## Gate status

| Gate | Command | Result on 2026-11-11 |
|---|---|---|
| Full release gate | `scripts/release-check.sh` (JDK 17) | ✅ PASSED — prints `RELEASE CHECK PASSED` |
| Unit tests | part of `clean verify -Pquality-gate` | ✅ 3,671 tests, 0 failures, 0 errors, 0 skipped |
| SpotBugs (High) | `spotbugs:check` in every module | ✅ 0 bugs (see fixes below) |
| Checkstyle | `checkstyle:check` in every module | ✅ 0 violations |
| JaCoCo (engine/runtime) | 80% instructions / 70% branches | ✅ met on `api`, `core`, `annotation-runtime`, `runtime-*` |
| JaCoCo exemptions | see below | ⚪ documented, not silently disabled |
| Publication artifacts | `release-check.sh` artifact loop | ✅ 444 artifacts (148 modules × jar/sources/javadoc) |
| Agent manifest | `Premain-Class` / `Agent-Class` | ✅ both `com.github.cc11001100.weavergirl.agent.WeaverGirlAgent` |
| Distribution archive | `weaver-girl-agent-<version>-dist.zip` | ✅ 15.6 MB, agent jar + `plugins/` (126 plugin + 14 runtime jars) |
| Overhead regression guard | `scripts/verify-benchmarks.sh` | ✅ 16/16 within 1.5x budget (max ratio 1.408) |
| Packaged-agent smoke | `scripts/cross-jdk-smoke.sh` | ✅ PASS on JDK 8, 11, 17, 21 |
| Maven Central packaging | `release` profile + `release.yml` | ✅ tooling ready; publishing needs secrets |
| GitHub Release | `release.yml` on `v*` tag | ✅ tooling ready; no tag pushed |

## Coverage policy and exemptions

The 80% instruction / 70% branch floor is enforced on the modules whose logic runs in the
agent engine:

- `weaver-girl-annotation-runtime`, `weaver-girl-api`, `weaver-girl-core`
- the 14 `weaver-girl-runtime-*` capability jars

The following are exempt from the **ratio** check (their tests still run — a failing test
still fails the build):

- `weaver-girl-sample` — demo application, not a published library.
- `weaver-girl-agent` — launcher glue (`premain`/`agentmain`, plugin-jar discovery, the
  child-first `AgentClassLoader`). It is exercised for real by the packaged-agent smoke and
  `JavaAgentE2ETest`, which attach the shaded jar to a live JVM; in-process line coverage
  cannot observe that path meaningfully.
- `weaver-girl-testkit` — test-support harness shipped for plugin authors; every consumer
  test drives it.
- the 126 instrumentation plugins — thin declarative adapters (matchers plus a handler that
  delegates to the API). Their own unit tests still run, behavior-level paths are covered by
  `weaver-girl-plugins-tests` and the packaged-agent smoke, and a per-jar ratio would mostly
  measure defensive config/reflection branches.

This is a deliberate, documented scope, not a lowered threshold: the floor stays at
80/70 where the engine lives.

## Benchmark guard (rerun on a quiet machine)

`scripts/verify-benchmarks.sh` on 2026-11-11, JDK 17, `wi=2 i=3 forks=0`:

```
Checked: 16   Failures: 0   (budget: 1.5x of baseline)
RESULT: within budget
```

Worst ratio was `CoreComponentBenchmark.circuitBreaker_recordSuccess` at 1.408x
(34.4 ns vs 24.4 ns baseline); every other benchmark was within 1.17x (two earlier
quiet runs put the worst ratio at 1.49x and 1.21x — run-to-run noise on a shared
desktop, but all well under the 1.5x budget). The guard now passes `-am`, so it
compiles upstream modules from the working tree instead of whatever snapshot is in
`~/.m2`; the JMH profile was moved from the parent pom into `weaver-girl-core` because
`-am` otherwise tried to run the core-only runner class in the aggregator module.
An earlier
run on a machine that was also compiling showed a false 1.645x for that benchmark
and a 4.5 µs reading for `sampling_shouldSample_rate10`; the latter was real — the
benchmark called `setSamplingRate` (which logs) inside the measured operation. It now
sets the rate in `@Setup(Level.Iteration)`, so the benchmark measures `shouldSample()`
itself (1.85 ns). The guard's job is catching regressions, not tracking that
methodology fix, so the baseline in `docs/benchmarks/baseline-v1.md` is left as the
historical reference.

## Cross-JDK packaged-agent smoke

`SKIP_BUILD=1 scripts/cross-jdk-smoke.sh` on the shaded agent produced by the release
build, attaching to the sample app under four JDKs:

```
=== JDK openjdk version "1.8.0_492" ===          PASS
=== JDK openjdk version "11.0.33" ===            PASS
=== JDK openjdk version "17.0.21.0.3" ===        PASS
=== JDK openjdk version "21.0.13.0.3" ===        PASS
CROSS-JDK SMOKE PASSED on 4 JDK(s)
```

Each pass asserts `/health`, `/ready`, `/stats`, `/metrics`, ByteBuddy resolving in
`AgentClassLoader` (isolation), a positive servlet-interception counter, populated
`topHooks` p95 data, and circuit-breaker state — i.e. the real `-javaagent` path, not
just process startup.

## What this pass fixed

1. **Distribution completeness (the release blocker).** The agent jar contains no built-in
   plugin classes — it discovers them from a sibling `plugins/` directory. Releases used to
   publish only the agent jar, which would have instrumented nothing. Added
   `weaver-girl-agent/src/assembly/dist.xml`, wired it into the agent build, and made
   `release-check.sh` and `release.yml` reject a release whose archive lacks the servlet or
   jdbc plugin.
2. **Release workflow correctness.** The workflow now verifies `Premain-Class`/`Agent-Class`
   and the dist archive, writes SHA-256 files with bare file names (so `sha256sum -c` works
   on the downloaded asset), and the install snippet downloads the archive instead of
   renaming it.
3. **Container/Helm deployment.** `Dockerfile` copies `plugins/` next to the agent and takes
   the version as a build arg; the Helm README init-container example copies `plugins/` too.
4. **Docs truthfulness.** README/CHANGELOG/RELEASE_PROCEDURE now state 126 instrumentation
   plugins + 14 runtime jars, the dist-archive requirement, and that `quality-gate` stays on
   during deploy.
5. **Checkstyle.** Fixed all redundant/unused imports across `annotation-runtime`, `api`,
   and `core`.
6. **SpotBugs.** Fixed two real concurrency defects — `LoggingExporter.exportCount` and
   `DefaultInterceptorRegistry.generation` were `volatile long` incremented with `++`; both
   are now `AtomicLong`. The 22 remaining High findings were ByteBuddy advice parameter
   write-backs (`@Advice.Argument(readOnly = false)` / `@Advice.Return(readOnly = false)`),
   which SpotBugs cannot see as used; they are excluded in `spotbugs-exclude.xml` with the
   reason recorded there.
7. **Coverage.** Added tests for the Hikari plugin (previously none), the update-check
   capability, taint, diagnostics (top-hooks/breakers rendering and escaping), OTLP (live
   POST, dead endpoint, escaping), and the Micrometer/AOP-Alliance bridges.

## Reproducible build

The release gate requires JDK 17 (`scripts/release-check.sh` checks it) while the compiler
targets Java 1.8 bytecode. Use `./mvnw` so the build does not depend on a system Maven.
