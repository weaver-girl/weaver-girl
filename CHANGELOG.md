# Changelog

All notable changes to the Weaver-Girl project, organized by development phase.

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
