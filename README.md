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

- **12 Built-in Plugins**: Servlet, Spring, JDBC, Redis, Kafka, gRPC, MongoDB, HttpClient, MethodTiming, TraceCorrelation, ExceptionMonitor, Logging
- **Three Hook Modes**: Programmatic API, Annotation-driven, YAML configuration
- **Plugin System**: SPI-based plugin discovery with dependency resolution and ClassLoader isolation
- **ByteBuddy Advice**: Zero-allocation inlined method interception
- **Bootstrap Class Injection**: Intercept `java.*` and `javax.*` classes
- **Dynamic Attach**: Runtime attachment via `agentmain` with retransformation
- **Hot Reload**: YAML config changes applied without restart
- **Circuit Breaker**: Automatic disable of failing interceptors
- **Adaptive Sampling**: Reduce overhead under high load
- **JMX Diagnostics**: Monitor agent status via JMX MBean
- **Cross-thread Context**: ThreadContext propagation with ContextRunnable/ContextCallable

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
| `timing` | Any method | `slowThreshold` (ms) |
| `trace-correlation` | HTTP headers | `headerName` |
| `exception` | Any exception | `maxStackTraceLength` |
| `logging` | SLF4J / Log4j2 | `logLevel` |

## Running the Sample

```bash
./run-sample.sh
```

The sample demonstrates all three hook modes: Programmatic, Annotation, and YAML.

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
| `weaver-girl-plugins` | 12 built-in instrumentation plugins |
| `weaver-girl-agent` | Agent entry point (premain/agentmain) |
| `weaver-girl-sample` | Sample application demonstrating all modes |

## Requirements

- Java 1.8+
- Maven 3.6+

## Documentation

- [Architecture](docs/architecture.md)
- [Plugin Developer Guide](docs/plugin-developer-guide.md)
- [YAML Config Reference](docs/yaml-config-reference.md)
- [Troubleshooting](docs/troubleshooting.md)
- [Contributing](CONTRIBUTING.md)

## License

MIT License — see [LICENSE](LICENSE) for details.
