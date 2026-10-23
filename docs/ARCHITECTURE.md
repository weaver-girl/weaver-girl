# Architecture

## Overview

Weaver-Girl is a Java bytecode instrumentation framework built on [ByteBuddy](https://bytebuddy.net/). It intercepts method calls at the bytecode level, allowing plugins to observe and modify application behavior without source code changes. The framework is designed for production use with fault isolation, adaptive sampling, and self-monitoring built in.

---

## Module Structure

The project is a multi-module Maven build. Every library module is also a JPMS module: its
`module-info.java` (under `src/main/java9`) is compiled for JDK 9 and shipped at
`META-INF/versions/9/module-info.class`, so the same jar is a plain Java 8 jar on JDK 8 and a named
module on JDK 9+.

```
weaver-girl/                              (parent POM, packaging=pom)
├── weaver-girl-api/                      Plugin SDK — interfaces, matchers, context, event system
├── weaver-girl-annotation/               Declarative annotations — @WeaveClass, @Before, @After, @Around
├── weaver-girl-annotation-runtime/       Scans and loads @WeaveClass interceptors
├── weaver-girl-core/                     Engine — transformer, registry, config, circuit breaker, sampling
├── weaver-girl-bridge-micrometer/        Optional: publishes agent counters to a Micrometer registry
├── weaver-girl-bridge-aopalliance/       Optional: adapts an AOP Alliance MethodInterceptor
├── weaver-girl-plugins/                  (aggregator POM, packaging=pom)
│   ├── weaver-girl-plugin-servlet/       one module per built-in plugin, each depending on api only
│   ├── weaver-girl-plugin-jdbc/
│   ├── ...                               (17 plugin modules, 18 plugins — async ships two)
│   └── weaver-girl-plugins-tests/        cross-plugin regression tests
├── weaver-girl-runtime-plugins/          (aggregator POM, packaging=pom)
│   ├── weaver-girl-runtime-json-events/  the agent's own optional capabilities, one per module
│   ├── weaver-girl-runtime-prometheus/
│   ├── weaver-girl-runtime-config-watch/
│   ├── weaver-girl-runtime-annotation-scan/
│   └── weaver-girl-runtime-management-api/
├── weaver-girl-agent/                    Agent entry point — premain/agentmain, shades the engine
├── weaver-girl-testkit/                  In-process plugin test harness (test scope, never shipped)
└── weaver-girl-sample/                   Sample application — embedded Jetty + H2 demo
```

### Module Dependency Graph

```
                 weaver-girl-annotation
                          |
                 weaver-girl-api  (Plugin SDK)
                /    |        \
               /     |         \
   bridge-*   core   plugin-<name> ...
       \       |         /
        \      |        /
         weaver-girl-agent   (shades everything into one javaagent jar)
                 |
         weaver-girl-sample
```

Key dependency rules:

- **`weaver-girl-api`** depends only on `weaver-girl-annotation` -- it is the Plugin SDK that third-party plugins code against
- **`weaver-girl-core`** depends on `weaver-girl-api` only -- the engine carries no Micrometer and no AOP Alliance dependency
- **each `weaver-girl-plugin-*`** depends on `weaver-girl-api` only -- plugins never depend on core, and they match target classes by name so they compile without the instrumented library
- **`weaver-girl-bridge-*`** depend on `weaver-girl-api` plus the one third-party library they bridge; they read engine state through `AgentStatusView`, never through core internals
- **`weaver-girl-agent`** shades core, the bridges and the annotation runtime into the single jar a JVM loads with `-javaagent`. The plugins are **not** inside it: the build copies each plugin jar into a `plugins/` directory next to the agent jar, and at startup only the jars for enabled capabilities are put on the agent ClassLoader. `disabledPlugins` (or `enabledPlugins`, an allowlist) decides this before any plugin class is loaded, so a capability that is off costs nothing. With neither set, every jar is loaded. Dropping a plugin dependency from the agent module ships a distribution without that capability
- **`weaver-girl-runtime-*`** are the agent's own optional capabilities, packaged and loaded exactly like the instrumentation plugins. Each switches itself on from a config key (`jsonEvents`, `metricsPort`, `watch`, `annotationPackages`, `apiPort`) and does nothing when that key is absent, so shipping the jar costs nothing until the capability is asked for. They are the one exception to "plugins depend only on the API": they drive engine services (`PrometheusExporter`, `ConfigWatcher`, `AgentApiServer`, `AnnotatedClassScanner`), so they depend on core
- **`weaver-girl-sample`** depends on `weaver-girl-api` and `weaver-girl-core` -- demonstrates all three hook modes

### Module Details

| Module | Purpose | Key Classes |
|--------|---------|-------------|
| `weaver-girl-api` | Plugin SDK: the only module a third-party plugin codes against | `WeaverPlugin`, `AbstractPlugin` (method, field and catch builders), `Interceptor`, `FieldInterceptor`, `CatchInterceptor`, `MethodInvocation`, `ClassMatcher`, `MethodMatcher`, `FieldMatcher`, `Pointcut`, `InterceptorDefinition`, `InterceptorRegistry`, `PluginContext`, `ThreadContext`, `Tracer`, `MetricRegistry`, `ExporterRegistry`, `TopologyGraph`, `AlertEngine`, `Taint`, `TaintFindings`, `InterceptorEvent`, `InterceptorEventPublisher` |
| `weaver-girl-core` | Engine implementation: bytecode transformation, registry, config, fault isolation | `WeaverGirl`, `InterceptAdvice`, `WeaverTransformer`, `DefaultInterceptorRegistry`, `PluginLoader`, `YamlConfigLoader`, `ConfigWatcher`, `InterceptorCircuitBreaker`, `SamplingController`, `PrometheusExporter`, `AgentMXBean`/`AgentMonitor` |
| `weaver-girl-annotation` | Declarative annotation-driven hook mode | `@WeaveClass`, `@Before`, `@After`, `@Around` |
| `weaver-girl-plugin-<name>` | One built-in instrumentation plugin per module | `ServletPlugin`, `JdbcPlugin`, `SpringPlugin`, `RedisPlugin`, `KafkaPlugin`, `GrpcPlugin`, `MongoPlugin`, `HttpClientPlugin`, `HikariPlugin`, `MethodTimingPlugin`, `TraceCorrelationPlugin`, `ExceptionMonitorPlugin`, `LoggingPlugin`, `OkHttpPlugin`, `RabbitMQPlugin`, `ElasticsearchPlugin`, `AsyncContextPropagationPlugin`, `CompletableFuturePropagationPlugin` |
| `weaver-girl-runtime-<capability>` | One of the agent's own optional capabilities, loaded only when its config key is set | `JsonEventsPlugin` (`jsonEvents`), `PrometheusPlugin` (`metricsPort`), `ConfigWatchPlugin` (`watch`), `AnnotationScanPlugin` (`annotationPackages`), `ManagementApiPlugin` (`apiPort`) |
| `weaver-girl-bridge-micrometer` | Publishes agent counters to a Micrometer `MeterRegistry` | `MicrometerBridge` |
| `weaver-girl-bridge-aopalliance` | Adapts an AOP Alliance `MethodInterceptor` | `AopAllianceAdapter` |
| `weaver-girl-agent` | Java Agent entry point | `WeaverGirlAgent` |
| `weaver-girl-testkit` | Attaches the agent to a test JVM, runs the plugin lifecycle and weaves only the classes a test names | `PluginTestHarness` |
| `weaver-girl-sample` | Demonstration application | `SampleApplication`, `TargetService`, sample plugins and interceptors |

---

## ByteBuddy Transformation Pipeline

The core of the framework is the transformation pipeline that instruments target classes at class-load time:

```
JVM Class Load
    |
    v
WeaverTransformer  (registers type transformers via AgentBuilder)
    |
    |-- For each InterceptorDefinition:
    |       1. Build type matcher from ClassMatcher
    |       2. Check TypeExistenceChecker (skip EXACT_NAME if class not found)
    |       3. Build method matcher from MethodMatcher
    |       4. Register transformer: type(matcher).transform(Advice.to(InterceptAdvice))
    |
    v
InterceptAdvice  (ByteBuddy @Advice -- inlined into target method bytecode)
    |
    |-- @OnMethodEnter  (before the method body)
    |       1. Check SamplingController.shouldSample()
    |       2. Acquire MethodInvocation from pool
    |       3. Lookup interceptors via InterceptorHolder.getRegistry()
    |       4. For each matching interceptor:
    |              - Check InterceptorCircuitBreaker.shouldInvoke()
    |              - Call interceptor.before(invocation)
    |       5. If skipMethod() called -> return invocation (triggers skipOn)
    |          Otherwise -> release to pool, return null
    |
    |-- [original method body]  (skipped if skipOn triggered)
    |
    |-- @OnMethodExit  (after the method body, or on exception)
    |       1. Acquire MethodInvocation (reuse from Enter, or fresh from pool)
    |       2. Store return value or throwable
    |       3. For each matching interceptor:
    |              - Call interceptor.after(invocation) or interceptor.onException(invocation)
    |       4. Handle exception suppression (suppressException)
    |       5. Write back overridden return value (setReturnValue)
    |       6. Release MethodInvocation to pool
```

### ClassFileTransformer Integration

`WeaverTransformer.install(Instrumentation)` creates a ByteBuddy `AgentBuilder` pipeline that:

1. **Injects helper classes** into the Bootstrap ClassLoader via `BootstrapInjection` -- required so that `InterceptAdvice` and `InterceptorHolder` are visible when instrumenting `java.*`/`javax.*` classes
2. **Builds an exclusion matcher** that skips:
   - JDK internals (`sun.*`, `jdk.internal.*`, `com.sun.*`)
   - Agent's own packages (`com.github.cc11001100.weavergirl.*`, `com.github.cc11001100.weavergirl.shade.*`)
   - User-configured patterns from `WeaverConfig.excludedClasses`
3. **Optionally limits scope** via `WeaverConfig.onlyInterceptPackages` -- only classes in specified packages are instrumented
4. **Enforces `maxTransformations`** -- an atomic counter prevents runaway transformation of unexpectedly large applications
5. **Supports retransformation** -- `AgentBuilder.RedefinitionStrategy.RETRANSFORMATION` enables `retransformLoadedClasses()` for dynamic attach scenarios

### Type Matcher Construction

`WeaverTransformer.buildTypeMatcher()` maps each `ClassMatcher.MatchType` to a ByteBuddy `ElementMatcher`:

| MatchType | ByteBuddy Matcher |
|-----------|-------------------|
| `EXACT_NAME` | `named(pattern)` |
| `NAME_PATTERN` | `nameMatches(pattern)` |
| `ANNOTATION` | `isAnnotatedWith(named(pattern))` |
| `SUPER_CLASS` | `hasSuperType(named(pattern))` |
| `INTERFACE` | `hasSuperType(isInterface().and(named(pattern)))` |

### Method Matcher Construction

`WeaverTransformer.buildMethodMatcher()` always excludes bridge, synthetic, native, and abstract methods (these cannot or should not be instrumented by ByteBuddy Advice):

| MethodMatchType | ByteBuddy Matcher |
|-----------------|-------------------|
| `EXACT_NAME` | `named(pattern)` |
| `NAME_PATTERN` | `nameMatches(pattern)` |
| `ANY` | `isMethod()` |

All method matchers are ANDed with: `not(isBridge()).and(not(isSynthetic())).and(not(isNative())).and(not(isAbstract()))`

---

## Plugin Loading via SPI

Plugins are discovered and loaded using Java's `ServiceLoader` mechanism:

```
META-INF/services/com.github.cc11001100.weavergirl.api.plugin.WeaverPlugin
    |
    v
PluginLoader.loadPlugins(ClassLoader, InterceptorRegistry, Map<String,String>)
    |
    |-- 1. ServiceLoader.load(WeaverPlugin.class, classLoader)
    |-- 2. Select capability jars (plugins/ next to the agent jar):
    |       enabledPlugins allowlist, else everything not in disabledPlugins
    |-- 3. Filter discovered plugins by the same selection (disabledPlugins / enabledPlugins)
    |-- 3. PluginDependencyResolver.resolve() -- topological sort based on depends()
    |-- 4. For each plugin in dependency order:
    |       a. plugin.init(context)        -- pass DefaultPluginContext with config
    |       b. plugin.isEnabled(context)   -- false skips it (inactive, not a failure)
    |       c. plugin.registerInterceptors(registry)  -- optional; no-op default for capability plugins
    |-- 5. Log summary: succeeded / inactive / failed counts
```

Both kinds of capability jar are selected the same way. An instrumentation plugin jar is named
`weaver-girl-plugin-<capability>-<version>.jar` and a runtime capability jar
`weaver-girl-runtime-<capability>-<version>.jar`; the capability names the selection matches against
come from the jar's `META-INF/weavergirl-capabilities` marker. A jar that follows neither convention
is a third-party drop-in and is always loaded, which is how a user-supplied plugin joins in: drop it
in `plugins/` (or the directory named by the `plugins` agent argument) and declare its
`WeaverPlugin` in the SPI file.

The runtime capabilities are what used to be hardcoded in the agent entry point. They stay plugins so
the agent itself only boots the engine:

| Capability | Plugin | Enabled by |
|------------|--------|------------|
| JSON event log | `json-events` | `jsonEvents=true` |
| Prometheus metrics endpoint | `prometheus` | `metricsPort=<port>` |
| Config hot-reload | `config-watch` | `watch=true` with a `config` path |
| `@WeaveClass` scanning | `annotation-scan` | `annotationPackages=<pkgs>` |
| Management REST API | `management-api` | `apiPort=<port>` |

`init()` runs before `isEnabled()`, so each of these returns immediately when its key is absent. The
config-watch plugin reloads the YAML itself but cannot see the agent instance, so after a reload it
runs the retransform callback the agent installed on `InterceptorHolder` — that class is resolved
parent-first, which is the only way a plugin inside the isolated loader can reach the agent.

### The plugin contract

`weaver-girl-api` is the whole of the supported surface for a plugin that does not ship with the
agent. A third-party jar is loaded by a child-first loader that delegates to its parent only for
`com.github.cc11001100.weavergirl.api`, `com.github.cc11001100.weavergirl.annotation` and the JDK, so
anything in core that happens to be visible is visible by fall-through, not by contract, and may be a
different copy from the one the engine holds. The `weaver-girl-runtime-*` plugins are the exception
because they are loaded by the agent's own loader.

What a product plugin registers and what it reports both go through that module:

- **Join points.** `AbstractPlugin` builds three kinds of definition, each woven with its own advice.
  `intercept(...)` is a method hook on the standard advice. `interceptField(...)` sets
  `AdviceMode.FIELD` and an interceptor that implements `FieldInterceptor`, which is the combination
  `WeaverTransformer` and `FieldAdvice` both require — the advice picks interceptors with
  `instanceof FieldInterceptor` and then matches `fieldPointcuts()`, while the advice mode only
  decides that field advice is woven at all. `interceptCatch(...)` does the same for
  `AdviceMode.CATCH` and `CatchInterceptor`. A field or catch interceptor registered as a plain method
  definition is never called.
- **Ordering and compatibility.** `depends()` is resolved across jars, not inside each one, so a product plugin can depend on a built-in plugin. A jar that declares `plugin.apiVersion` in `META-INF/weaver-girl-plugin.properties` is refused when it is newer than `WeaverPlugin.API_VERSION`; jars that declare nothing load as before.
- **Configuration.** Every key is read through `PluginContext`, which resolves `weavergirl.plugin.<name>.<key>` (system property), then YAML `plugins.<name>.<key>`, then plain `<key>` system property, then the agent argument / top-level YAML launch map, then the caller's default. The YAML `plugins:` map is flattened into the namespaced keys before plugins start, and scalar top-level keys are passed through as capability options; a plugin cannot see another plugin's scoped key.
- **Logging.** `PluginContext.getLogger()` returns a logger bound to the agent's logging, because a plugin's own class loader would bind one to the application's instead.
- **Product services.** `Tracer`, `MetricRegistry`, `ExporterRegistry`/`DataExporter`,
  `TopologyGraph`, `AlertEngine`, `InterceptorEventPublisher` and `Taint`/`TaintPropagation`/
  `TaintFindings` are static and live in the API module, so a plugin reports spans, metrics, topology,
  alerts, events and taint findings without holding any core type. An exporter has to be `activate`d
  after `register`; registration alone does not send it events.

The developer guide at `docs/plugin-developer-guide.md` is the worked form of this contract.

### Plugin Lifecycle

Each plugin goes through these phases:

1. **Discovery** -- loaded via `ServiceLoader`
2. **Initialization** -- `init(PluginContext)` called once at startup; read config, allocate resources
3. **Enable check** -- `isEnabled(PluginContext)` decides whether the plugin actually activates; a plugin that returns false is inactive, not failed
4. **Registration** -- `registerInterceptors(InterceptorRegistry)` called once after the enable check; register all `InterceptorDefinition` instances. The interface default is a no-op, so a capability plugin that only starts a service does not override it
5. **Runtime** -- interceptors are invoked as matched methods are called; the plugin itself has no active role
6. **Destroy** -- `destroy()` called at agent shutdown; release resources, flush buffers

### ClassLoader Isolation

For production use, `PluginLoader.loadPluginsFromDirectory()` creates an isolated `PluginClassLoader` per plugin JAR using `PluginJarScanner`. This is child-first for plugin classes but parent-first for `weaver-girl-api` packages, ensuring plugins see the correct API types.

---

## Interceptor Lifecycle

Each `Interceptor` instance provides three callback hooks that are invoked around intercepted method calls:

```
Method call arrives
    |
    v
before(MethodInvocation)     <-- before the method body executes
    |
    |-- Can call invocation.skipMethod() to prevent original execution
    |-- Can call invocation.setReturnValue(value) to supply a return value
    |-- Can read invocation.getTargetClass(), getMethodName(), getArguments()
    |-- Can modify arguments via invocation.setArgument(index, value)
    |
    v
[original method body]       <-- skipped if skipMethod() was called
    |
    v
after(MethodInvocation)      <-- if the method completed normally
  OR
onException(MethodInvocation) <-- if the method threw an exception
    |
    |-- Can read invocation.getReturnValue() (in after)
    |-- Can read invocation.getThrowable() (in onException)
    |-- Can override return value via invocation.setReturnValue()
    |-- Can suppress exception via invocation.suppressException()
    v
Return to caller
```

**Key behaviors:**

- `after()` and `onException()` are mutually exclusive -- only one is called per invocation
- Multiple interceptors targeting the same method execute in priority order (lower value = higher priority = executed first)
- Interceptors must be thread-safe -- a single instance may be invoked concurrently
- Exceptions thrown by interceptors are caught by `InterceptAdvice` and logged; they never propagate to the target application
- `MethodInvocation` objects are pooled per-thread via `MethodInvocationPool` to reduce GC pressure

---

## Circuit Breaker for Fault Isolation

`InterceptorCircuitBreaker` prevents a failing interceptor from degrading application performance:

```
State: CLOSED (normal operation)
    |
    |-- Record failure via recordFailure()
    |-- If consecutive failures >= failureThreshold (default: 5):
    |
    v
State: OPEN (interceptor disabled)
    |
    |-- shouldInvoke() returns false
    |-- Interceptor is skipped in InterceptAdvice
    |-- After cooldownMillis (default: 60,000ms):
    |
    v
State: CLOSED (reset -- interceptor gets another chance)
    |
    |-- Failure counter reset to 0
```

The circuit breaker is integrated into `InterceptAdvice` via `InterceptorHolder.shouldInvoke()`, `InterceptorHolder.recordInterceptorSuccess()`, and `InterceptorHolder.recordInterceptorFailure()`.

Configuration via `WeaverConfig`:
- `circuitBreakerFailures` -- consecutive failures before opening (default: 5)
- `circuitBreakerCooldown` -- cooldown in milliseconds (default: 30000)

---

## Adaptive Sampling

`SamplingController` automatically reduces interception overhead under high load:

```
Normal load (invocations/second < threshold):
    |-- samplingRate = 1 (every invocation is intercepted)

High load (invocations/second > threshold):
    |-- samplingRate incremented (1 in N invocations is intercepted)
    |-- Maximum rate: maxRate (default: 100, i.e., 1 in 100)

Load decreases (invocations/second < threshold/2):
    |-- samplingRate decremented (sample more frequently again)
```

- Default threshold: 10,000 invocations/second
- Sampling check is the first thing `InterceptAdvice.onMethodEnter()` does, before any registry lookup
- Rate adaptation is performed by `SamplingMonitor` which runs a periodic background thread
- Configuration via `WeaverConfig.samplingThreshold`

---

## Event System

The structured event system enables integration with external observability tools:

```
Plugin emits event:
    InterceptorEventPublisher.getInstance().publish(event)
        |
        v
    InterceptorEventPublisher (singleton, thread-safe)
        |
        |-- Iterates all registered InterceptorEventListener instances
        |-- Calls listener.onEvent(event)
        |-- Exceptions in listeners are caught and logged (never block publisher)
        |
        v
    Built-in listeners:
        1. JsonEventListener   -- structured JSON to SLF4J (enable: jsonEvents=true)
        2. PrometheusExporter  -- Prometheus /metrics endpoint (enable: metricsPort=9400)
```

### InterceptorEvent Structure

```java
InterceptorEvent event = InterceptorEvent.builder()
    .type("slow-query")              // event type (e.g., "slow-query", "request-error")
    .plugin("jdbc")                  // source plugin name
    .className("PgStatement")        // intercepted class
    .methodName("execute")           // intercepted method
    .durationMs(2500)                // duration if applicable
    .attribute("sql", "SELECT ...")  // key-value attributes
    .build();
```

### Adding a Custom Event Listener

```java
InterceptorEventPublisher.getInstance().addListener(event -> {
    // Send to your metrics system, trace exporter, etc.
});
```

---

## Prometheus Metrics Export

`PrometheusExporter` is a built-in `InterceptorEventListener` that exposes metrics at an HTTP endpoint in Prometheus text exposition format.

**Enable:** `java -javaagent:weaver-girl-agent.jar=metricsPort=9400 -jar app.jar`

**Endpoint:** `http://localhost:9400/metrics`

**Exposed metrics:**

| Metric | Type | Description |
|--------|------|-------------|
| `weavergirl_slow_operations_total` | counter | Slow operations by plugin/type |
| `weavergirl_error_operations_total` | counter | Errors by plugin |
| `weavergirl_operation_duration_ms_sum` | counter | Cumulative duration by plugin |
| `weavergirl_operation_duration_ms_count` | counter | Operation count by plugin |

Example output:

```
# HELP weavergirl_slow_operations_total Total number of slow operations detected
# TYPE weavergirl_slow_operations_total counter
weavergirl_slow_operations_total{plugin="jdbc",type="slow-query"} 5
weavergirl_slow_operations_total{plugin="servlet",type="slow-request"} 2
```

The exporter uses `com.sun.net.httpserver.HttpServer` with zero external dependencies beyond the JDK.

---

## JMX Monitoring

`AgentMXBean` / `AgentMonitor` provide runtime visibility into the agent via JMX.

**MBean ObjectName:** `com.github.cc11001100.weavergirl:type=Agent`

**Access via JConsole / VisualVM / JMC:**

Connect to the JVM and navigate to the MBean under `com.github.cc11001100.weavergirl`.

**Programmatic access:**

```java
ObjectName name = new ObjectName("com.github.cc11001100.weavergirl:type=Agent");
long interceptCount = (Long) mBeanServer.getAttribute(name, "TotalInterceptCount");
```

**Exposed attributes:**

| Attribute | Type | Description |
|-----------|------|-------------|
| `TotalInterceptCount` | `long` | Total intercepted method calls since agent started |
| `TotalInterceptTimeMs` | `long` | Total time spent in interceptors (ms) |
| `AverageInterceptTimeUs` | `double` | Average overhead per intercepted call (microseconds) |
| `TransformedClassCount` | `int` | Number of classes that were bytecode-transformed |
| `InterceptorDefinitionCount` | `int` | Number of active interceptor definitions |
| `PluginCount` | `int` | Number of loaded plugins |
| `AgentVersion` | `String` | Agent version string |
| `PluginNames` | `String` | Names of all loaded plugins (comma-separated) |
| `ExcludedClassCount` | `int` | Number of classes excluded from transformation |

**Operations:**

| Operation | Description |
|-----------|-------------|
| `resetCounters()` | Reset all counters to zero |

There is also a legacy MBean `WeaverGirlMBean` that exposes `AgentStatus` data (transformation count, error count, interceptor invocation/error counts, active plugin count, uptime, status report). The `jmx` plugin registers it, and `jmx=false` leaves it unregistered.

---

## Configuration System

Weaver-Girl supports three layers of configuration, resolved in priority order:

### 1. YAML Configuration File

Passed via agent argument: `config=/path/to/weaver.yml`

```yaml
# Agent-level settings
samplingThreshold: 100
circuitBreakerFailures: 5
circuitBreakerCooldown: 30000
maxTransformations: 10000
logLevel: INFO
excludedClasses:
  - "com.example.internal.*"
onlyInterceptPackages:
  - "com.example"
disabledPlugins:
  - kafka
  - grpc

# YAML-based interceptor definitions
interceptors:
  - className: com.example.Service
    method: process
    before: com.example.interceptor.ProcessBeforeAdvice
```

`YamlConfigLoader` parses the file, validates it (checks required fields, validates regex patterns), and registers `InterceptorDefinition` instances with the registry.

### 2. System Properties

Override YAML values for plugin configuration. Properties prefixed with `weavergirl.plugin.<pluginName>.` take precedence:

```bash
-Dweavergirl.plugin.jdbc.slowQueryThreshold=500
-Dweavergirl.plugin.servlet.slowThreshold=3000
```

### 3. PluginContext

`PluginContext` merges the two sources and provides typed access methods:

```java
@Override
public void init(PluginContext context) {
    long threshold = context.getConfigLong("slowQueryThreshold", 1000);
    boolean enabled = context.getConfigBoolean("enabled", true);
    String mode = context.getConfigEnum("mode", "full", "full", "lite", "off");
}
```

### Config Hot-Reload

When `watch=true` is passed as an agent argument, `ConfigWatcher` monitors the YAML file for changes using `java.nio.file.WatchService`. On file change:

1. All YAML-registered interceptors (names starting with `yaml-`) are unregistered
2. The new config is loaded and interceptors are re-registered
3. A retransformation callback is triggered to update already-loaded classes
4. A 2-second debounce prevents duplicate reload events

---

## Enterprise Features (P46-P73)

### Dynamic Configuration Center

`DefaultDynamicConfigManager` provides runtime config changes with listeners, snapshots, and rollback:

- `ConfigChangeListener` receives `ConfigChangeEvent` on every set/remove
- `ConfigSnapshot` captures full config state at a point in time (max 50 snapshots)
- `rollback(snapshot)` restores a previous snapshot
- Audit trail: all changes logged with source and timestamp
- Thread-safe with `ConcurrentHashMap` and `CopyOnWriteArrayList`

### Plugin Hot Management

`PluginManager` / `DefaultPluginManager` manage plugin lifecycle at runtime:

- `PluginState` enum: `LOADED → ACTIVE → DISABLED → UNLOADED` with valid transition rules
- `PluginInfo` metadata: state, version, interceptor count, timestamps
- Disable/enable/unload with automatic interceptor coordination
- `PluginHealthRegistry`: tracks per-plugin health (HEALTHY/DEGRADED/UNHEALTHY)

### Multi-Tenant Isolation

`TenantContext` + `TenantConfigRegistry` provide per-tenant isolation:

- `TenantContext`: ThreadLocal tenant ID/group propagation
- `TenantSnapshot`: immutable capture/restore for cross-thread propagation
- `TenantConfig`: per-tenant sampling rate, max rate, threshold, custom properties

### Distributed Tracing

`Tracer` + `SpanContext` provide W3C-compatible distributed tracing:

- `SpanContext`: trace/span/parent IDs, sampled flag, baggage map
- `Tracer`: ThreadLocal span management, child span creation
- `TracingSnapshot`: cross-thread propagation (capture/restore)
- HTTP header injection/extraction (X-Trace-Id, X-Span-Id, X-Baggage-*)

### Data Exporter SPI

`DataExporter` interface for exporting intercepted data to external systems:

- `LoggingExporter`: structured JSON output to SLF4J
- `InMemoryExporter`: bounded ring buffer with type/recent filtering
- `ExporterRegistry`: register/activate/deactivate with multi-exporter fan-out

### Alerting Engine

`AlertEngine` evaluates rules against metrics and fires notifications:

- `AlertRule`: metric, operator (>, <, ==, >=, <=), threshold, severity
- `AlertChannel`: notification interface for custom delivery
- `AlertEvent`: structured alert with rule, metric value, timestamp

### Service Topology

`TopologyGraph` auto-builds a service dependency graph:

- `ServiceNode` / `ServiceEdge`: service and call edge models
- Thread-safe graph builder with call recording, edge aggregation
- Query APIs: outgoing/incoming edges, text export

### Metric Aggregation

`TimeWindowAggregator` + `MetricRegistry` provide sliding-window metrics:

- Percentiles: p50, p90, p95, p99
- Statistics: count, sum, min, max, avg
- `MetricSnapshot`: immutable snapshot with rate-per-second calculation

### Agent REST API

`AgentApiServer` exposes 7 HTTP endpoints for runtime management:

| Endpoint | Description |
|----------|-------------|
| GET /status | Agent status and uptime |
| GET /plugins | Loaded plugins and states |
| GET /topology | Service topology graph |
| GET /alerts | Recent alert events |
| GET /metrics | Metric aggregations |
| GET /diagnostics | Memory, hotspots, faults |

### Agent State Persistence

`AgentStateSnapshot` + `AgentStatePersister` enable state survival across JVM restarts:

- Properties-format persistence (human-readable, editable)
- Captures: interceptor counts, plugin states, config, metrics, health
- `AgentStatePersister`: periodic snapshot scheduler with configurable interval
- Graceful shutdown final snapshot
- `SnapshotListener` callbacks for onSnapshot/onRestore events

### Plugin Compatibility Checker

`PluginCompatibilityChecker` validates plugins at load time:

- Agent version check: `minimumAgentVersion` in plugin metadata
- Duplicate detection: same-name plugins with same or older version rejected
- Dependency verification: all declared `depends` checked against available plugins
- Version format validation: semver-like format (X.Y.Z with optional suffixes)
- Auto-disable: incompatible plugins automatically disabled (configurable)
- `CompatibilityReport`: structured result with warnings, errors, autoDisabled flag

### Lock-Free Object Pool

`LockFreeObjectPool<T>` reduces GC pressure on hot paths:

- `ConcurrentLinkedQueue`-based: no locks on borrow/release
- Bounded pool size with eviction
- Statistics: hit/miss/borrow/return/eviction counts and hit rate

### Internationalization (i18n)

`AgentMessages` provides lightweight message externalization:

- ResourceBundle-based with UTF-8 encoding
- Parameterized messages: `{0}`, `{1}`, `{2}`... positional arguments
- Multi-locale caching with English fallback
- 50+ English messages, complete Chinese (zh_CN) translation
- Auto-detection from system properties

---

## Self-Protection

The agent excludes its own packages from instrumentation to prevent `ClassCircularityError`:

- `com.github.cc11001100.weavergirl.core.*`
- `com.github.cc11001100.weavergirl.agent.*`
- `com.github.cc11001100.weavergirl.shade.*`
- `net.bytebuddy.*`
- `sun.*`, `jdk.internal.*`, `com.sun.*`

User-configured exclusions from `WeaverConfig.excludedClasses` are also applied.

`InterceptAdvice` catches `Throwable` (not just `Exception`) in all callback paths to prevent `OutOfMemoryError` and `StackOverflowError` in plugins from crashing the target application. If an interceptor calls `skipMethod()` and then throws, the skip flag is reset so the original method executes normally.

---

## Thread Safety Model

| Component | Thread Safety Mechanism |
|-----------|------------------------|
| `DefaultInterceptorRegistry` | Volatile index swap, synchronized writes, `CopyOnWriteArrayList` for definitions |
| `InterceptorHolder` | `volatile` registry reference, static `InterceptorCircuitBreaker` |
| `InterceptorCircuitBreaker` | `ConcurrentHashMap<String, State>`, per-interceptor synchronization |
| `SamplingController` | `AtomicInteger` for rate, `AtomicLong` for counter |
| `AgentStatus` | `AtomicLong` counters, `CopyOnWriteArrayList` |
| `AgentMonitor` | `AtomicLong`/`AtomicInteger` counters, `volatile` strings |
| `InterceptorEventPublisher` | `CopyOnWriteArrayList` for listeners |
| `MethodInvocationPool` | `ThreadLocal` per-thread pooling |
| `ThreadContext` / context propagation wrappers | `ThreadLocal` with `remove()` plus snapshot/scope restore around async tasks |
| `PrometheusExporter` | `ConcurrentHashMap` with `AtomicLong` counters |
| `ConfigWatcher` | `AtomicBoolean` for running state |
| `DefaultDynamicConfigManager` | `ConcurrentHashMap` for config, `CopyOnWriteArrayList` for listeners/snapshots |
| `DefaultPluginManager` | `ConcurrentHashMap` for plugin states |
| `TenantConfigRegistry` | `ConcurrentHashMap` for tenant configs |
| `TopologyGraph` | `ConcurrentHashMap` for nodes/edges |
| `MetricRegistry` | `ConcurrentHashMap` for named aggregators |
| `AlertEngine` | `CopyOnWriteArrayList` for rules/channels |
| `ExporterRegistry` | `ConcurrentHashMap` for exporters |
| `PluginCompatibilityChecker` | `ConcurrentHashMap` for loaded versions, `CopyOnWriteArrayList` for reports |
| `LockFreeObjectPool` | `ConcurrentLinkedQueue` + `AtomicInteger` for pool size and stats |
| `AgentMessages` | `ConcurrentHashMap` for locale→bundle cache |

---

## Performance Characteristics

- **Zero dispatch overhead** -- `InterceptAdvice` is inlined into target methods by ByteBuddy; no reflection or proxy calls at runtime
- **Object pooling** -- `MethodInvocation` instances are pooled per-thread via `MethodInvocationPool`, reducing GC pressure
- **Fast lookup** -- `DefaultInterceptorRegistry` uses a `ConcurrentHashMap` index for O(1) exact-name lookups; pattern/annotation/superclass/interface matchers scan all definitions but are checked only for non-EXACT_NAME matchers
- **Circuit breaker** -- prevents wasted CPU on repeatedly failing interceptors
- **Adaptive sampling** -- automatically skips interception under high load
- **Transformation cap** -- `maxTransformations` (default: 10,000) limits total bytecode modifications
- **Conditional enhancement** -- `TypeExistenceChecker` skips `EXACT_NAME` interceptors when the target class is not on the classpath, avoiding unnecessary transformer registration
