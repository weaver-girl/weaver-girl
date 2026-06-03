# Changelog

All notable changes to the Weaver-Girl project, organized by development phase.

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
