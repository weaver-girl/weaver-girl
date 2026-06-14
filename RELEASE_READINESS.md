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
