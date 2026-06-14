# Weaver-Girl Release Readiness (verified 2026-06-15)

Honest status of the quality gates and what stands between the current build and a
shippable **APM Hook base**. (IAST readiness is a separate, larger effort — see
`docs/superpowers/plans/2026-06-15-apm-iast-hook-base-release-readiness.md`.)

## Gate status

| Gate | Target | Current | Status |
|---|---|---|---|
| Unit tests (`clean test`) | green | 1376 tests, 0 failures | ✅ GREEN |
| JaCoCo coverage (`weaver-girl-api`) | 80% instr / 70% branch | **~51% instr / ~40% branch** | ❌ RED |
| Checkstyle (google_checks, max 200) | ≤ 200 violations/module | annotation **0** (fixed); api **~3200**, others pending | ❌ RED |
| SpotBugs (threshold High) | 0 High | see `target/spotbugsXml.xml` | ⚠️ verify |
| Packaged agent attach (`-javaagent`) | bootstraps on real app | **verified working** | ✅ GREEN (was ❌ P0 — see below) |

## Critical fix: the packaged agent jar now bootstraps (was a P0 blocker)

**This was the single most important release-readiness finding.** Until commit
`66b6627`, the packaged `weaver-girl-agent.jar` could **not attach to any real
application** — every `-javaagent` invocation failed during `WeaverGirl.bootstrap()`
with `NoClassDefFoundError` or a `loader-constraint violation`. The framework's
1376 unit tests never caught it because they attach **in-process**, never
exercising the shaded `-javaagent` jar. For an APM/IAST hook base, an agent that
cannot attach is the worst possible release blocker.

Root causes (both fixed in `66b6627`):

1. **ByteBuddy relocation** (`net.bytebuddy` → `shaded.net.bytebuddy`) in the shade
   plugin. ByteBuddy loads some of its own classes (e.g. `AgentBuilder$Listener$Adapter`)
   by their **original** name via the bootstrap classloader at runtime, which the
   shade plugin cannot rewrite → `NoClassDefFoundError`. Relocation removed.
2. **Custom `BootstrapInjection`** appended the *whole* agent JAR (which bundles all
   of ByteBuddy) to the bootstrap classloader while the same JAR was already on the
   app classpath via `-javaagent`. `net.bytebuddy` classes then resolved to different
   `Class` objects in the app vs bootstrap loaders → loader-constraint violation.
   The redundant custom injection was removed; ByteBuddy's own
   `AgentBuilder.InjectionStrategy.UsingInstrumentation` (already configured) injects
   only the needed helper classes into a temp JAR, avoiding the split.

The CI **agent-smoke** workflow (`.github/workflows/agent-smoke.yml`) now guards
against regressions of this class: it attaches the packaged jar to the sample app
and asserts `/health`, `/ready`, `/metrics` respond.

**Trade-off:** ByteBuddy is now bundled un-shaded. If a host app also bundles a
conflicting ByteBuddy, the agent's version (prepended via `-javaagent`) wins. A
proper dual-classloader agent architecture (thin bootstrap jar + child
`URLClassLoader` for the agent body) would restore relocation safety and is the
recommended follow-up for a hardened release.

## What this means

- The **build compiles and all unit tests pass**. The framework is functionally usable.
- The **`verify`-phase quality gate does not pass**: the 80% coverage rule fails on
  `weaver-girl-api`, and the google_checks (2-space) style rule fails across modules
  written in 4-space style. `README.md` advertises "JaCoCo (80%+ coverage)" — that
  target is **not currently met**.
- The annotation module's 116 style violations were fixed (reformatted to google style);
  it is the reference for bringing the remaining modules in line.

## Path to a green quality gate

1. **Coverage** — raise `weaver-girl-api` from ~51% toward 80%. Biggest wins: add tests
   for the untested public API surface in `api/` (66 main classes, 18 test classes).
   Do **not** lower the 0.80/0.70 floor; raise the tests.
2. **Checkstyle** — reformat `weaver-girl-api` / `weaver-girl-core` / `weaver-girl-plugins`
   to `google_checks.xml`. The annotation module was done with
   `google-java-format --replace`; the same tool applied per-module clears the 2-space
   violations deterministically. Review the diff (it reflows more than indent).
3. Re-run `./scripts/release-check.sh` until coverage and style rows go GREEN.

## Reproducible build

`mvnw` was regenerated (the committed script was a 14-byte `404: Not Found`). Use
`./mvnw` so the build is reproducible without a system Maven.
