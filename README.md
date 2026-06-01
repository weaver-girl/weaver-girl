# Weaver-Girl

A modular Java bytecode instrumentation framework built on ByteBuddy, inspired by SkyWalking and OpenTelemetry Java Agent.

## Features

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

## Quick Start

### 1. Build

```bash
mvn clean package -DskipTests
```

### 2. Attach as Java Agent

```bash
java -javaagent:weaver-girl-agent/target/weaver-girl-agent-1.0.0-SNAPSHOT.jar=config=/path/to/weaver.yml -jar your-app.jar
```

### 3. Define Interceptors (Programmatic)

```java
WeaverGirl weaverGirl = WeaverGirl.create();
weaverGirl.intercept("com.example.Service")
    .method("process")
    .before(inv -> System.out.println("Before: " + inv.getMethodName()))
    .after(inv -> System.out.println("After: " + inv.getMethodName()))
    .install();
weaverGirl.withInstrumentation(instrumentation);
```

### 4. Define Interceptors (Annotation)

```java
@WeaveClass(className = "com.example.Service")
public class MyInterceptor {

    @Before(methodName = "process")
    public static void beforeProcess(MethodInvocation invocation) {
        // ...
    }
}
```

### 5. Define Interceptors (YAML)

```yaml
interceptors:
  - className: com.example.Service
    method: process
    before: com.example.MyBeforeAdvice
```

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
| `weaver-girl-api` | Plugin SDK -- interfaces and value objects |
| `weaver-girl-core` | Engine -- ByteBuddy transformer, registry, config |
| `weaver-girl-annotation` | Declarative annotations for interceptors |
| `weaver-girl-agent` | Agent entry point (premain/agentmain) |
| `weaver-girl-sample` | Sample application demonstrating all modes |

## Requirements

- Java 1.8+
- Maven 3.6+

## License

Apache License 2.0
