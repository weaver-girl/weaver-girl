# Weaver-Girl

[![CI](https://github.com/weaver-girl/weaver-girl/actions/workflows/ci.yml/badge.svg)](https://github.com/weaver-girl/weaver-girl/actions/workflows/ci.yml)
[![Agent Smoke](https://github.com/weaver-girl/weaver-girl/actions/workflows/agent-smoke.yml/badge.svg)](https://github.com/weaver-girl/weaver-girl/actions/workflows/agent-smoke.yml)
[![License: MIT](https://img.shields.io/badge/License-MIT-yellow.svg)](LICENSE)
[![Java 8+](https://img.shields.io/badge/Java-8%2B-blue.svg)](docs/versioning-and-compatibility.md)
[![Website](https://img.shields.io/badge/website-weaver--girl.github.io-blue)](https://weaver-girl.github.io/)

A modular Java bytecode instrumentation framework built on ByteBuddy, inspired by SkyWalking and OpenTelemetry Java Agent.

**Weaver-Girl is a hook base, not a turnkey APM product.** It provides the
interception engine, plugin isolation and shared runtime services; an APM agent,
an IAST scanner, a RASP shield, an audit trail or a resilience layer is a plugin
built on top. If you want a complete APM platform today, use SkyWalking or
OpenTelemetry plus a backend. If you are building your own product on a stable
hook layer, this is the base. See [`docs/positioning.md`](docs/positioning.md).

> **Status:** pre-1.0. The first release is being prepared; artifacts publish to
> Maven Central when `v1.0.0` is tagged. Versioning and compatibility are
> documented in
> [`docs/versioning-and-compatibility.md`](docs/versioning-and-compatibility.md).

## Quick Start (30 seconds)

```bash
# 1. Build the agent (also produces target/plugins/ next to the agent jar)
mvn clean package -DskipTests

# 2. Attach to any Java application. Run from the repository or use the release
#    distribution (agent jar + plugins/ directory unpacked side by side).
java -javaagent:weaver-girl-agent/target/weaver-girl-agent-1.0.0-SNAPSHOT.jar -jar your-app.jar

# That's it — 126 built-in instrumentation plugins auto-discover and intercept:
# Servlet, Spring, JDBC, Redis, Kafka, gRPC, MongoDB, HttpClient, Dubbo, MyBatis,
# RocketMQ, Druid, Feign, and more
```

The agent starts immediately with all built-in plugins active. No config file required.

> **Packaging:** the agent jar contains no built-in plugin classes. It loads one jar per
> capability from the `plugins/` directory next to it, so a jar-only copy instruments nothing.
> Releases ship `weaver-girl-agent-<version>-dist.zip`, which contains both. If you embed the
> agent in an image, copy `plugins/` alongside the jar (see the `Dockerfile`).

## With Configuration

```bash
# Use a YAML config to customize interception
java -javaagent:weaver-girl-agent.jar=config=/path/to/weaver.yml -jar your-app.jar

# Enable hot reload (config changes applied without restart)
java -javaagent:weaver-girl-agent.jar=config=/path/to/weaver.yml,watch=true -jar your-app.jar

# Disable specific plugins
java -javaagent:weaver-girl-agent.jar=config=/weaver.yml,disabledPlugins=servlet,kafka -jar your-app.jar
# 只加载列出的能力（jar 按能力拆分，未启用的不会被加载）：
java -javaagent:weaver-girl-agent.jar=enabledPlugins=jdbc,servlet -jar your-app.jar
```

**Minimal weaver.yml:**
```yaml
# Disable specific built-in plugins
disabledPlugins:
  - servlet

# Only intercept your own code
onlyInterceptPackages:
  - com.myapp

# Custom interceptors (advice classes must implement Interceptor interface)
interceptors:
  - className: com.myapp.service.UserService
    method: createUser
    before: com.myapp.interceptor.AuditAdvice
```

→ **Full config reference:** [docs/yaml-config-reference.md](docs/yaml-config-reference.md)

→ **Troubleshooting:** [docs/troubleshooting.md](docs/troubleshooting.md)

## Features

- **126 Built-in Plugins**: Servlet, Spring, JDBC, Redis, Kafka, gRPC, MongoDB, HttpClient, HikariCP, MethodTiming, TraceCorrelation, ExceptionMonitor, Logging, OkHttp, RabbitMQ, Elasticsearch, Dubbo, MyBatis, RocketMQ, Druid, Feign, Caffeine, Netty, Tomcat/Jetty/Undertow, Quartz/XXL-Job, and more — one jar per capability, loaded only when enabled
- **Three Hook Modes**: Programmatic API, Annotation-driven, YAML configuration
- **Plugin System**: SPI-based plugin discovery with dependency resolution, ClassLoader isolation, conditional enable/disable, state management, and hot loading. A third-party product (an APM agent, an IAST scanner) extends the platform through a plugin that codes only against `weaver-girl-api`: method, field and catch interception, plus the shared tracing, metrics, exporter, topology, alerting and taint services. See the [Plugin Developer Guide](docs/plugin-developer-guide.md).
- **ByteBuddy Advice**: Zero-allocation inlined method interception with skip & return-value override
- **Bootstrap Class Injection**: Intercept `java.*` and `javax.*` classes
- **Dynamic Attach**: Runtime attachment via `agentmain` with retransformation
- **Hot Reload**: YAML config changes applied without restart
- **Circuit Breaker**: Automatic disable of failing (or consistently slow) interceptors; `circuitBreakerThreshold` / `circuitBreakerCooldownMs` retune the live breaker, including at runtime
- **Configurable Per-Call Overhead**: `telemetry` / `returnSnapshot` / `mdc` / `pluginMetrics` / `circuitBreaker` switches (agent arg, YAML key, `-Dweavergirl.<name>.enabled`, or runtime) strip the observation work from the hot path while leaving interception intact — all off takes a matched call from ~335 ns to ~88 ns
- **Adaptive Sampling**: A monitor raises the sampling rate when the invocation rate exceeds `samplingThreshold`; `adaptiveSampling=false` turns the thread off
- **Advanced Sampling**: SPI strategy interface with fixed-rate and probabilistic strategies
- **Dynamic Config Center**: Runtime config changes with listeners, snapshots, rollback, and audit trail
- **Multi-tenant Isolation**: Per-tenant config, sampling, and context propagation
- **Distributed Tracing**: SpanContext, cross-thread/process propagation, baggage
- **Service Topology**: Auto-built service dependency graph from call data
- **Alerting Engine**: Rule-based alerting with configurable channels
- **Data Exporters**: SPI for exporting to logging, in-memory, OTLP, or custom backends
- **Metric Aggregation**: Sliding time windows with p50/p95/p99 percentiles
- **Security Audit**: Allow/deny policy engine with audit logging
- **Self Diagnostics**: Memory trends, interceptor hotspots, fault detection
- **Runtime Compatibility**: Auto-detect Spring Boot, Quarkus, Virtual Threads, GraalVM
- **REST API**: Agent status, plugins, topology, alerts, metrics, diagnostics endpoints
- **Prometheus Metrics**: Built-in metrics endpoint with slow-ops, errors, and duration tracking
- **JMX Diagnostics**: Monitor agent status via JMX MBean
- **Health Check**: Liveness (`/health`) and readiness (`/ready`) HTTP endpoints
- **OpenTelemetry Bridge**: Convert intercepted events to OTel-compatible spans
- **W3C Trace Context**: Full traceparent/tracestate propagation across services
- **Config Validation**: Schema-based config validation with error/warning reporting
- **Plugin Health**: Runtime health monitoring for all loaded plugins
- **State Persistence**: Periodic agent-state snapshots to a directory (`stateFile`), with a final snapshot on shutdown; a snapshot from a previous run is logged at startup
- **Quality Gates**: JaCoCo (≥80% instructions / ≥70% branches on the engine and runtime modules), SpotBugs (High), Checkstyle enforced in build

## Programmatic API

```java
// Inside your own WeaverPlugin implementation (extends AbstractPlugin):
@Override
public void registerInterceptors(InterceptorRegistry registry) {
    // The builder produces a definition; the registry is what registers it.
    registry.register(
        intercept("com.example.Service")
            .method("process")
            .before(inv -> log.debug("Before: {}", inv.getMethodName()))
            .after(inv -> log.debug("After: {}", inv.getMethodName()))
            .build());
}
```

## Annotation Mode

```java
@WeaveClass(target = "com.example.Service")
public class MyInterceptor {

    @Before(value = "process")
    public static void beforeProcess(MethodInvocation invocation) {
        // Called before Service.process()
    }

    @After(value = "process")
    public static void afterProcess(MethodInvocation invocation) {
        // Called after Service.process()
    }
}
```

## Built-in Plugins

126 instrumentation plugins ship as individual jars in `weaver-girl-plugins/` (plus 14 runtime
capability jars in `weaver-girl-runtime-plugins/`). The table below covers the most common ones;
the full list is the module directory itself.

| Plugin | Target | Key Config |
|--------|--------|------------|
| `servlet` | javax.servlet | `slowRequestThreshold` (ms) |
| `spring` | Spring Framework | `slowThreshold` (ms) |
| `jdbc` | java.sql | `slowQueryThreshold` (ms), `logSql` |
| `redis` | Jedis / Lettuce | `slowCommandThreshold` (ms) |
| `httpclient` | Apache HttpClient | `slowThreshold` (ms) |
| `grpc` | io.grpc | `slowThreshold` (ms) |
| `kafka` | Apache Kafka | `slowThreshold` (ms) |
| `mongo` | MongoDB Driver | `slowThreshold` (ms) |
| `hikari` | HikariCP | `leakThresholdMs`, `trackAcquisition` |
| `okhttp` | OkHttp 3.x/4.x | `slowThreshold` (ms), `trackConnectionPool` |
| `rabbitmq` | RabbitMQ Client | `slowPublishThreshold` (ms), `slowConsumeThreshold` (ms) |
| `elasticsearch` | ES REST/Java Client | `slowQueryThreshold` (ms), `trackBulkSize` |
| `method-timing` | Any method | `slowThreshold` (ms) |
| `trace-correlation` | HTTP headers | `headerName` |
| `exception-monitor` | Any exception | `maxStackTraceLength` |
| `logging` | SLF4J / Log4j2 | `logLevel` |

## Examples

[`weaver-girl-examples/`](weaver-girl-examples/) holds standalone projects that use the **released**
artifacts — no reactor build, no weaver-girl dependency at compile time. Start with
[`spring-boot-jdbc`](weaver-girl-examples/spring-boot-jdbc/): a Spring Boot 3.3 application over H2,
attached with `-javaagent`, proving servlet / Spring MVC / JDBC interception end to end.

## Running the Sample

```bash
./run-sample.sh
```

The sample demonstrates all three hook modes on one call — Programmatic, Annotation, and YAML —
and prints a `--- hook demo ---` block at startup proving it:

```
--- hook demo: calling com.github.cc11001100.weavergirl.sample.app.TargetService ---
[LOG] Before greet: [hooks]            # plugin in META-INF/services (programmatic)
[YAML] Before greet args=[hooks]       # weaver-sample.yml
[ANNOTATION] Before greet: [hooks]     # @WeaveClass interceptor, found by annotationPackages
```

`run-sample.sh` passes the sample's `weaver-sample.yml`, which is what turns on the annotation
scan and the YAML interceptors; the programmatic plugin ships inside the sample jar and needs no
config. Without that file only the programmatic hook fires, so the three lines together are the
check.

### Drop-in plugin demo

`scripts/run-audit-demo.sh` builds a plugin that the application never references and runs the same program twice. With the agent and the plugin jar in `plugins/` it exits 0 and prints an audit trail (card numbers masked, charged cents summed from the metric registry, the rejected charge left out). Without the agent it exits 2 and the trail is empty — the payment service ran either way, the difference is the plugin being woven in.

```bash
scripts/run-audit-demo.sh
```

### Scenarios where the plugin changes what the program does

`scripts/run-scenario-demos.sh` runs two small applications that have no protection of their own, each with and without its plugin. Neither application references the plugin; the jar is found because it sits in `plugins/`.

| Scenario | Without the plugin | With the plugin |
|---|---|---|
| Support desk (`com.example.support.RedactionDemo`) | The text an agent pastes into chat carries the customer's card number and phone number | Those come back masked (`411111******1234`, `138****5678`), the stored ticket is untouched, and each catch is counted in `redaction.secrets.masked` |
| Payment gateway (`com.example.gateway.ResilienceDemo`) | A provider that fails its first four calls is hit by all six charges | After three failures the circuit opens and the next charge is answered with `queued:` without calling the provider, counted in `resilience.charges.shed` |

Both exit 0 under the agent and 2 without it.

```bash
scripts/run-scenario-demos.sh
```

### Join points a method interceptor cannot express

Three more demos cover the hook types beyond method entry/exit. Each runs the same program twice:
with the agent it exits 0, without it exits 2.

| Demo | What it exercises | Without the plugin | With it |
|---|---|---|---|
| `scripts/run-field-catch-demo.sh` | Field read/write hooks and a failed-exit (catch) hook | `describe()` prints `alice/hunter2`, an empty `setPassword("")` wipes the field, and the export throws | Every password read comes back `alice/********`, the empty write is rejected and the old value stays, and the failed export returns `unavailable: …` |
| `scripts/run-introduction-demo.sh` | Introduction (mixin): add an interface to a class the plugin does not own | `getInterfaces()` is `[]` and `instanceof InvoiceCsv` is false | The class implements `InvoiceCsv` and `toCsvRow()` renders `alice,2599` through the plugin's delegate |
| `scripts/run-taint-demo.sh` | Call-site tracking plus taint (IAST) | The query is concatenated from the raw parameter and nothing reports it | The call site (`InjectionDemo.main:41`) is recorded, and the SQL sink reports the finding with source `http.parameter:q` |

An introduction is applied when the target type is **first defined**; the JVM does not let an
already-loaded class gain an interface, so the demo asks the object before using it.

```bash
scripts/run-field-catch-demo.sh
scripts/run-introduction-demo.sh
scripts/run-taint-demo.sh
```

## Observability

```bash
# Enable Prometheus metrics + health check
java -javaagent:agent.jar=metricsPort=9400,healthPort=9401 -jar app.jar

# Check agent health
curl http://localhost:9401/health    # {"status":"UP","agent":"weaver-girl","uptimeSeconds":3600}
curl http://localhost:9401/ready     # {"status":"READY","interceptorCount":12}

# Scrape metrics
curl http://localhost:9400/metrics
```

### Monitoring Stack

- **Traces**: W3C `traceparent`/`baggage` propagation, span attributes, hand-built child spans for
  nested servlet/filter interceptions, and batch export as OTLP/HTTP JSON with `service.name`
  resource attributes
- **Prometheus**: Built-in `/metrics` endpoint — event counters (total/slow/error/duration),
  agent self-metrics, JVM metrics and `MetricRegistry` business percentiles
- **OTLP logs**: Interception events to a collector's `/v1/logs` with `otlpLogsEndpoint`
- **JMX**: `com.github.cc11001100.weavergirl:type=Agent` MBean
- **Health**: Kubernetes liveness/readiness probes via `/health` and `/ready`
- **JSON Events**: Structured event output with `jsonEvents=true`

Design details, metric catalog, PromQL examples and collector configuration are in the
[Observability Guide](docs/observability.md).

`metricsPort`, `jsonEvents`, `apiPort`, `annotationPackages` and config hot-reload (`watch=true`) are plugins (`weaver-girl-runtime-*`). Each ships as its own jar and only activates when its config key is set, so a distribution can leave out the capabilities it does not use. A custom plugin is added the same way: drop its jar in the `plugins/` directory next to the agent.

## Deployment

### Kubernetes (Helm)

```bash
helm install weaver-girl ./deploy/helm/weaver-girl \
  --set config.metricsPort=9400 \
  --set config.healthPort=9401 \
  --set serviceMonitor.enabled=true
```

### Docker

```bash
docker-compose up
```

→ **Full deployment guide:** [docs/runbook.md](docs/runbook.md)

## Architecture

```
+--------------------------------------------------+
|                  Target JVM                       |
|  +----------------------------------------------+|
|  |          Weaver-Girl Agent (premain)          ||
|  |  +------------+  +---------------+  +------+ ||
|  |  | Weaver     |  | PluginLoader  |  | YAML | ||
|  |  | Transformer|  | (SPI+Isolated)|  | Config| ||
|  |  +-----+------+  +-------+-------+  +--+---+ ||
|  |        |                  |             |     ||
|  |  +-----v------------------v-------------v---+ ||
|  |  |       DefaultInterceptorRegistry         | ||
|  |  +------------------+-----------------------+ ||
|  |                     |                         ||
|  |  +------------------v-----------------------+ ||
|  |  |          InterceptAdvice                 | ||
|  |  |    (ByteBuddy @Advice inlined)           | ||
|  |  +------------------------------------------+ ||
|  +----------------------------------------------+|
+--------------------------------------------------+
```

## Modules

| Module | Description |
|--------|-------------|
| `weaver-girl-api` | Plugin SDK — interfaces and value objects |
| `weaver-girl-core` | Engine — ByteBuddy transformer, registry, config |
| `weaver-girl-annotation` | Declarative annotations for interceptors |
| `weaver-girl-plugin-<name>` | One built-in instrumentation plugin per module (126 modules) |
| `weaver-girl-runtime-<name>` | One optional runtime capability per module (14 modules: metrics, health, OTLP, JMX, …) |
| `weaver-girl-bridge-micrometer` | Optional Micrometer metrics bridge |
| `weaver-girl-bridge-aopalliance` | Optional AOP Alliance interceptor adapter |
| `weaver-girl-agent` | Agent entry point (premain/agentmain) |
| `weaver-girl-testkit` | In-process test harness for product plugins (test scope only) |
| `weaver-girl-sample` | Sample application demonstrating all modes |

## Requirements

- Java 1.8+
- Maven 3.6+

## Website

The project site (React + Vite + Ant Design) lives in its own repository,
[`weaver-girl/weaver-girl.github.io`](https://github.com/weaver-girl/weaver-girl.github.io), and is
live at **https://weaver-girl.github.io/**. It is built and deployed by that repository's Pages
workflow; this repository carries no site code. The old `/weaver-girl/` URL redirects to the new
domain. The [cases page](https://weaver-girl.github.io/cases) walks through the three runnable demos
above with their real before/after output.

```bash
git clone https://github.com/weaver-girl/weaver-girl.github.io.git
cd weaver-girl.github.io && npm ci && npm run dev   # local preview
```

## Documentation

- [Positioning](docs/positioning.md) — what this project is, and what it is not
- [Architecture](docs/ARCHITECTURE.md)
- [Plugin Developer Guide](docs/plugin-developer-guide.md)
- [Plugin Template](https://github.com/weaver-girl/weaver-girl-plugin-template) — start a third-party plugin
- [Versioning and Compatibility](docs/versioning-and-compatibility.md)
- [Roadmap](ROADMAP.md)
- [YAML Config Reference](docs/yaml-config-reference.md)
- [Observability Guide](docs/observability.md)
- [Extension Point Guide](docs/extension-point-guide.md)
- [Troubleshooting](docs/troubleshooting.md)
- [Operations Runbook](docs/runbook.md)
- [OTel Collector Example](docs/otel-collector-example.yaml)
- [Contributing](CONTRIBUTING.md)
- [Code of Conduct](CODE_OF_CONDUCT.md)
- [Security Policy](SECURITY.md)
- [Changelog](CHANGELOG.md)

## License

MIT License — see [LICENSE](LICENSE) for details.

## Contact

Use the [GitHub issues](https://github.com/weaver-girl/weaver-girl/issues) for bugs in the
repository itself. For a custom plugin, help taking the base to a product, or hands-on
troubleshooting, the maintainer's WeChat is **CC11001100-weixin**, and the QR code is on the
[contact section of the site](https://weaver-girl.github.io/#contact).

