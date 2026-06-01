# Architecture

## Overview

Weaver-Girl is a Java bytecode instrumentation framework built on [ByteBuddy](https://bytebuddy.net/). It intercepts method calls at the bytecode level, allowing plugins to observe and modify application behavior without source code changes. The framework is designed for production use with fault isolation, adaptive sampling, and self-monitoring built in.

---

## Module Structure

The project is organized as a multi-module Maven build with 6 modules plus the parent POM:

```
weaver-girl/                          (parent POM, packaging=pom)
├── weaver-girl-api/                  Plugin SDK — interfaces, matchers, context, event system
├── weaver-girl-core/                 Core engine — transformer, registry, config, circuit breaker, sampling
├── weaver-girl-annotation/           Declarative annotations — @WeaveClass, @Before, @After, @Around
├── weaver-girl-plugins/              12 built-in instrumentation plugins
├── weaver-girl-agent/                Agent entry point — premain/agentmain, YAML config loading
└── weaver-girl-sample/               Sample application — embedded Jetty + H2 demo
```

### Module Dependency Graph

```
                    weaver-girl-api  (Plugin SDK)
                    /      |      \
                   /       |       \
    weaver-girl-annotation  |   weaver-girl-plugins
                          |
                   weaver-girl-core
                          |
                   weaver-girl-agent
                          |
                   weaver-girl-sample
```

Key dependency rules:

- **`weaver-girl-api`** has no internal dependencies -- it is the pure Plugin SDK that third-party plugins depend on
- **`weaver-girl-core`** depends on `weaver-girl-api` -- it provides the engine implementation
- **`weaver-girl-plugins`** depends on `weaver-girl-api` only -- plugins never depend on core internals
- **`weaver-girl-annotation`** depends on `weaver-girl-api` -- annotation definitions reference API types
- **`weaver-girl-agent`** depends on `weaver-girl-core` -- it wires everything together at startup
- **`weaver-girl-sample`** depends on `weaver-girl-api` and `weaver-girl-annotation` -- demonstrates all three hook modes

### Module Details

| Module | Purpose | Key Classes |
|--------|---------|-------------|
| `weaver-girl-api` | Plugin SDK: interfaces and value objects that plugin developers code against | `WeaverPlugin`, `AbstractPlugin`, `Interceptor`, `MethodInvocation`, `ClassMatcher`, `MethodMatcher`, `Pointcut`, `InterceptorDefinition`, `InterceptorRegistry`, `PluginContext`, `ThreadContext`, `InterceptorEvent`, `InterceptorEventPublisher`, `InterceptorEventListener` |
| `weaver-girl-core` | Engine implementation: bytecode transformation, registry, config, fault isolation | `WeaverGirl`, `InterceptAdvice`, `WeaverTransformer`, `DefaultInterceptorRegistry`, `PluginLoader`, `YamlConfigLoader`, `ConfigWatcher`, `InterceptorCircuitBreaker`, `SamplingController`, `PrometheusExporter`, `AgentMXBean`/`AgentMonitor` |
| `weaver-girl-annotation` | Declarative annotation-driven hook mode | `@WeaveClass`, `@Before`, `@After`, `@Around` |
| `weaver-girl-plugins` | 12 built-in instrumentation plugins | `ServletPlugin`, `JdbcPlugin`, `SpringPlugin`, `RedisPlugin`, `KafkaPlugin`, `GrpcPlugin`, `MongoPlugin`, `HttpClientPlugin`, `MethodTimingPlugin`, `TraceCorrelationPlugin`, `ExceptionMonitorPlugin`, `LoggingPlugin` |
| `weaver-girl-agent` | Java Agent entry point | `WeaverGirlAgent` |
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
    |-- 2. Filter out disabled plugins (from WeaverConfig.disabledPlugins)
    |-- 3. PluginDependencyResolver.resolve() -- topological sort based on depends()
    |-- 4. For each plugin in dependency order:
    |       a. plugin.init(context)        -- pass DefaultPluginContext with config
    |       b. plugin.registerInterceptors(registry)  -- register InterceptorDefinitions
    |-- 5. Log summary: succeeded / failed counts
```

### Plugin Lifecycle

Each plugin goes through these phases:

1. **Discovery** -- loaded via `ServiceLoader`
2. **Initialization** -- `init(PluginContext)` called once at startup; read config, allocate resources
3. **Registration** -- `registerInterceptors(InterceptorRegistry)` called once after init; register all `InterceptorDefinition` instances
4. **Runtime** -- interceptors are invoked as matched methods are called; the plugin itself has no active role
5. **Destroy** -- `destroy()` called at agent shutdown; release resources, flush buffers

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

There is also a legacy MBean `WeaverGirlMBean` registered via `JmxRegistrar` that exposes `AgentStatus` data (transformation count, error count, interceptor invocation/error counts, active plugin count, uptime, status report).

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
| `ThreadContext` | `ThreadLocal` with `remove()` to prevent memory leaks |
| `PrometheusExporter` | `ConcurrentHashMap` with `AtomicLong` counters |
| `ConfigWatcher` | `AtomicBoolean` for running state |

---

## Performance Characteristics

- **Zero dispatch overhead** -- `InterceptAdvice` is inlined into target methods by ByteBuddy; no reflection or proxy calls at runtime
- **Object pooling** -- `MethodInvocation` instances are pooled per-thread via `MethodInvocationPool`, reducing GC pressure
- **Fast lookup** -- `DefaultInterceptorRegistry` uses a `ConcurrentHashMap` index for O(1) exact-name lookups; pattern/annotation/superclass/interface matchers scan all definitions but are checked only for non-EXACT_NAME matchers
- **Circuit breaker** -- prevents wasted CPU on repeatedly failing interceptors
- **Adaptive sampling** -- automatically skips interception under high load
- **Transformation cap** -- `maxTransformations` (default: 10,000) limits total bytecode modifications
- **Conditional enhancement** -- `TypeExistenceChecker` skips `EXACT_NAME` interceptors when the target class is not on the classpath, avoiding unnecessary transformer registration
