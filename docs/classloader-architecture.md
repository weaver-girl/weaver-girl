# ClassLoader Architecture

How weaver-girl loads itself, and the one invariant that must never break.

This matters because weaver-girl is a **Hook base for APM/IAST products**: it is
attached (`-javaagent`) into arbitrary JVMs that already carry their own ByteBuddy,
slf4j, and libraries. The agent must not collide with the host, and — crucially — the
host must never observe a broken agent.

## Topology

```
                        ┌──────────────────────────────────────────────┐
   bootstrap loader ───▶│  JDK classes                                 │
                        │  + advice-bridge helpers injected here by     │
                        │    AgentBuilder.InjectionStrategy (for        │
                        │    bootstrap-loaded instrumented targets)     │
                        └──────────────────────────────────────────────┘
                                              ▲
                                              │ parent
                        ┌──────────────────────────────────────────────┐
   system (app) loader ▶│  host application classes                     │
                        │  + the agent jar (via -javaagent)             │
                        │  + the ADVICE BRIDGE classes (parent-first):   │
                        │    core.InterceptorHolder, core.status.*,     │
                        │    core.sampling.*, core.switches.*,          │
                        │    core.circuit.*, core.management.*,         │
                        │    core.interceptor.*, api.*, relocated slf4j │
                        └──────────────────────────────────────────────┘
                                              ▲
                                              │ parent (system loader)
                        ┌──────────────────────────────────────────────┐
   AgentClassLoader ────▶│  the agent BODY (child-first):               │
   (child-first)         │    WeaverGirl, WeaverTransformer,             │
                        │    PluginLoader, config, metrics, event,      │
                        │    registry, plugins,                         │
                        │    shaded.net.bytebuddy.*  ← ISOLATED         │
                        └──────────────────────────────────────────────┘
```

- **Bootstrap loader** — JDK classes, plus the advice-bridge helpers that
  `AgentBuilder.InjectionStrategy.UsingInstrumentation` injects so that
  *bootstrap-loaded* instrumented targets (rare in practice) can resolve the bridge.
- **System (app) loader** — the host app, plus the agent jar (that is how
  `-javaagent` works), plus the **advice-bridge classes** (see below).
- **`AgentClassLoader`** — a child-first `URLClassLoader` (parent = system loader)
  that holds the **agent body**: the orchestration, the transformer, plugins, and
  ByteBuddy. ByteBuddy is shaded (`net.bytebuddy` → `shaded.net.bytebuddy`) and lives
  only here, so the agent's ByteBuddy never collides with a host's own `net.bytebuddy`.

## The advice-bridge invariant (read this before touching core)

ByteBuddy `@Advice` is **inlined**: the advice method's bytecode is copied into each
instrumented target and runs on whatever `ClassLoader` loaded that target. The advice
(`core.InterceptAdvice`) references a small set of "bridge" classes at runtime —
`InterceptorHolder`, `AgentStatus`, `SamplingController`, `MethodInvocationPool`, the
public `api.*` interfaces, and their transitive closure.

> **Invariant:** those bridge references must resolve to the **same `Class` objects**
> that the agent wrote the interceptor registry into. If the advice resolves
> `InterceptorHolder` to a different `Class` than the one `InterceptorHolder.setRegistry`
> wrote to, the advice reads a **null registry** and interception silently stops — every
> endpoint stays green, nothing errors.

This is why `AgentClassLoader` is child-first for the agent body but **parent-first for
the bridge closure**: the bridge resolves on the system loader, exactly as it did before
isolation, and `setRegistry` (called from the isolated `WeaverGirl`) targets that same
system-loaded instance.

### The parent-first set

`AgentClassLoader` delegates these to the parent (system loader) **first**:

- JDK packages (`java.`, `javax.`, `sun.`, `jdk.`, …) and `com.sun.` (the JDK's
  `com.sun.net.httpserver` used by the health/metrics servers).
- The relocated SLF4J facade (`…shade.org.slf4j.`) — used by the bridge, kept on the
  system loader to avoid a split binding. It is namespaced, so it still never clashes
  with the host's `org.slf4j`.
- `com.github.cc11001100.weavergirl.api.` — the public API, referenced by the advice
  **and** by plugins; must be one shared set of `Class` objects across the boundary.
- The advice-bridge packages: `core.interceptor.`, `core.sampling.`, `core.status.`,
  `core.switches.`, `core.circuit.`, `core.management.`
- `core.InterceptorHolder` by FQN — it shares the `core` root package with `WeaverGirl`
  and `InterceptAdvice` (which must stay isolated), so the whole package cannot be
  parent-first.

Everything else — including `core.WeaverGirl`, `core.transformer.*`, `core.plugin.*`,
`core.config.*`, `core.metrics.*`, `core.registry.*` (the `DefaultInterceptorRegistry`
impl), the plugins, and `shaded.net.bytebuddy.*` — is **child-first** (isolated).

### Maintaining the set

The bridge closure must stay **closed**: a parent-first (bridge) class must only
reference other bridge classes, the JDK, the public API, and the relocated slf4j —
**never** an isolated class (a downward reference would throw `ClassNotFoundException`).

If you add a class that the inlined advice (`InterceptAdvice`) references at runtime,
**add it to the parent-first set** in `AgentClassLoader`, or interception will silently
break. The set is verified empirically by the cross-JDK smoke's interception assertion.

## Wiring

`WeaverGirlAgent.premain`/`agentmain` are a thin stub: build the `AgentClassLoader`,
reflectively invoke `init` on the **isolated** copy of `WeaverGirlAgent`, and on any
failure fall back to running `init` directly on the system loader (the agent always
still attaches). The isolated `init` owns all runtime state (health server, registry,
metrics). `/stats` reports `byteBuddyClassLoader` and `agentBodyClassLoader` so isolation
is observable — both should read `AgentClassLoader`.

## What the regression net checks

The cross-JDK smoke (`scripts/cross-jdk-smoke.sh`) and CI
(`.github/workflows/agent-smoke.yml`) attach the packaged jar and assert:

1. `/health`, `/ready`, `/metrics` respond (attach + bootstrap works).
2. `/stats` exposes the live counters (observation plumbing loads).
3. **Interception actually fires** — after driving servlet traffic,
   `weavergirl_operation_duration_ms_count{plugin="servlet"} > 0`. This is the net for
   the advice-bridge invariant: a split-bridge regression makes this assertion fail.
4. **ByteBuddy is isolated** — `/stats` reports `byteBuddyClassLoader=AgentClassLoader`.

A packaging or classloader change that breaks interception will fail (3); one that leaks
ByteBuddy onto the host loader will fail (4).
