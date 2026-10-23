# Plugin Architecture Audit

Date: 2026-10-23. Scope: how far the engine has been pushed behind the plugin boundary, what was
broken in that boundary, and what still leaks.

## Module layers

| Layer | Module(s) | Role |
|-------|-----------|------|
| Contract | `weaver-girl-api` | `WeaverPlugin`, `PluginContext`, `AbstractPlugin`, the fluent builders, matchers, pointcuts, and the static product services (`Tracer`, `MetricRegistry`, `ExporterRegistry`, `TopologyGraph`, `AlertEngine`, `Taint*`) |
| Engine | `weaver-girl-core` | Transformer, advice, interceptor registry, and the plugin runtime itself (`PluginLoader`, `PluginClassLoader`, `PluginJarScanner`, `PluginDirectoryWatcher`, `DefaultPluginContext`) |
| Annotation | `weaver-girl-annotation`, `-annotation-runtime` | `@WeaveClass` and the annotation-driven loader |
| Capability plugins | `weaver-girl-plugins/weaver-girl-plugin-*` (~120 jars) | Method, field, catch, and framework-level instrumentation |
| Runtime capabilities | `weaver-girl-runtime-plugins/*` | Servers and background services that used to be hardcoded in the agent entry point (metrics, management API, health, diagnostics, config watch, annotation scan, OTLP, span export, update check, alert file, dynamic refresh, JMX, taint, JSON events) |
| Entry point | `weaver-girl-agent` | `premain`/`agentmain`, argument parsing, isolated class loader, packaging |
| Product surfaces | `weaver-girl-bridge-micrometer`, `-bridge-aopalliance`, `weaver-girl-sample` | Adapters and the demo |

The direction is one-way: plugins depend on the API, the agent depends on everything, core does not
name a capability plugin. The agent entry point now does almost nothing beyond parsing arguments,
building the isolated loader, calling `bootstrap`, and recording startup timing — every switch that
used to live there (`metricsPort`, `apiPort`, `healthPort`, `watch`, `annotationPackages`,
`jsonEvents`, `otlpEndpoint`, `spanExport`, `updateCheckEndpoint`, `jmx`, `refreshIntervalSeconds`,
`taint`) belongs to a runtime plugin.

## Two class loaders, two isolation rules

- `AgentClassLoader` is child-first for the agent body, but parent-first for the JDK, `com.sun.`,
  relocated SLF4J, `api.*`, and the advice-bridge closure (`core.InterceptorHolder`,
  `core.interceptor.`, `core.sampling.`, `core.status.`, ...). This is the invariant that keeps
  inlined advice resolving to the same `Class` objects the registry was written into.
- `PluginClassLoader` is one per plugin jar, parent-first for `api.*`, `annotation.*`, and the JDK,
  child-first for everything else. Its parent is the agent loader, so core classes are reachable by
  fall-through. That is deliberately not part of the contract: a plugin that touches core is tied to
  internals and may see a different copy from the engine.
- Cross-plugin visibility is explicit. A plugin publishes packages or individual classes via
  `plugin.exports` in `META-INF/weaver-girl-plugin.properties`; only a plugin that names the owner in
  `depends()` can load them. Services go through `PluginServices`, and a service registered through
  `PluginContext` is withdrawn when the provider is unloaded.

## Discovery, selection, and lifecycle

Two discovery paths share one lifecycle:

1. **Classpath SPI** — `ServiceLoader` over `WeaverPlugin` from the agent loader. This is how bundled
   instrumentation and runtime plugins arrive.
2. **Plugin directory** — `PluginJarScanner` opens each jar, reads
   `META-INF/weavergirl-capabilities`, and `PluginJarSelector` applies the same
   `enabledPlugins`/`disabledPlugins` selection *before* a disabled jar is opened. Third-party jars
   that follow neither naming convention are always loaded. Selection happens before class loading,
   so a disabled capability's jar never reaches a loader.

For each plugin, in dependency order: `init(PluginContext)` → `isEnabled` (false = inactive, not a
failure) → `registerInterceptors` (optional, default no-op) → runtime → `destroy`. `depends()` is a
soft, ordering-only edge; `requires()` is a hard edge that prevents activation and cascades when the
required plugin is unloaded. Failures are isolated per plugin and counted in the load summary.

The directory is re-scanned when it changes. A jar whose content fingerprint is unchanged is not
loaded again, a changed jar is reloaded, and a removed jar unloads its plugins plus everything that
required them, unregistering owned interceptors and withdrawing published services along the way.

## Configuration

Every plugin reads through `PluginContext`, with a first-match-wins chain:

1. `weavergirl.plugin.<name>.<key>` system property
2. YAML `plugins.<name>.<key>`
3. plain `<key>` system property
4. agent arguments and top-level YAML keys (the launch map)
5. the caller's default

The YAML `plugins:` map is flattened into namespaced keys before plugins load, so a plugin sees only
its own section for a scoped read and cannot receive another plugin's scoped value. Scalar top-level
keys (`metricsPort`, `watch`, `otlpEndpoint`, ...) are collected as capability options and merged
into the same launch map as agent arguments; agent arguments win.

`plugins:` also accepts a scalar directory, which is the drop-in plugin switch. The agent's YAML
pre-scan and the core loader agree on that form, and a configured directory equal to the agent's own
plugin directory is loaded once, by the isolated plugin loader, instead of also being preloaded into
the agent loader.

## What this audit fixed

- **Whole YAML document lost on a plugin key.** SnakeYAML typed binding rejected unknown top-level
  keys (`metricsPort`, `plugins`), which made `parseFromFile` return an empty config and discarded
  every interceptor definition in the same file. Parsing is now lenient; unknown scalars are passed
  to plugins.
- **`plugins.<name>.<key>` documented but unreachable.** The section was never flattened, so scoped
  configuration never reached `PluginContext`. It is now flattened and merged.
- **Two-argument `getConfig(key, default)` ignored system properties and scoped YAML.** All ~140
  call sites that use the default-returning overload were affected. Both overloads now share the
  same chain.
- **Duplicate plugin instantiation** when `plugins` pointed at the agent's own plugin directory.
  The agent loader preloaded the jars and the isolated loader loaded them again.
- **Build list drift.** The agent pom listed every plugin artifact by name in
  `includeArtifactIds`; it now selects by group and `provided` scope, and the packaged set was
  verified at 140 jars.
- **Capability-only plugins needed an empty `registerInterceptors`.** It is now a default no-op.
- **Stale load accounting** in the directory rescan (cumulative counts and a negative failure
  count) — the summary now counts the current batch only.
- **Dead legacy core wiring** (`initSamplingMonitor`, `initConfigWatcher`, `setConfigWatcher`,
  `initSpanExport`, `stopSpanExport`, `getSpanExporter`, `initDynamicRefresh`, `stopDynamicRefresh`,
  and the fields/imports behind them). Span export and version checking are runtime plugins; keeping
  a second, unstarted path in core invited a duplicate exporter and misled readers. Tests that only
  exercised the removed wrappers were deleted; the underlying `SpanExport` utility keeps its own
  tests.

## Remaining gaps and recommendations

1. **Core is reachable by fall-through from plugins.** The loader does not deny it. A future hardening
   step is an explicit deny for `core.*`/`agent.*` with the runtime plugins exempted (they are loaded
   by the agent loader, not by `PluginClassLoader`), but that changes what an accidental core user
   sees, so it needs a compatibility note.
2. **`getAllConfig()` exposes other plugins' scoped keys** in its flat map. Reads through
   `getConfig(key)` are isolated; an iteration over everything is not. If a plugin scans all keys,
   filter `plugins.<other>.*` in the context.
3. **`ConfigValidator` still hardcodes capability keys** and is not called from production code. Either
   delete it or let plugins register their own key/type rules; today adding a capability means editing
   a core list that nothing executes.
4. **Agent-side YAML pre-scan is a second parser.** It only needs `config`, the plugin directory, and
   the selection lists, and it deliberately understands only scalars. Keeping it small is right; if it
   grows again, pass the parsed `WeaverConfig` from core instead of re-reading the file.
5. **The plugin directory watcher is always on** when a directory is configured; there is no switch
   to disable hot reload. That is a deliberate zero-cost choice (one daemon thread), but it should be
   documented next to `plugins=`.
6. **Plugin names are authoritative, artifact ids are not.** `weaver-girl-plugin-timing` is
   `method-timing` and `weaver-girl-plugin-exception` is `exception-monitor`. Documentation and
   tooling must read `name()`.

## Verification

- `weaver-girl-core`: clean full suite green (1388 tests) after removing the legacy wrappers and
  their tests. One pre-existing ordering test (`DefaultInterceptorRegistryTest.annotationLoader_
  fqnDeclarePrecedence_ordersEqualPriorityDefinitions`) failed once inside a combined `-am` run and
  passes in the clean core run and when run alone; it is unrelated to the plugin changes.
- `weaver-girl-agent`: builds with `-am`, packages 140 jars into `target/plugins`, and the agent tests
  (including `sameDirectory`) pass.
- End-to-end probe with a third-party jar in a directory: YAML `plugins.cfg-probe.alpha`,
  agent argument `beta`, top-level `gamma`, and namespaced system property
  `weavergirl.plugin.cfg-probe.delta` all arrive at `PluginContext`, the plugin is loaded exactly
  once, and it is destroyed at shutdown.
- Same-directory reproduction: `plugins=<agent plugin dir>` logs the skip line and the probe is
  loaded once by the isolated loader.
