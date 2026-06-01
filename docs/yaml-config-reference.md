# YAML Configuration Reference

Complete reference for weaver-girl agent YAML configuration.

## File Location

Pass the config file path to the agent:

```bash
java -javaagent:weaver-girl-agent.jar=config=/path/to/weaver.yml -jar your-app.jar
```

## Agent-Level Settings

All agent-level settings are optional. Defaults are shown below.

```yaml
# Sampling: when invocation rate exceeds threshold, reduce interception frequency
samplingThreshold: 10000         # invocations/second threshold for adaptive sampling
maxTransformations: 10000        # max classes to transform (increase for large apps)

# Circuit breaker: disable interceptors that fail repeatedly
circuitBreakerFailures: 5        # consecutive failures before opening
circuitBreakerCooldown: 60000    # cooldown in milliseconds (default: 60s)

# Scope control
excludedClasses:                 # class name patterns to exclude (regex)
  - "com.example.internal.*"
onlyInterceptPackages:           # only intercept classes in these packages
  - "com.myapp"

# Plugin control
disabledPlugins:                 # built-in plugins to skip (by name)
  - servlet
  - kafka

# Logging
logLevel: INFO                   # TRACE, DEBUG, INFO, WARN, ERROR

# Hot reload
# Enable with: -javaagent:weaver-girl-agent.jar=config=/path/to/weaver.yml,watch=true
```

### disabledPlugins — Built-in Plugin Names

| Plugin Name | Target Framework |
|-------------|-----------------|
| `servlet` | javax.servlet / Jakarta Servlet |
| `spring` | Spring Framework (@Controller, @Service, etc.) |
| `jdbc` | java.sql (JDBC drivers) |
| `redis` | Jedis / Lettuce Redis clients |
| `httpclient` | Apache HttpClient / HttpURLConnection |
| `grpc` | gRPC (io.grpc) |
| `kafka` | Apache Kafka (producer/consumer) |
| `mongo` | MongoDB Java Driver |
| `timing` | Method execution timing |
| `trace-correlation` | Cross-service trace ID propagation |
| `exception` | Exception monitoring and tracking |
| `logging` | SLF4J/Log4j2 logging integration |

## Interceptor Definitions

Define custom interceptors via YAML. Advice classes must implement
`com.github.cc11001100.weavergirl.api.interceptor.Interceptor`.

### Class Matching

| Field | Type | Description |
|-------|------|-------------|
| `className` | exact string | Exact fully-qualified class name (e.g., `com.example.UserService`) |
| `classPattern` | regex | Regex pattern for class name (e.g., `com\.example\..*Service`) |
| `classAnnotation` | exact string | Match classes with this annotation (e.g., `org.springframework.web.bind.annotation.RestController`) |
| `superClass` | exact string | Match classes that extend this superclass |
| `interfaceName` | exact string | Match classes that implement this interface |

**Note:** `className` uses exact match. `classPattern` uses Java regex. Do NOT use glob patterns like `com.example.*Service` — use `com\.example\..*Service` instead.

### Method Matching

| Field | Type | Description |
|-------|------|-------------|
| `method` | exact string | Exact method name (use `"*"` for all methods) |
| `methodPattern` | regex | Regex pattern for method name |

### Advice Callbacks

| Field | Description |
|-------|-------------|
| `before` | Fully-qualified class name implementing `Interceptor.before()` — called before method execution |
| `after` | Fully-qualified class name implementing `Interceptor.after()` — called after method execution |
| `around` | Fully-qualified class name implementing both `before()` and `after()` — called on both entry and exit |

### Priority

| Field | Type | Default | Description |
|-------|------|---------|-------------|
| `priority` | integer | 0 | Lower value = higher priority. Interceptors are invoked in priority order. |

### Examples

```yaml
interceptors:
  # Example 1: Intercept a specific method on a specific class
  - className: com.example.UserService
    method: createUser
    before: com.example.interceptor.AuditBeforeAdvice

  # Example 2: Intercept all methods matching a regex pattern
  - classPattern: com\.example\..*Service
    methodPattern: ^(find|get|query).*
    after: com.example.interceptor.QueryTimingAdvice

  # Example 3: Intercept all methods on annotated classes
  - classAnnotation: org.springframework.web.bind.annotation.RestController
    method: "*"
    around: com.example.interceptor.ApiMonitoringAdvice

  # Example 4: With priority ordering
  - className: com.example.PaymentService
    method: process
    before: com.example.interceptor.AuthCheckAdvice
    priority: 1
  - className: com.example.PaymentService
    method: process
    after: com.example.interceptor.AuditLogAdvice
    priority: 10
```

## Plugin-Specific Configuration

Each built-in plugin has its own configuration keys, set via the agent config map:

```yaml
# JDBC Plugin
plugins:
  jdbc:
    slowQueryThreshold: 1000    # ms; log queries slower than this
    logSql: true                # log SQL statements
    maxSqlLength: 500           # truncate SQL longer than this

# Redis Plugin
plugins:
  redis:
    slowCommandThreshold: 100   # ms; log commands slower than this
    logKeys: true               # log Redis keys
    maxKeyLength: 200           # truncate keys longer than this

# Kafka Plugin
plugins:
  kafka:
    slowThreshold: 500          # ms; log operations slower than this
    logTopic: true              # log Kafka topics

# Servlet Plugin
plugins:
  servlet:
    slowRequestThreshold: 2000  # ms; log requests slower than this
    logUri: true                # log request URIs

# MethodTiming Plugin
plugins:
  timing:
    slowThreshold: 100          # ms; log methods slower than this

# Logging Plugin
plugins:
  logging:
    logLevel: INFO              # capture logs at this level and above

# ExceptionMonitor Plugin
plugins:
  exception:
    maxStackTraceLength: 1000   # truncate stack traces longer than this

# HttpClient Plugin
plugins:
  httpclient:
    slowThreshold: 2000         # ms; log requests slower than this
    logUrl: true                # log request URLs

# gRPC Plugin
plugins:
  grpc:
    slowThreshold: 1000         # ms; log calls slower than this

# MongoDB Plugin
plugins:
  mongo:
    slowThreshold: 1000         # ms; log operations slower than this

# TraceCorrelation Plugin
plugins:
  trace-correlation:
    headerName: X-Trace-Id      # HTTP header for trace ID propagation

# Spring Plugin
plugins:
  spring:
    slowThreshold: 500          # ms; log bean method calls slower than this
```

## Hot Reload

Enable hot reload to automatically pick up config changes without restarting the JVM:

```bash
java -javaagent:weaver-girl-agent.jar=config=/path/to/weaver.yml,watch=true -jar your-app.jar
```

When the config file changes:
1. All YAML-defined interceptors are unregistered
2. The new config is loaded
3. New interceptors are registered
4. Already-loaded classes are retransformed if needed

**Debounce:** File change events are debounced by 2 seconds to prevent rapid reloads from editors that write temporary files.
