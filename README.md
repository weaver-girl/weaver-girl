# Weaver-Girl

A modular Java bytecode instrumentation framework built on ByteBuddy, inspired by SkyWalking and OpenTelemetry Java Agent.

## Quick Start (30 seconds)

```bash
# 1. Build the agent
mvn clean package -DskipTests

# 2. Attach to any Java application
java -javaagent:weaver-girl-agent/target/weaver-girl-agent-1.0.0-SNAPSHOT.jar -jar your-app.jar

# That's it — 12 built-in plugins auto-discover and intercept:
# Servlet, Spring, JDBC, Redis, Kafka, gRPC, MongoDB, HttpClient, and more
```

The agent starts immediately with all built-in plugins active. No config file required.

## With Configuration

```bash
# Use a YAML config to customize interception
java -javaagent:weaver-girl-agent.jar=config=/path/to/weaver.yml -jar your-app.jar

# Enable hot reload (config changes applied without restart)
java -javaagent:weaver-girl-agent.jar=config=/path/to/weaver.yml,watch=true -jar your-app.jar

# Disable specific plugins
java -javaagent:weaver-girl-agent.jar=config=/weaver.yml,disabledPlugins=servlet,kafka -jar your-app.jar
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

- **16 Built-in Plugins**: Servlet, Spring, JDBC, Redis, Kafka, gRPC, MongoDB, HttpClient, HikariCP, MethodTiming, TraceCorrelation, ExceptionMonitor, Logging, OkHttp, RabbitMQ, Elasticsearch
- **Three Hook Modes**: Programmatic API, Annotation-driven, YAML configuration
- **Plugin System**: SPI-based plugin discovery with dependency resolution, ClassLoader isolation, conditional enable/disable, state management, and hot loading
- **ByteBuddy Advice**: Zero-allocation inlined method interception with skip & return-value override
- **Bootstrap Class Injection**: Intercept `java.*` and `javax.*` classes
- **Dynamic Attach**: Runtime attachment via `agentmain` with retransformation
- **Hot Reload**: YAML config changes applied without restart
- **Circuit Breaker**: Automatic disable of failing interceptors (configurable threshold & cooldown)
- **Adaptive Sampling**: Reduce overhead under high load (configurable rate & threshold)
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
- **State Persistence**: Agent state snapshot/restore across JVM restarts
- **Quality Gates**: JaCoCo (80%+ coverage), SpotBugs, CheckStyle enforced in build

## Programmatic API

```java
// Inside your own WeaverPlugin implementation:
@Override
public void registerInterceptors(InterceptorRegistry registry) {
    intercept("com.example.Service")
        .method("process")
        .before(inv -> log.debug("Before: {}", inv.getMethodName()))
        .after(inv -> log.debug("After: {}", inv.getMethodName()))
        .register(registry);
}
```

## Annotation Mode

```java
@WeaveClass(className = "com.example.Service")
public class MyInterceptor {

    @Before(methodName = "process")
    public static void beforeProcess(MethodInvocation invocation) {
        // Called before Service.process()
    }

    @After(methodName = "process")
    public static void afterProcess(MethodInvocation invocation) {
        // Called after Service.process()
    }
}
```

## Built-in Plugins

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
| `timing` | Any method | `slowThreshold` (ms) |
| `trace-correlation` | HTTP headers | `headerName` |
| `exception` | Any exception | `maxStackTraceLength` |
| `logging` | SLF4J / Log4j2 | `logLevel` |

## Running the Sample

```bash
./run-sample.sh
```

The sample demonstrates all three hook modes: Programmatic, Annotation, and YAML.

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

- **Prometheus**: Built-in `/metrics` endpoint (slow ops, errors, duration)
- **JMX**: `com.github.cc11001100.weavergirl:type=Agent` MBean
- **Health**: Kubernetes liveness/readiness probes via `/health` and `/ready`
- **OTel**: Span bridge to OpenTelemetry Collector
- **JSON Events**: Structured event output with `jsonEvents=true`

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
| `weaver-girl-plugins` | 16 built-in instrumentation plugins |
| `weaver-girl-agent` | Agent entry point (premain/agentmain) |
| `weaver-girl-sample` | Sample application demonstrating all modes |

## Requirements

- Java 1.8+
- Maven 3.6+

## Documentation

- [Architecture](docs/ARCHITECTURE.md)
- [Plugin Developer Guide](docs/plugin-developer-guide.md)
- [YAML Config Reference](docs/yaml-config-reference.md)
- [Troubleshooting](docs/troubleshooting.md)
- [Operations Runbook](docs/runbook.md)
- [OTel Collector Example](docs/otel-collector-example.yaml)
- [Contributing](CONTRIBUTING.md)
- [Changelog](CHANGELOG.md)

## License

MIT License — see [LICENSE](LICENSE) for details.
