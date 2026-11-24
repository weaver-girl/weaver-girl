# Changelog

All notable changes to the Weaver-Girl project, organized by development phase.

---

## Unreleased — CI green again: the test-state leaks behind the red suite (2026-11-24)

`main` had been red since 2026-11-22, and the failure set changed on every run: fourteen weaving
tests, then twenty-six, then thirty-seven, then three — all "before() should have been called:
expected true but was false" on JDK 17 and 21, all passing when the class ran alone. The engine
was fine; the module's tests were sharing process-wide state and surefire's ordering is not
stable, so each leaking test poisoned whatever ran after it.

Four leaks, each of them enough on its own:

- **A config test left emergency mode on.** `WeaverGirlBootstrapTest` and `CoreConfigWiringTest`
  apply a real config map through `WeaverGirl.applyCoreConfig`, including
  `weavergirl.emergency.disable=true`. That is process-wide: emergency mode switches the
  interception kill-switch off and caps the sampling rate.
- **The same path left the per-call switches off.** A config with `returnSnapshot=false` /
  `telemetry=false` left them in effect for the whole fork, so a later hook that reads the
  called method's return value got `null` (`InterceptAdviceTest`'s durable-registry snapshot test).
- **A bootstrap test never shut its agent down.** `dynamicConfigChangesReachTheSamplingController`
  was the one bootstrap test in its class without a `shutdown()`, so its `SamplingMonitor` thread
  outlived the test and kept raising the shared sampling rate from `AgentStatus` counters other
  tests left behind — which is what sampled `SignatureMatchingIntegrationTest`'s `before()` callbacks away.
- **Breaker state and threshold leaked too.** `HookTelemetryTest` retunes the global breaker to
  threshold 2 and then 1 without restoring it, and breaker states are keyed by hook name, so a
  tripped hook disabled anything reusing its name.

**The fix is ambient rather than per-class.** `ProcessWideStateExtension` runs
`ProcessWideState.restore()` before and after every core test, auto-detected through
`META-INF/services` with `junit.jupiter.extensions.autodetection.enabled=true` in the module's
`junit-platform.properties`. It resets emergency mode, the kill-switch, the five per-call
switches, the breaker (state and threshold), the sampling rate, and stops any leftover
`SamplingMonitor` (`SamplingMonitor.stopAllForTest()`). Patching one leaking test at a time was
tried first and did not hold — the leak simply moved to the next test in the next run.

**Verified on CI, not only locally:** `ci.yml` at `8f68bfbc` and `88fed789` are green on JDK 17,
JDK 21 and the quality gate, and `agent-smoke.yml` is green on all six jobs (JDK 8/11/17/21 plus
Spring Boot 3.3 and the Jetty/H2 sample). A local run on a different order cannot reproduce this
class of failure, which is why the CI result is the one that counts.

## Unreleased — per-call overhead and the ASM question (2026-11-24)

Measured the cost of a woven call end to end, found that it was observation work rather than
dispatch, and cut the biggest terms.

- **Method advice allocates instead of pooling.** `MethodInvocationPool.acquire` + `release` measured
  39 ns on the reference machine; a fresh `MethodInvocation` measured 7 ns. The pool spent two
  ThreadLocal operations and two full-field resets to save a TLAB allocation. `InterceptAdvice`
  now allocates one invocation per call. The pool remains for the constructor/static-init/field/catch
  advices.
- **Latent pertarget leak fixed.** `MethodInvocation.reset()` and `clear()` did not null
  `perInstance`, so a pooled invocation could hand the previous target's perthis/pertarget state to
  the next call on the same thread (and pinned that target in an idle thread pool). Both now clear
  it; a regression test covers it.
- **Exit reuses the entry's dispatch view.** Enter stamps its registry generation onto the carrier
  object; exit re-dispatches only when the generation changed during the call. Removes a hash lookup
  per successful invocation.
- **No-op exits.** Kill-switch-off and null-registry now return the `NOTHING` marker instead of
  `null`, so the exit advice returns immediately instead of taking the defensive path that acquired
  and released an invocation for nothing. A registry whose every match is an `ARGUMENT_REWRITE`
  hook does the same.
- **Allocation-free MDC bridge.** The common case (no trace context on the thread) returns a shared
  empty snapshot instead of building one. When a bridge key does change, only the changed keys are
  written and only those are restored, so the tracing case costs one or two `MDC.put`s instead of
  three put-or-remove pairs each way.
- **Coarse clock for plugin "last invocation" timestamps.** `PluginPerformanceTracker` read
  `System.currentTimeMillis()` twice per callback (~20 ns each) for fields a health check reads every
  30 s. `CoarseClock` refreshes a `volatile` timestamp every 10 ms on a daemon thread; readers pay
  one volatile read.
- **`get` before `computeIfAbsent`** in the per-hook metrics map, so the steady state does not call
  the lambda.
- **All costly per-call work is now a configurable switch.** `PerformanceSwitches` (in the API
  module, so both the engine and `ReturnVersion` read it) holds five switches, all defaulting on:
  `telemetry`, `returnSnapshot`, `mdc`, `pluginMetrics`, `circuitBreaker`. Each is settable by
  system property (`-Dweavergirl.<name>.enabled=false`), by agent argument / YAML key of the same
  name, or at runtime through the dynamic-config listener — the system property wins. The hot path
  pays one `volatile` read per switch. All six advices (method, constructor, static-init, field,
  catch, argument-rewrite) now record through `HookTelemetry.record`, so switching telemetry off
  stops the cost everywhere rather than only on the method path.
- **Measured cost per switch** (one matching hook, medians of repeated runs): all on ~335 ns/call;
  telemetry off ~197; mdc off ~291; returnSnapshot off ~307; pluginMetrics off ~344 (noise);
  telemetry+returnSnapshot+mdc+pluginMetrics off **~88 ns/call (-74%)**. Interception is
  unaffected in every case; with the breaker on, throwing hooks still trip it (failure paths report
  an unknown duration, `-1`) — turning the breaker off means failing hooks are never skipped.
- **Switches are real, not advisory.** The MDC switch is checked inside `MdcInjector.inject()` too,
  not only in `isMdcAvailable()`, so direct callers of the public API cannot bridge while it is
  off; `InterceptorHolder.shouldInvoke` returns true unconditionally when the breaker is off.
  Behaviour tests pin each switch (`MdcSwitchTest`, `PluginMetricsSwitchTest`,
  `HookTelemetryTest`, `CoreConfigWiringTest`, `PerformanceSwitchesTest`).
- **Tests.** `HookPathBenchmark` (per-callback attribution), `InstrumentationOverheadBenchmark`
  (end-to-end woven calls), `HookTelemetryTest` (switch removes timing without changing breaker
  behaviour), `CoarseClockTest`, and a pooled-invocation pertarget regression test.
- **Regression guard** now covers `DispatchBenchmark.preResolvedView` and every end-to-end
  scenario of `InstrumentationOverheadBenchmark` (30 checks, all within budget).
- **Measured effect** (one hook, in-process, shared host so ±20-30%): 625 ns before this pass →
  290-450 ns default → 140-200 ns with telemetry off. 5,000 registered pointcuts: 601 → 297-319 →
  101-152 ns. Dispatch itself is 6.5-7.7 ns of that total.

### The ASM-generated-dispatch question

Would replacing the runtime dispatcher with ASM-generated code help? The measurements say no by
more than a rounding error. `preResolvedView` (a static field read — the floor for any
weave-time-resolved dispatch) is 2.2 ns; today's `dispatch_hotPath` is 6.5–7.6 ns. The entire
saving available is ~5 ns per call: 1-2% of the default one-hook cost and 2.5-3.5% of the
telemetry-off cost. The remaining cost is per-callback observation (timing, metrics, snapshot),
which an ASM rewrite of the dispatcher would not change. See
`docs/benchmarks/baseline-v1.md` for the full tables.

## Unreleased — dispatch scaling (2026-11-23)

Instrumentation cost no longer grows with the number of registered pointcuts.

- **Per-class/per-method dispatch cache.** `InterceptorHolder` now answers the inlined advice from
  a cache keyed by registry generation, class name and method name (`DispatchCache` → `ClassPlan` →
  `DispatchView`). Every intercepted call used to rescan the registry — including reflective
  `Class.forName` matching for interface/superclass/annotation matchers — three times per call
  (enter, exit, plus the call-site flag check). A call now costs two hash lookups and iterates only
  the definitions that can apply to that method. Exact names, name patterns, signatures, constructor
  and accessor matchers are pre-filtered by method name; composed pointcuts are deliberately not
  filtered because their combined method matcher is only an approximation.
- **Measured effect** (JMH, in-process): `dispatch_hotPath` is ~8 ns/op at 100, 1,000 and 5,000
  registered pointcuts — flat. The previous per-call registry lookup is 10.9 µs / 15.1 µs /
  33.9 µs at the same sizes. See `docs/benchmarks/baseline-v1.md`.
- **Linear bulk registration.** `@DeclarePrecedence` resolution is cached per aspect class and the
  precedence map is rebuilt lazily on first lookup instead of once per registration, removing the
  quadratic `Class.forName` cost of loading thousands of pointcuts. Dispatch order is unchanged
  (priority, then `@DeclarePrecedence`).
- **Cheaper field/catch dispatch.** `FieldAdvice` and `CatchAdvice` no longer scan the whole registry
  per event; the definitions whose interceptor is a `FieldInterceptor` / `CatchInterceptor` are
  selected once per registry generation.
- **cflow costs nothing when unused.** The thread-local cflow stack is only maintained when a cflow
  expression has ever been constructed, frames are stored as two parallel deques instead of
  concatenated strings, and a pop only happens when the exiting frame is the top one. `resetCflow()`
  clears the stack for tests.
- **Transform-time precomputation.** The weaver builds one union method matcher and one `Advice`
  instance per weave group instead of per definition, and the group key now includes the advice
  class (constructor/static-init/field/catch definitions no longer share a group with method
  advice).
- **Guard rails.** `DispatchScaleTest` asserts that repeated dispatches never rescan the registry or
  resolve classes reflectively, that per-method filtering is exact, that a registration invalidates
  the cache, and that bulk registration stays linear; `DispatchBenchmark` is part of the `bench`
  profile.

## Unreleased — taint / IAST hardening (2026-11-23)

- **`Taint.ranges()` no longer throws `IllegalAccessError`** when the taint plugin appends the
  bridge to the bootstrap loader. `TaintBridge.Range` and `lookup` are public so the
  application-loader `Taint` class can read them across the loader boundary.
- **Sanitiser primitive.** `Taint.untaint(value)` / `Taint.untaint(value, source)` remove marks in
  every scope of the current thread, so a product's encoder / whitelist interceptor can clear a
  propagated mark.
- **Prepared statements reach the SQL sink.** The JDBC plugin observes SQL text at
  `prepareStatement` / `prepareCall` time. `PreparedStatement.executeQuery()` has no SQL argument,
  so the execute-only hook was blind to the most common JDBC pattern.
- **Java 9+ concatenation keeps taint.** `a + b` compiles to
  `StringConcatFactory.makeConcatWithConstants` and never touched `StringBuilder`, so the dominant
  string-building idiom lost the mark. `TaintConcatTransformer` rewrites each concat call site
  (arguments spilled to fresh locals, original indy preserved, template resolved at transform time)
  and calls bootstrap-loaded `TaintConcat.propagate`, keeping exact argument offsets. Idempotent,
  skips bootstrap/JDK/agent classes, and leaves a class untouched if the rewrite fails.
- **Wider propagation.** `String.trim`, `strip*`, case conversion, `replace`, `replaceAll`,
  `replaceFirst` and `String.join` now propagate the whole result; `StringBuilder.delete`,
  `deleteCharAt`, `insert`, `replace`, `setLength`, `reverse` and `append(CharSequence, int, int)`
  move ranges with the text instead of leaving stale slices that caused false positives.
- **Generic engine APIs.** `Taint.propagate(source, target)` copies labels for flows the string
  advice cannot see; `Taint.sink(kind, values...)` reports a finding for any sink, flattens arrays /
  iterables / map values, and returns whether a value was tainted. Non-string values are rendered
  into the finding, and findings now carry `callerClass` / `callerMethod` / `callerLine` for the
  application frame that reached the sink.
- **Broader HTTP sources.** `HttpSourceCapture` also tags cookies (`http.cookie:name`) and the URL
  envelope (`http.uri`, `http.path`, `http.query`), not just parameters and headers.
- **Thread hand-off carries the scope.** The agent now instruments `ThreadPoolExecutor.execute`,
  `ForkJoinPool.execute` and the `ForkJoinPool.submit` overloads, wrapping each submitted
  `Runnable`/`Callable` with a submission-time snapshot. The worker sees the submitting request's
  tags and its previous scopes are restored afterwards, so a pooled thread never keeps a stale
  request. `CompletableFuture` stages inherit through the same hook. `Taint.wrap`,
  `Taint.captureContext` / `restoreContext` and `Taint.hasScope` cover boundaries the agent does
  not wrap (`new Thread`, third-party callbacks).
- **Sanitiser framework.** `Taint.untaintAll(Object...)` cleans a parameter group and reports how
  many values carried marks; `TaintSanitizerInterceptor.returnValue()` / `.argument(int)` /
  `.returnValueAndArgument(int)` turn a sanitiser into a drop-in `Interceptor`.
- **Array and rendering parity.** `toCharArray`, `getChars`, `getBytes`, `new String(char[])`,
  `new String(char[],int,int)`, `new String(byte[])`, `append(Object)`, `append(char[])`,
  `append(char[],int,int)`, `insert(int,Object)`, `insert(int,char[])`, `insert(int,char[],int,int)`,
  `setCharAt`, `String.valueOf` and `new StringBuilder/StringBuffer(CharSequence)` all propagate.
  `getChars` and `insert(int,char[],int,int)` map only the window they touch, keeping the rest of
  the destination untouched. Every builder advice takes `CharSequence`, so `StringBuffer` is
  covered alongside `StringBuilder`.
- **One-shot scheduled tasks carry context.** `ScheduledThreadPoolExecutor` overrides `execute`
  and queues the task itself, so its `schedule(Runnable/Callable, long, TimeUnit)` overloads are
  wrapped at submission. Periodic scheduling is deliberately left alone so a request scope cannot
  be pinned for the lifetime of the process.
- **Re-entrancy guard never strands.** Every advice that takes the guard now releases it on all
  exit paths — null results, unmatched arguments and, new in this pass, the throwable path
  (`onThrowable` + `@Advice.Thrown`). An exception from `substring`, `append` or `getBytes` no
  longer silences propagation for the rest of the request. Constructor advice probes and releases
  on entry because ByteBuddy cannot attach throwable exit advice to a constructor.
- **Capability audit.** `docs/taint-iast-capability-audit.md` records the verified propagation
  matrix, source/sink coverage, async carry, cost, and the remaining IAST gaps (rule layer,
  source/sink breadth, raw-thread hand-off).

## Unreleased — seeded issues and ecosystem fixes (2026-11-20)

Work driven by the open issues and by running the agent against a real Spring Boot application.

- **Log-to-trace correlation (issue #45).** The `logging` plugin now writes `traceId`/`spanId` (plus
  any `mdcKeys`/`baggageKeys`) into the SLF4J MDC **and** Log4j2 `ThreadContext` for the duration of
  every intercepted call, and restores the previous values on exit. Backends are resolved
  reflectively, so neither is a dependency and the plugin is inert when both are absent.
  `scripts/run-log-correlation-demo.sh` shows the same log line with and without the agent.
- **Grafana dashboard pack (issue #44).** `deploy/grafana/` gains an operations dashboard
  (request rate, slow-call and error ratios, p95/p99 interception latency, plugin health, lifecycle
  state, circuit-breaker state) plus a README, a loader fix and a datasource variable on all three
  dashboards. `weaver-girl-jvm.json` queried Micrometer-style names the exporter never emits; it now
  uses the real `weavergirl_jvm_*` series.
- **Circuit-breaker metrics.** The Prometheus exporter now emits
  `weavergirl_circuit_breaker_{open,consecutive_failures,consecutive_slow,trips_total}`, fed from the
  engine's breaker through a new API-level `CircuitBreakerRegistry`, so an OPEN breaker — a hook that
  has stopped running — is visible instead of silent.
- **Compatibility matrix (issue #43).** `docs/compatibility-matrix.md` records, per plugin and per
  JDK, what CI actually proves, and marks everything else best-effort rather than implying support.
- **Spring Boot examples project (issue #46).** `weaver-girl-examples/spring-boot-jdbc/` is a
  standalone Spring Boot 3.3 + H2 application depending only on released artifacts, with a script
  that downloads the agent distribution, attaches it and proves interception.

Running that example surfaced four defects, all fixed here:

- **Plugin activation in a Spring Boot fat jar.** `PluginContext.classPresent` only asked the class
  loaders, which cannot see `BOOT-INF/classes/` or `BOOT-INF/lib/*.jar` before Spring Boot's loader
  exists. Servlet, Spring MVC and Tomcat plugins therefore deactivated themselves in the most common
  deployment shape. `ApplicationClasspath` now indexes the launch class path, including nested jars.
- **`servlet` declared only the `javax` namespace**, so every Spring Boot 3 (`jakarta`) application
  deactivated it; it now declares both.
- **Multi-release entries of relocated packages kept their original paths.** The agent jar is
  appended to the bootstrap search path, so a host asking for `org.yaml.snakeyaml.internal.Logger`
  was handed the shaded-bytecode entry and the JVM rejected it
  (`NoClassDefFoundError: ... (wrong name: shaded/...)`), breaking Spring Boot's YAML path.
  `scripts/relocate-multi-release.py` rewrites those entry paths at build time.
- **Micrometer and aopalliance were bundled unrelocated.** `-javaagent` puts the agent jar on the
  application class path, so Spring Boot's actuator resolved `io.micrometer...LogbackMetrics` to the
  agent's copy and died on `NoClassDefFoundError: ch/qos/logback/classic/spi/LoggerContextListener`.
  Both are now excluded from the shaded jar.

Dependency versions were also brought up to the point releases Dependabot proposed (surefire 3.6.1,
antrun 3.2.0, mockito 5.24.0, spotbugs 4.10.5.0, central-publishing 0.12.0, exec-maven 3.6.4,
shade 3.6.2, logback 1.6.6, Maven wrapper 3.10.0).

## Unreleased — release-readiness fixes (2026-11-19)

Findings from the capability assessment in
[`docs/release-assessment-2026-11-19.md`](docs/release-assessment-2026-11-19.md), and the fixes
landed with them:

- **Interception overhead.** Call-site capture (`getCallerClass()` and friends) is now opt-in per
  definition (`InterceptorDefinition.withCallSiteTracking`, `AbstractPlugin`'s
  `trackCallSite(boolean)`); it used to run a stack walk on every intercepted invocation.
  `MdcInjector.isMdcAvailable()` caches its probe. Measured end-to-end on JDK 21: 2.1 µs → 0.67 µs
  per intercepted call with one empty `before` hook.
- **Runtime registration.** `WeaverTransformer` rebuilds its matcher chain when the registry
  generation changes, so a definition registered after startup reaches already-loaded classes
  (`WeaverGirl.reloadAndRetransform()`; config-watch, plugin-directory reload and dynamic refresh
  all use it). Regression test: `PluginTestHarnessLateDefinitionTest`.
- **Instrumentation escape hatch.** `PluginContext.getInstrumentation()` and
  `registerTransformer(ClassFileTransformer)` give a product access to bytecode the declarative
  registry cannot reach; the agent guards agent-owned classes and withdraws the transformer with
  the plugin (`InstrumentationHandle`, `InstrumentationHandleImpl`).
- **Nested spans.** `Tracer.endSpan` restores the parent span instead of clearing the thread's
  span, so spans opened by different plugins nest correctly.
- **Endpoint defaults.** Metrics/health/diagnostics/management endpoints bind `127.0.0.1` by
  default (`endpointBindAddress` opts out), use a bounded daemon executor, and the management API
  can require `endpointAuthToken`.
- **Plugin contract.** `plugin.minimumAgentVersion` is enforced even when `plugin.apiVersion` is
  absent; `@since` tags were aligned to `1.0.0`.
- **Release chain.** Central publishing auto-publishes after validation; the workflow rejects
  SNAPSHOT/mis-tagged versions, signs both the distribution archive and the bare agent jar, marks
  propagations as pre-releases; `weaver-girl-sample` and the cross-plugin tests no longer deploy.
- **Delivery artifacts.** Grafana dashboards use the metric names the exporter actually emits;
  the Docker image enables the endpoints, runs non-root and has a health check; the Helm chart
  renders `config.*` into an agent-argument string that the init container consumes.
- **Config that was documented but inert.** `circuitBreakerThreshold` /
  `circuitBreakerCooldownMs` now retune the live breaker (including at runtime), the
  adaptive-sampling monitor is started (and `adaptiveSampling=false` turns it off), and
  `stateFile` enables periodic state snapshots with a final one on shutdown
  (`CoreConfigWiringTest`).
- **Plugin unload.** `PluginClassLoader.close()` plus the four strong references around it are
  cleared, so a hot-swapped plugin's loader, classes and jar handle become collectable.
- **Docs.** README snippets and `run-sample.sh` are runnable again; `method: "*"` and the four
  dead agent-level YAML keys are documented correctly; the plugin guide covers the escape hatch
  and call-site tracking.

---

## 1.0.0 — First release (2026-11-11)

The first release-ready cut of Weaver-Girl. Highlights:

- **126 built-in instrumentation plugins** (Servlet, Spring, JDBC, Redis, Kafka, gRPC, MongoDB,
  HttpClient, HikariCP, OkHttp, RabbitMQ, Elasticsearch, Dubbo, MyBatis, RocketMQ, Druid, Feign,
  Caffeine, Netty, Tomcat/Jetty/Undertow, Quartz/XXL-Job, and more), one jar per capability, plus
  **14 runtime capability jars** (Prometheus, health, OTLP, JSON events, JMX, config watch,
  diagnostics, update check, taint, …). Only enabled jars are loaded.
- **Distribution archive** (`weaver-girl-agent-<version>-dist.zip`) now ships the agent jar and its
  `plugins/` directory together. The agent has no built-in plugin classes, so a jar-only download
  would instrument nothing; `scripts/release-check.sh` and `release.yml` both verify the archive
  contains the agent and the servlet/jdbc plugins before a release is published.
- **Release gate is green**: `clean verify -Pquality-gate` passes on JDK 17 for all 153 modules —
  unit tests, SpotBugs (High), and Checkstyle everywhere, plus JaCoCo instruction ≥ 80% / branch
  ≥ 70% on the engine and runtime modules. The sample demo, the launcher-only agent module and
  the thin instrumentation-plugin adapters are exempt from the coverage ratio: the agent is
  attached for real by the cross-JDK smoke, and plugin behavior is covered by the cross-plugin
  integration tests instead of per-jar line ratios.
- **Java 8 compatibility** verified by the packaged-bytecode scanner and the bare-attach smoke on
  JDK 8/11/17/21; agent ByteBuddy resolves in the isolated child-first `AgentClassLoader`.
- **Observability**: W3C trace context propagation, OTLP span/log export, Prometheus metrics,
  health/readiness endpoints, JMX, topology, alerting, metric governance, and tracer
  self-telemetry.
- **Production safety**: global emergency kill-switch, circuit breaker with slow-call tripping,
  adaptive sampling, graceful degradation, plugin health, and dynamic config with rollback/audit.
- **Project layout**: the website moved to `weaver-girl/weaver-girl.github.io` (served from the
  organization root), third-party plugins start from the
  `weaver-girl/weaver-girl-plugin-template` template repository, and release metadata now points
  at the `weaver-girl` organization. Code of Conduct, security policy, issue forms, a PR
  template, a public roadmap and a versioning/compatibility policy are in place.
  Published coordinates use `io.github.cc11001100`, the namespace Maven Central provisions
  automatically for the maintainer's GitHub account; Java packages keep the historical
  `com.github.cc11001100.weavergirl` prefix.

See `RELEASE_READINESS.md` for the verified gate evidence and `RELEASE_PROCEDURE.md` for the
publishing steps.

---

## P80 — Agent Self-Update Mechanism (2026-09-06)

### P80: Version check + staged update, wired into bootstrap

**New capability**: the agent can now poll a remote JSON version endpoint and
alert when a newer release exists, and optionally stage the new artifact
(download + SHA-256 verify) for an operator-driven swap + restart:
- `VersionInfo` (release package, `@since 1.10.0`): remote descriptor
  (`version` / `downloadUrl` / `sha256` / `mandatory` / `releaseNotes`) with a
  dependency-free JSON parser and semver comparison (release outranks
  pre-release)
- `UpdateChecker` (`@since 1.10.0`): daemon-scheduler polling, listener
  notification on newer versions, check/failed counters; failures never throw
- `AgentUpdater` (`@since 1.10.0`): IDLE → DOWNLOADING → VERIFYING → READY
  staging state machine; checksum mismatch deletes the artifact and fails;
  check-only descriptors (no downloadUrl) report manual-upgrade guidance
- `WeaverGirl.initUpdateCheck/stopUpdateCheck`: opt-in via
  `updateCheckEndpoint` (zero overhead when unset); `updateCheckIntervalMs`
  (default 24h), `updateAutoStage`, `updateStagingDir`, `updateHeaders`;
  `ConfigValidator` recognizes the 5 new keys

**Design note**: a loaded agent JAR cannot replace itself (JVM memory-maps the
file), so fully automatic in-place upgrade is impossible without an external
supervisor — staging + restart instructions is the safe subset.

**New tests**: `VersionInfoTest` (12) + `UpdateCheckerTest` (14: live
HttpServer endpoint, periodic schedule, failure counting, staging
download/verify/mismatch/check-only) + `WeaverGirlUpdateCheckTest` (7:
bootstrap wiring end-to-end) + 2 `ConfigValidatorTest` cases.

---

## P79 — SpanExporter Bootstrap Wiring (2026-09-06)

### P79: Span batch export auto-wires from agent config

**Previous gap**: `SpanExporter` / `OtlpHttpExporter` / `TracerSpanExporterBridge`
existed with passing unit tests, but no production code ever created them —
configuring an OTLP endpoint had zero effect because the agent startup path never
assembled the export pipeline.

**Change** (opt-in, zero overhead when disabled):
- `WeaverGirl.initSpanExport(config)`: `otlpEndpoint=http://collector:4318/v1/traces`
  builds an `OtlpHttpExporter` (with optional `otlpHeaders` `K=V;K2=V2`); bare
  `spanExport=true` enables log-only export. Tuning keys: `spanExportBatchSize`
  (100), `spanExportIntervalMs` (5000), `spanExportBufferSize` (10000)
- Bridge registers via `Tracer.addCompletionListener` (additive — coexists with the
  topology bridge instead of replacing it); `shutdown()` detaches + stops
- `ConfigValidator` recognizes the 6 new keys; `getSpanExporter()` accessor for
  tests/diagnostics

**New tests**: `WeaverGirlSpanExportTest` (8 tests: disabled-by-default,
log-only running, end-to-end span delivery via flush, OTLP construction with
headers, tuning defaults/fallbacks, stop detaches) + 2 `ConfigValidatorTest`
cases for the new keys.

---

## P0-12 — ARGUMENT_REWRITE Supports Any Argument Index (2026-09-06)

### P0-12: AsyncArgumentAdvice binds the first 8 parameter slots writably

**Previous limit**: `AsyncArgumentAdvice` bound only `@Advice.Argument(0)`, so
`setArgument(i, ...)` for i &gt; 0 silently never reached the method body — any
hook needing to rewrite a non-first argument (e.g. an Executor passed as the
second parameter of a factory method) was impossible.

**Change**: the advice now binds the first 8 parameter slots with
`@Advice.Argument(i, readOnly=false, optional=true)` plus `@Advice.AllArguments`
for dispatch. Indices beyond the method's actual arity bind as absent optionals
(writes silently ignored — verified by spike, then removed); the invocation
carries the full argument list and each rewritten slot is assigned back before
the method body executes. `InterceptorDefinition.AdviceMode` javadoc updated.

**New tests**: `ArgumentRewriteIntegrationTest` (end-to-end writeback for
indexes 0/1/2 through real ByteBuddy weaving) + 2 new cases in
`AsyncArgumentAdviceTest` (full argument visibility, non-first rewrite dispatch);
existing async executor integration tests (8 tests) pass unchanged.

---

## P0-9 — CompletableFuture Context Propagation (2026-09-06)

### P0-9: Context-propagating CompletableFuture factories + capability placeholder plugin

**Why API, not weaving**: `java.util.concurrent.CompletableFuture` lives on the bootstrap
classloader, where `ARGUMENT_REWRITE` advice is skipped (`WeaverTransformer.install`) and inlined
advice cannot resolve the agent's own classes; the no-executor overloads use
`ForkJoinPool.commonPool()` internally (also bootstrap). Transparent weaving of the
`supplyAsync`/`runAsync` factory methods is therefore not possible — propagation is provided
by explicit API factories instead:

- `ContextCompletableFuture` (weaver-girl-api, `@since 1.9.0`): static factories
  `supplyAsync(Supplier)` / `supplyAsync(Supplier, Executor)` /
  `runAsync(Runnable)` / `runAsync(Runnable, Executor)` that wrap the target executor
  with `ContextExecutor` at submission time. Null executor falls back to the common
  ForkJoinPool; already-wrapped executors are reused (idempotent)
- `CompletableFuturePropagationPlugin` (weaver-girl-plugins): capability placeholder —
  registers no interceptor definitions and logs a pointer to the API, so the feature is
  discoverable via ServiceLoader without pretending to weave. Factory-method hooks belong
  here if the transformer ever gains bootstrap `ARGUMENT_REWRITE` support

**New tests**: 11 tests in `ContextCompletableFutureTest` (explicit/null/commonPool executors,
chained stages, Tracer/Tenant end-to-end, no-leak) + 5 tests in
`CompletableFuturePropagationTest` (placeholder registration semantics + API propagation)

---

## P0-7 — ContextPropagator Priority & MDC/Tenant Bridges (2026-07-19)

### P0-7 Task 1: ContextPropagator priority support for ordered propagation
- `ContextPropagator.priority()`: lower value restores first, so ThreadContext (-200) and
  Tracer (-100) bridges land before dependents read them; cleanup unwinds in reverse

### P0-7 Task 2: TenantContextPropagator — cross-thread tenant propagation with ThreadContext bridge
- Propagates `TenantContext` across threads and bridges `tenantId` into ThreadContext
  during `restore()`, so downstream plugins read it without code changes

### P0-7 Task 3: MdcPropagator — SLF4J MDC cross-thread propagation via reflection
- Zero-compile-dependency MDC propagation (reflection over `org.slf4j.MDC`); mirrors
  bridged `traceId`/`spanId`/`tenantId` from ThreadContext into MDC on the worker thread,
  so log statements automatically include trace and tenant information

---

## P0-6 — ContextPropagator SPI & Span Bridge (2026-07-14)

### P0-6: End-to-end Tracer + ThreadContext propagation

**Core bug fixed**: Worker threads in async executors saw `Tracer.getCurrentSpan() == null` and `ThreadContext.get("spanId") == null` because `AsyncContextPropagationPlugin` only propagated ThreadContext, never the Tracer span; and nobody bridged spanId into ThreadContext.

**ContextPropagator SPI** — pluggable context-propagation lifecycle:
- `ContextPropagator` interface: `name()`, `capture()`, `restore(Snapshot)`, `cleanup(Snapshot previous)`
- `ContextPropagatorRegistry`: static registry with registration-order preservation (CopyOnWriteArrayList + ConcurrentHashMap)
- `ContextPropagators`: static facade (renamed from old `ContextPropagator` class) — `capture()`, `wrap(Runnable/Callable)`, `wrapCallables()`, `scope()`

**Built-in propagators**:
- `ThreadContextPropagator` — propagates `ThreadContext` map; cleanup restores worker's prior ThreadContext symmetrically
- `TracerPropagator` — propagates Tracer span; **bridges `traceId`/`spanId` into `ThreadContext`** during `restore()` so OkHttp/RabbitMQ/Elasticsearch/Logging plugins read them without code changes; cleanup explicitly restores or clears the worker's prior span (avoids `Tracer.restore(null)` no-op pitfall)

**Snapshot/Scope extended**:
- `ContextSnapshot` now holds entries from all registered propagators; `empty()` clears all propagatable state
- `ContextScope` performs symmetric cleanup in reverse registration order (LIFO unwind)
- `ContextExecutor`/`ContextExecutorService`/`ContextScheduledExecutorService` updated to delegate to `ContextPropagators`

**Deprecations**:
- `TraceRunnable`, `TraceCallable`, `TraceExecutorService` → use `ContextPropagators.wrap()` or `ContextExecutorService.wrap()`

**New tests**: 12 tests in `ContextPropagatorTest` covering span propagation, spanId bridge, cleanup semantics, registry, and combined multi-propagator propagation

---

## P0-5 — Global Context Bus (2026-07-03)

### P0-5: Process-wide GlobalContext
- `GlobalContext`: process-wide, all-threads-visible key/value blackboard — the missing fourth layer of the data-propagation model
  - Backed by `ConcurrentHashMap`; static-class API matching `ThreadContext`/`MetricRegistry`/`TopologyGraph`
  - Basic read/write: `put` / `get` / `get(key, default)` / `remove` / `clear` / `containsKey`
  - Atomic operations replacing ad-hoc `static AtomicLong` fields: `computeIfAbsent` / `putIfAbsent` / `replace` / `replace(CAS)`
  - Key-prefix namespace convention (`"<pluginName>.<key>"`, e.g. `"trace.counter"`)
  - Does NOT support null keys/values (CHM limitation, differs from `ThreadContext`)
  - No TTL/eviction — callers remove explicitly; javadoc documents try-finally idiom
  - Completes the four-layer propagation model:
    | Layer | Scope |
    |---|---|
    | `MethodInvocation.setAttachment` | single call (before→after) |
    | `ThreadContext` | single thread (ThreadLocal) |
    | **`GlobalContext`** | whole process, all threads |
    | `Tracer.inject/extract` | cross-process |
- Dogfood: migrated `TraceCorrelationPlugin`'s `static AtomicLong traceCounter` to `GlobalContext.computeIfAbsent("trace.counter", …)` — first consumer of the global bus
- 18 tests (`GlobalContextTest`) covering basic API, atomic semantics, and the defining cross-thread visibility property

---

## P78 — Adaptive Rate Limiter (2026-06-04)

### P78: Adaptive Rate Limiter
- `AdaptiveRateLimiter`: token bucket rate limiter with adaptive load-based adjustment
  - Token bucket algorithm: `tryAcquire()` for single/multi-permit acquisition
  - `tryAcquire(timeoutMs)`: blocking acquisition with timeout
  - Adaptive rate: `updateLoad(systemLoad)` reduces rate when load exceeds threshold (max 50% reduction)
  - Automatic restoration to base rate when load drops below threshold
  - Configurable burst capacity and load threshold (0.0-1.0)
  - Statistics: allowed/rejected/total counts, rejection rate, total wait time
  - Thread-safe: AtomicLong/AtomicInteger CAS operations
  - `setBaseRate()`: runtime rate adjustment
- 31 tests

---

## P77 — Metric Reporter (2026-06-04)

### P77: Periodic Metric Reporter
- `MetricReporter`: periodic metric collection and reporting
  - Named metric suppliers registered via `registerMetric(name, supplier)`
  - Pluggable formatters via `MetricFormatter` SPI
  - Built-in formatters: `JsonFormatter` (structured JSON to SLF4J), `LogFormatter` (human-readable lines)
  - Configurable collection interval and history retention size
  - Scheduled daemon thread execution with graceful start/stop
  - Bounded history (CopyOnWriteArrayList)
  - Exception-safe: supplier failures recorded as `error:` prefix
- `MetricSnapshot`: immutable timestamped snapshot of metric values
  - `toJson()` for endpoint rendering
  - Unmodifiable values map
- `MetricFormatter`: functional interface for pluggable output formats
- 30 tests

---

## P76 — Health Check Subsystem (2026-06-04)

### P76: Advanced Health Check Subsystem
- `HealthIndicator`: functional interface for component health checks
- `HealthStatus`: component health result with builder pattern
  - Status values: UP, DEGRADED, DOWN, UNKNOWN
  - Immutable detail map with arbitrary key-value pairs
  - `isUp()`/`isDown()` convenience methods
  - `toJson()` for HTTP endpoint rendering
- `HealthReport`: composite health report
  - Overall status: DOWN if any component DOWN, DEGRADED if any DEGRADED
  - Per-component health details
  - Check duration tracking
  - `upCount()`/`downCount()`/`componentCount()`
  - `toJson()` for full JSON output
- `CompositeHealthIndicator`: singleton health indicator registry
  - Dynamic register/unregister
  - `check()` runs all indicators, exception-safe (→ DOWN with error detail)
  - `checkComponent(name)` for single component
- 30 tests

---

## P75 — Release Preparation (2026-06-04)

### P75: Release Info & Integrity Verification
- `ReleaseInfo`: release metadata singleton loaded from `META-INF/weaver-girl-release.properties`
  - Version (semantic versioning), build timestamp, git commit hash, build number
  - SHA-256 checksum for agent JAR integrity verification
  - `verifyIntegrity()`: compare expected vs actual checksum
  - `getSummary()`: human-readable release summary
  - `toMap()`: structured output for APIs
  - `sha256()`: utility for computing SHA-256 hashes
  - Dev mode fallback when properties file absent (1.0.0-SNAPSHOT)
- 15 tests

---

## P74 — Documentation Update (2026-06-04)

### P74: Documentation Improvements
- Updated `ARCHITECTURE.md`:
  - Module structure: 12 → 16 built-in plugins
  - Module details table: added OkHttp, RabbitMQ, Elasticsearch plugins
  - New "Enterprise Features (P46-P73)" section with 12 subsections:
    Dynamic Config Center, Plugin Hot Management, Multi-Tenant Isolation,
    Distributed Tracing, Data Exporter SPI, Alerting Engine, Service Topology,
    Metric Aggregation, Agent REST API, Agent State Persistence,
    Plugin Compatibility Checker, Lock-Free Object Pool, Internationalization
  - Thread Safety Model: added 10 new components
- `README.md` already updated (16 plugins table + State Persistence feature)
- `CHANGELOG.md` complete records for P66-P73

---

## P73 — Internationalization (2026-06-04)

### P73: i18n Support
- `AgentMessages`: lightweight internationalization framework
  - ResourceBundle-based message externalization with UTF-8 encoding
  - Parameterized messages with {0}, {1}, {2}... positional arguments
  - Multi-locale caching (ConcurrentHashMap), auto-fallback to English
  - System property auto-detection: `weaver-girl.messages` or `user.language`
  - `parseLocale()`: locale string parser (zh_CN, en-US, ja_JP)
  - `formatMessage()`: simple placeholder formatter
  - `hasKey()`, `getKeys()`: key existence and enumeration
- Message files:
  - `META-INF/agent-messages.properties` (English — 50+ messages)
  - `META-INF/agent-messages_zh_CN.properties` (Chinese Simplified — complete translation)
- Coverage: Bootstrap, Plugin, Interceptor, Config, Diagnostics, Security, Export, Alert, Persistence, Compatibility, Errors
- 32 tests

---

## P71-P72 — Maturity & Performance (2026-06-04)

### P71: Plugin Version Compatibility Checker
- `PluginCompatibilityChecker`: runtime plugin compatibility validation
  - Agent version check: plugins declaring `minimumAgentVersion` are validated
  - Duplicate detection: same-name same-version or older-version plugins rejected
  - Newer version warning: same-name newer-version plugins supersede with warning
  - Dependency presence: all declared plugin `depends` verified against available plugins
  - Version format validation: semver-like format checked (X.Y.Z with optional -prerelease +build)
  - Auto-disable: incompatible plugins automatically disabled (configurable)
  - `CompatibilityReport`: structured result with warnings, errors, autoDisabled flag
  - `compareVersions()`: semver comparison utility (handles SNAPSHOT, pre-release suffixes)
  - Report collection: getReports(), getIncompatibleReports(), getCheckedCount(), getCompatibleCount()
- 40 tests

### P72: Lock-Free Object Pool
- `LockFreeObjectPool<T>`: high-performance lock-free object pool for hot-path allocation reduction
  - `ConcurrentLinkedQueue`-based: no locks on borrow/release
  - Bounded pool size: prevents memory leaks by evicting excess objects
  - Statistics: borrowCount, returnCount, hitCount, missCount, evictionCount, hitRate
  - `PoolStats`: immutable statistics snapshot
  - Thread-safe: verified with 20-thread × 500-operation stress test
  - Capacity enforcement: pool stays within maxSize under contention
- 19 tests

---

## P66-P70 — Breadth Expansion (2026-06-04)

### P66: OkHttp Plugin
- `OkHttpPlugin`: dedicated OkHttp instrumentation beyond generic HttpClientPlugin
- Call lifecycle tracking: `execute()` and `enqueue()` with timing
- Connection pool metrics: idle/total connection count monitoring
- Request/Response detail extraction: URL, method, status code, body size, protocol
- Trace propagation via OkHttp Request builder (X-Trace-Id, X-Span-Id)
- Compatible with OkHttp 3.x and 4.x (RealCall path variants)
- 28 tests

### P67: RabbitMQ Plugin
- `RabbitMQPlugin`: AMQP producer/consumer instrumentation
- Producer: `basicPublish` timing, exchange/routingKey extraction, message body size
- Consumer: `basicAck`/`basicNack`/`basicReject` deliveryTag tracking
- Connection: `newConnection` timing with host/port extraction
- AMQP message header trace propagation (X-Trace-Id, X-Span-Id)
- 28 tests

### P68: End-to-End Integration Tests
- `FullAgentLifecycleTest`: 10-phase comprehensive lifecycle test
  - Bootstrap & Registration, Interception & Events
  - Circuit Breaker (trip, cooldown, reset)
  - Sampling Controller (rate verification)
  - Dynamic Config (listener, snapshot, rollback)
  - State Persistence (snapshot/restore roundtrip)
  - Multi-Plugin Coordination (shared registry)
  - Concurrent Stress (registration, lookup thread safety)
  - Error Recovery (isolated plugin failures)
  - Graceful Shutdown (state capture)
  - Unregistration & Registry Lookup
- 16 tests

### P69: Agent State Persistence
- `AgentStateSnapshot`: Properties-format state persistence
  - Interceptor/class/invocation/error counts
  - Agent uptime, sampling rate, circuit breaker threshold
  - Per-plugin state, configuration snapshot, custom metrics, health status
  - Chained builder API, thread-safe (ConcurrentHashMap)
  - Save/load/delete/exists file operations
- `AgentStatePersister`: periodic snapshot scheduler
  - Configurable interval and directory
  - Scheduled snapshots + graceful shutdown final snapshot
  - SnapshotListener callbacks (onSnapshot, onRestore)
  - StateProvider SPI for plugging in state capture logic
- 32 + 19 = 51 tests

### P70: Elasticsearch Plugin
- `ElasticsearchPlugin`: multi-client ES instrumentation
- RestHighLevelClient: search, index, bulk, delete, update, get, msearch, scroll, reindex, count, exists
- ES 8.x Java Client: co.elastic.clients.elasticsearch.ElasticsearchClient
- RestClient low-level: performRequest with endpoint and status code
- Index name extraction (getIndex/index/indices)
- Search result extraction (hitCount via getHits/total/value)
- Server-side took time extraction
- Bulk operation size tracking
- Trace header injection (setHeader/putHeader)
- 28 tests

---

## P46-P65 — Enterprise & Ecosystem (2026-06-04)

### P46: Dynamic Configuration Center
- `ConfigChangeEvent` / `ConfigChangeListener` API for runtime config observation
- `DynamicConfigManager` interface with get/set/remove/listener/snapshot/rollback
- `DefaultDynamicConfigManager`: thread-safe with audit trail (1000 entries)
- `ConfigSnapshot` for versioned rollback support (max 50 snapshots)
- Integrated into `WeaverGirl.bootstrap()` with `DynamicCoreConfigListener`
- 31 tests including concurrency stress tests

### P47: Plugin Hot Management
- `PluginState` enum with valid transition rules (LOADED→ACTIVE→DISABLED→UNLOADED)
- `PluginManager` interface for runtime plugin lifecycle management
- `PluginInfo` metadata (state, version, interceptor count, timestamps)
- `DefaultPluginManager`: disable/enable/unload with interceptor coordination
- 11 tests covering state transitions, lifecycle, edge cases

### P48: Multi-tenant Isolation
- `TenantContext`: ThreadLocal-based tenant ID/group propagation
- `TenantSnapshot`: immutable capture/restore for cross-thread propagation
- `TenantConfig`: per-tenant sampling rate, max rate, threshold, custom props
- `TenantConfigRegistry`: thread-safe tenant config lookup by ID or current context
- 18 tests covering context, config, registry, cross-thread propagation

### P49: Agent Self-diagnostics
- `AgentDiagnostics`: memory snapshot history, interceptor hotspot analysis
- Memory tracking: heap/non-heap usage, trend calculation, auto pressure detection
- Interceptor hotspots: per-interceptor invocation count, avg/max/total time, top-N ranking
- Fault detection: recorded faults with type/message, recent faults query
- `generateReport()` comprehensive text report with uptime, memory, hotspots, faults
- 11 tests

### P50: Runtime Compatibility
- `RuntimeCompatibility`: Java version, Virtual Threads, GraalVM Native Image detection
- Framework detection: Spring Boot, Quarkus, Micronaut, Reactor, gRPC, Kafka, Redis, MongoDB
- `SpringBootAutoConfiguration`: recommended exclusions, reactive stack adaptations
- 12 tests

### P51: Plugin Marketplace Infrastructure
- `PluginMetadata`: structured descriptor from `META-INF/weaver-girl-plugin.properties`
- `PluginVerifier`: JAR integrity verification (SPI presence, SHA-256 checksum)
- 11 tests

### P52: Advanced Sampling Strategies
- `SamplingStrategy` SPI interface: shouldSample, updateMetrics, reset
- `SamplingStrategyRegistry`: register/activate strategies by name
- `FixedSamplingStrategy`: counter-based every-Nth-invocation
- `ProbabilisticSamplingStrategy`: random percentage-based with actual rate tracking
- 16 tests

### P53: Distributed Tracing
- `SpanContext`: trace/span/parent IDs, sampled flag, immutable baggage map
- `Tracer`: ThreadLocal span management, span creation, child spans
- Cross-thread propagation via `TracingSnapshot` (capture/restore)
- Cross-process HTTP header injection/extraction (X-Trace-Id, X-Span-Id, X-Baggage-*)
- Baggage propagation: setBaggage/getBaggage
- 16 tests

### P54: Performance Optimizations
- `CachedInterceptorRegistry`: LRU cache layer with hit/miss tracking, bounded size
- `AsyncEventPublisher`: async event delivery via daemon thread pool
- 11 tests

### P55: Security Audit
- `SecurityPolicy`: allow/deny pattern matching with precedence (deny > allow)
- `SecurityAuditLog`: thread-safe bounded audit log (500 entries)
- `AuditRecord`: structured audit entries with operation, principal, target, result
- 16 tests

### P56: Data Exporter SPI
- `DataExporter` SPI interface: export, flush, init, shutdown, health check
- `ExporterRegistry`: register/activate/deactivate, multi-exporter fan-out
- `LoggingExporter`: structured JSON output to SLF4J
- `InMemoryExporter`: bounded ring buffer with type/recent filtering
- 19 tests

### P57: Alerting Engine
- `AlertRule`: configurable rule with metric, operator, threshold, severity
- `AlertEngine`: centralized evaluation, multi-rule fan-out, channel notification
- `AlertEvent` / `AlertChannel`: structured alert with notification interface
- 19 tests

### P58: Service Topology
- `ServiceNode` / `ServiceEdge`: service and call edge models
- `TopologyGraph`: thread-safe graph builder with call recording, edge aggregation
- Query APIs: outgoing/incoming edges, text export
- 15 tests

### P59: Grafana Dashboards
- JVM Metrics dashboard: heap/non-heap memory, threads, GC, memory trend
- Interceptor Performance dashboard: rate, p50/p95/p99 latency, circuit breaker
- `load-dashboards.sh` automated provisioning script

### P60: HikariCP Plugin
- Connection pool monitoring for HikariCP (13th built-in plugin)
- Tracks acquisition time, slow threshold alerting, connection lifecycle

### P61: Metric Aggregator
- `TimeWindowAggregator`: sliding window with count/sum/min/max/avg/p50/p90/p95/p99
- `MetricSnapshot`: immutable snapshot with rate-per-second calculation
- `MetricRegistry`: named aggregator registry with convenience methods
- 17 tests

### P62: Agent REST API
- `AgentApiServer`: lightweight HTTP server with 7 endpoints
- GET /status, /plugins, /topology, /alerts, /metrics, /diagnostics
- 7 integration tests

### P63: Config Schema Validation
- `ConfigValidator`: validates config keys against known schema (BOOLEAN/INT/LONG/PORT)
- `ConfigValidationResult`: errors (blocking) + warnings (non-blocking)
- 12 tests

### P64: Plugin Health Monitoring
- `PluginHealth`: status (HEALTHY/DEGRADED/UNHEALTHY) with message
- `PluginHealthRegistry`: thread-safe health tracking and query
- 10 tests

---

## P40-P45 — Depth Polishing (2026-06-04)

### P40: Security Hardening
- Sensitive information masking in logs (`SecurityUtils.maskIfSensitive`)
- Configuration sanitization for safe logging
- Agent JAR integrity verification (SHA-256 in release workflow)

### P41: CI Quality Gate
- JaCoCo coverage check in CI quality job
- SpotBugs analysis in CI quality job
- Test + coverage report upload to GitHub Actions

### P42: Plugin Examples
- `AuditLogPlugin.java`: minimal plugin example using WeaverPlugin interface
- `CustomMetricPlugin.java`: advanced plugin with event publishing and conditional activation

### P43: Error Recovery
- Plugin loading failure degradation
- Transformer installation fallback
- Configuration corruption safe defaults

### P44: Structured Logging
- Unified log prefix format
- JSON event format support (`jsonEvents=true`)
- Log level configurable via agent args

### P45: API Stability
- `ApiStabilityTest`: verifies all public API method signatures
- Regression safeguard against accidental API breakage

---

## P30-P39 — Production Maturity (2026-06-04)

### P30: Code Quality Gate
- JaCoCo test coverage plugin with 80% minimum instruction coverage
- SpotBugs static analysis (High threshold, fail on error)
- CheckStyle code style checking (Google style, 200 max violations)
- Quality-gate Maven profile for CI enforcement
- Fixed SpotBugs exclude path for multi-module projects

### P31: Input Validation & Null Safety
- `ValidationUtils` utility class with 6 validation methods (`requireNonNull`, `requireNonEmpty`, `requireNonBlank`, `requirePositive`, `requireNonNegative`, `requireInRange`)
- Parameter validation added to all public API entry points: `WeaverGirl.bootstrap()`, `intercept()`, `withInstrumentation()`, `DefaultInterceptorRegistry`, `DefaultPluginContext`, `ClassMatcher`, `MethodMatcher`
- 16 unit tests for ValidationUtils

### P32: Error Handling
- Fixed 3 empty catch blocks (SampleApplication, MethodTimingPluginTest)
- Added error logging to InterceptAdvice pool release
- `WeaverGirlException` with 13 error codes across 5 categories (Bootstrap, Plugin, Transformer, Interceptor, Configuration)

### P33: Public API Documentation
- Completed Javadoc for `InterceptorEvent` (7 getters + Builder with 8 methods)
- Completed Javadoc for `InterceptorEventPublisher` (getInstance method)
- Overall API Javadoc coverage now ~99%

### P34: Integration Tests
- `MultiPluginCoordinationTest` — 5 tests for multi-plugin scenarios (shared registry, priority ordering, re-registration, event delivery, unregistration)
- `CircuitBreakerSamplingJointTest` — 6 tests for circuit breaker + sampling joint behavior
- `ConcurrencyStressTest` — 4 tests for concurrent register/unregister, read/write consistency, InterceptorHolder thread safety, unmodifiable view

### P35: Configuration Externalization
- `applyCoreConfig()` in `WeaverGirl.bootstrap()` — propagates agent args to SamplingController and CircuitBreaker
- Config keys: `samplingRate`, `samplingMaxRate`, `samplingThreshold`, `circuitBreakerThreshold`, `circuitBreakerCooldownMs`

### P36: Performance Benchmarks
- `CoreComponentBenchmark` — 5 JMH benchmarks for SamplingController, CircuitBreaker, MethodInvocationPool
- Updated `InterceptorBenchmarkRunner` to include all benchmark suites (16 total)

### P37: Advanced Plugin Capabilities
- `WeaverPlugin.isEnabled(PluginContext)` — conditional plugin activation with default implementation (backward compatible)
- `PluginLoader` checks `isEnabled()` after `init()`, before `registerInterceptors()`

### P38: OpenTelemetry Integration
- `OpenTelemetrySpanBridge` — converts InterceptorEvents to OTel-compatible spans (zero OTel SDK dependency)
  - Bounded buffer, OTel JSON export format, status mapping
  - 7 tests covering conversion, buffering, JSON rendering
- `W3CTraceContext` — full W3C Trace Context propagation
  - Parse/inject `traceparent` and `tracestate` headers
  - ThreadContext propagation, child span generation
  - 12 tests covering extraction, injection, propagation, edge cases
- OTel Collector config example (`docs/otel-collector-example.yaml`)

### P39: Deployment & Operations
- Health check endpoint: `healthPort` parameter, `/health` (liveness) and `/ready` (readiness) paths
- Enhanced shutdown hook with structured logging and health server cleanup
- Kubernetes Helm Chart (`deploy/helm/weaver-girl/`)
  - Chart.yaml, values.yaml, ConfigMap, Service, ServiceMonitor templates
  - Init container injection examples
- Operations Runbook (`docs/runbook.md`) — 10 chapters covering install, config, monitoring, troubleshooting, tuning, upgrade, rollback

### Statistics
- Total: ~540+ tests passing across 6 modules
- ~99% API Javadoc coverage
- 16 JMH performance benchmarks
- 40 files added, ~2860 lines of production code + tests + docs

---

## P28 -- Agent Self-Monitoring (JMX)

### Added
- `AgentMXBean` interface exposing runtime metrics via JMX: `TotalInterceptCount`, `TotalInterceptTimeMs`, `AverageInterceptTimeUs`, `TransformedClassCount`, `InterceptorDefinitionCount`, `PluginCount`, `AgentVersion`, `PluginNames`, `ExcludedClassCount`
- `AgentMonitor` implementation with `AtomicLong`/`AtomicInteger` counters, registered at MBean name `com.github.cc11001100.weavergirl:type=Agent`
- `AgentMonitorTest` unit tests for counter accuracy and JMX registration lifecycle
- `WeaverGirlMBean` legacy MBean interface exposing `AgentStatus` data (transformation count, error count, interceptor invocation/error counts, active plugin count, uptime, status report)
- `JmxRegistrar` for MBean registration/unregistration during agent lifecycle
- `JmxRegistrarTest` verifying registration idempotency and unregistration

---

## P27 -- Plugin Config Validation

### Added
- `YamlConfigLoader.validate()` -- structural validation of YAML interceptor configs before registration
  - Rejects entries missing both `className` and `classPattern`
  - Rejects entries missing all of `before`, `after`, and `around` advice classes
  - Validates regex patterns in `classPattern` and `methodPattern` via `Pattern.compile()`
  - Invalid entries are removed with warning logs; valid entries proceed normally
- `PluginContext` typed config access methods: `getConfigLong()`, `getConfigInt()`, `getConfigBoolean()`, `getConfigEnum()` -- all log warnings and return defaults on invalid input
- `PluginContextValidationTest` covering all typed access methods and edge cases

---

## P26 -- Prometheus Metrics Export

### Added
- `PrometheusExporter` -- built-in `InterceptorEventListener` that exposes metrics at `/metrics` via `com.sun.net.httpserver.HttpServer`
  - Counter: `weavergirl_slow_operations_total{plugin, type}` -- slow operations detected
  - Counter: `weavergirl_error_operations_total{plugin}` -- errors detected
  - Counter: `weavergirl_operation_duration_ms_sum{plugin}` -- cumulative duration
  - Counter: `weavergirl_operation_duration_ms_count{plugin}` -- operation count
- Enable via agent argument: `metricsPort=9400`
- Prometheus exporter automatically registered as event listener when `metricsPort` is specified
- `PrometheusExporterTest` covering metric accumulation and Prometheus text format output
- Zero external dependencies (uses JDK built-in HTTP server, manual JSON/metrics rendering)

---

## P25 -- Real-World Sample (Jetty + H2)

### Added
- `SampleApplication` with embedded Jetty HTTP server and H2 in-memory database
  - `/health` endpoint -- health check
  - `/users` endpoint -- list users, insert users (`?name=X`), trigger slow query (`?slow=true`)
  - Auto-demo mode that exercises JDBC and Servlet interception without manual `curl`
- Real Servlet and JDBC plugin interception in action (not just unit tests)
- `run-sample.sh` convenience script for building and running the sample with the agent
- `SampleApplicationTest` verifying startup and endpoint behavior

---

## P24 -- All 12 Plugins Wired to Events, MatchType Regression Tests

### Added
- All 12 built-in plugins now publish `InterceptorEvent` instances via `InterceptorEventPublisher`:
  - `JdbcPlugin` -- `slow-query`, `jdbc-error` events with SQL attribute
  - `ServletPlugin` -- `request`, `slow-request`, `request-error` events with HTTP method and URI
  - `SpringPlugin`, `RedisPlugin`, `KafkaPlugin`, `GrpcPlugin`, `MongoPlugin`, `HttpClientPlugin` -- domain-specific events
  - `MethodTimingPlugin` -- `slow-method` events with duration
  - `ExceptionMonitorPlugin` -- `exception` events with error details
  - `LoggingPlugin` -- logging-level events
  - `TraceCorrelationPlugin` -- trace propagation events
- `PluginMatchTypeRegressionTest` -- verifies that each plugin uses the correct `ClassMatcher.MatchType` for its interceptor definitions:
  - Interface types (`java.sql.Statement`, `javax.servlet.Filter`, etc.) must use `byInterface()`, not `byName()`
  - Abstract base classes (`HttpServlet`) must use `bySuperClass()`, not `byName()`
  - This prevents the regression where `byName()` would match only the abstract class itself, not real implementations

---

## P21-P23 -- Structured Events, Release Workflow

### Added
- `InterceptorEvent` -- immutable structured event with builder pattern: `type`, `plugin`, `className`, `methodName`, `timestamp`, `durationMs`, `attributes`
- `InterceptorEventPublisher` -- singleton publisher with `CopyOnWriteArrayList` listener management
- `InterceptorEventListener` -- functional interface for event consumers
- `JsonEventListener` -- built-in listener that outputs events as structured JSON to SLF4J (enable: `jsonEvents=true`)
- `InterceptorEventPublisherTest` and `JsonEventListenerTest`
- GitHub Actions release workflow (`.github/workflows/release.yml`) -- triggered by `v*` tags, builds with version from tag, publishes agent JAR with SHA-256 checksum as GitHub Release

---

## P16-P20 -- Security, Documentation, MatchType Fixes

### Added
- `YamlConfigLoader` advice class validation -- advice classes must implement `Interceptor` interface; arbitrary class instantiation is rejected
- `PluginClassLoader` security -- parent-first loading for `weavergirl-api` packages to prevent class shadowing
- Plugin developer guide (`docs/plugin-developer-guide.md`)
- YAML config reference (`docs/yaml-config-reference.md`)
- Troubleshooting guide (`docs/troubleshooting.md`)
- Architecture documentation (`docs/architecture.md`)

### Fixed
- **MatchType regression** -- plugins that target interfaces (`java.sql.Statement`, `javax.servlet.Filter`, `java.sql.Connection`) were using `ClassMatcher.byName()` which only matches the interface class itself, not concrete implementations. Fixed to use `ClassMatcher.byInterface()` and `ClassMatcher.bySuperClass()` respectively
  - `JdbcPlugin`: Statement/PreparedStatement/Connection changed from `byName()` to `byInterface()`
  - `ServletPlugin`: HttpServlet changed from `byName()` to `bySuperClass()`; Filter changed from `byName()` to `byInterface()`
  - Similar fixes across Redis, Kafka, gRPC, MongoDB, HttpClient, Spring plugins
- `PluginMatchTypeRegressionTest` added to prevent future regressions

---

## P11-P15 -- Production Safety

### Added
- `InterceptorCircuitBreaker` -- per-interceptor fault isolation
  - CLOSED -> OPEN transition after N consecutive failures (default: 5)
  - OPEN -> CLOSED transition after cooldown period (default: 60,000ms)
  - Thread-safe with per-interceptor synchronization
  - `CircuitBreakerIntegrationTest` verifying full lifecycle
- `SamplingController` -- adaptive sampling under high load
  - Counter-based sampling: rate of N means 1 in N invocations is intercepted
  - Automatic rate adaptation by `SamplingMonitor` background thread
  - Configurable threshold and max rate
  - `SamplingControllerTest` and `SamplingIntegrationTest`
- `ThreadContext` -- thread-local context for cross-callback state
  - `put()`/`get()`/`remove()`/`clear()` for per-thread key-value storage
  - `capture()`/`restore()` for cross-thread context propagation
  - `ContextRunnable` and `ContextCallable` for automatic context propagation with executor services
  - `ThreadContextTest` covering all operations and leak prevention
- `ConfigWatcher` -- YAML configuration hot-reload
  - Uses `java.nio.file.WatchService` on a daemon thread
  - Unregisters all `yaml-*` interceptors before reload
  - 2-second debounce for file change events
  - Optional `afterReloadCallback` for retransformation
  - `ConfigWatcherTest` covering file change detection and reload
- `AgentStatus` -- centralized agent state tracking (transformation count, error count, interceptor invocation counts, plugin statuses)

---

## P0-P10 -- Core Engine, 12 Plugins, CI, Docker

### Added
- **Core engine:**
  - `WeaverGirl` -- main entry point with fluent programmatic API and bootstrap logic
  - `InterceptAdvice` -- ByteBuddy `@Advice` class inlined into target methods
  - `WeaverTransformer` -- registers type transformers based on interceptor definitions
  - `DefaultInterceptorRegistry` -- thread-safe registry with `ConcurrentHashMap` index and atomic index rebuild
  - `InterceptorHolder` -- global holder for registry reference (needed because ByteBuddy Advice classes are static)
  - `MethodInvocationPool` -- thread-local object pool for `MethodInvocation` instances
  - `BootstrapInjection` -- injects helper classes into Bootstrap ClassLoader for `java.*`/`javax.*` instrumentation
  - `TypeExistenceChecker` -- skips EXACT_NAME interceptors when target class is not on classpath
- **Plugin SDK (`weaver-girl-api`):**
  - `WeaverPlugin` interface with lifecycle: `name()`, `init()`, `registerInterceptors()`, `destroy()`, `depends()`
  - `AbstractPlugin` with fluent builder API (`intercept()`, `interceptClassPattern()`, `interceptAnnotated()`, `interceptSubclassOf()`, `interceptImplementing()`)
  - `Interceptor` interface with `before()`, `after()`, `onException()` callbacks (all default no-op)
  - `MethodInvocation` context object with `skipMethod()`, `setReturnValue()`, `suppressException()`, argument access, return value override
  - `ClassMatcher` with 5 match types: `EXACT_NAME`, `NAME_PATTERN`, `ANNOTATION`, `SUPER_CLASS`, `INTERFACE`
  - `MethodMatcher` with 3 match types: `EXACT_NAME`, `NAME_PATTERN`, `ANY`
  - `Pointcut` binding a `ClassMatcher` and `MethodMatcher`
  - `InterceptorDefinition` binding a `Pointcut` and `Interceptor` with priority
  - `InterceptorRegistry` interface with `register()`, `unregister()`, `getInterceptorsForClass()`, `getAllDefinitions()`
  - `PluginContext` interface with typed config access
- **Annotation module (`weaver-girl-annotation`):**
  - `@WeaveClass` -- declarative class matching
  - `@Before`, `@After`, `@Around` -- declarative method interception
- **12 built-in plugins (`weaver-girl-plugins`):**
  - `ServletPlugin` -- HttpServlet and Filter interception (javax + jakarta)
  - `JdbcPlugin` -- Statement/PreparedStatement/Connection interception with slow-query detection
  - `SpringPlugin` -- Spring Controller and Component interception
  - `RedisPlugin` -- Jedis/Lettuce command interception
  - `KafkaPlugin` -- Kafka Producer/Consumer interception
  - `GrpcPlugin` -- gRPC stub interception
  - `MongoPlugin` -- MongoDB client interception
  - `HttpClientPlugin` -- Apache HttpClient interception
  - `MethodTimingPlugin` -- generic method execution timing
  - `TraceCorrelationPlugin` -- cross-service trace ID propagation
  - `ExceptionMonitorPlugin` -- exception tracking and reporting
  - `LoggingPlugin` -- log level and message interception
- **Agent module (`weaver-girl-agent`):**
  - `WeaverGirlAgent` with `premain()` and `agentmain()` entry points
  - Agent argument parsing in `key=value` format
  - Dynamic attach support with `retransformLoadedClasses()`
  - YAML config loading via `YamlConfigLoader`
  - Plugin directory loading via `PluginLoader.loadPluginsFromDirectory()`
  - Shutdown hook for clean plugin destruction
- **Plugin loading infrastructure:**
  - `PluginLoader` -- SPI-based discovery via `ServiceLoader`, lifecycle management
  - `PluginDependencyResolver` -- topological sort based on `depends()`
  - `PluginClassLoader` -- child-first class loading with parent-first for API packages
  - `PluginJarScanner` -- JAR file scanning from plugin directories
- **YAML configuration:**
  - `YamlConfigLoader` -- parses `weaver.yml`, registers interceptor definitions
  - `WeaverConfig` -- data model with `InterceptorConfig`, agent-level settings
- **CI pipeline (`.github/workflows/ci.yml`):**
  - Build matrix: JDK 8, 11, 17, 21 on Ubuntu
  - Full build + test on every push/PR to main
  - Agent JAR packaging and verification step
  - Test report upload on failure
- **Docker support:**
  - `Dockerfile` for containerized deployment
  - `docker-compose.yml` for local development
- **Sample application (`weaver-girl-sample`):**
  - `SampleApplication` demonstrating programmatic, annotation, and YAML hook modes
  - `TargetService` for interception target
  - Sample plugins (`LoggingPlugin`, `MethodTimingPlugin`) demonstrating plugin authoring
  - `SampleYamlInterceptor` and `AnnotationInterceptor` for YAML and annotation modes

### Fixed
- `InterceptAdvice` `skipMethod` / return value override mechanism
- `YamlConfigLoader` advice caching (was per-invocation reflection, now uses `ConcurrentHashMap` cache)
- `DefaultInterceptorRegistry` atomic index rebuild (volatile swap prevents read-write contention)
- `ThreadLocal` leak in `ThreadContext` (calls `ThreadLocal.remove()` in `clear()`)
- `ConfigWatcher` debounce for file change events (2-second minimum between reloads)
- Agent SLF4J packaging and shade relocation
- `MethodInvocation` Method object exposure for reflective parameter/return type access
- `suppressException()` mechanism for circuit-breaker fallback patterns
- `PluginClassLoader` security (parent-first for API packages to prevent class shadowing)
