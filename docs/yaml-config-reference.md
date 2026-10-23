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

# Plugin control. A disabled capability's jar is never loaded.
disabledPlugins:                 # built-in plugins to skip (by name)
  - servlet
  - kafka
# enabledPlugins:                # if set, ONLY these are loaded (ignores disabledPlugins)
#   - jdbc

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
| `method-timing` | Method execution timing |
| `trace-correlation` | Cross-service trace ID propagation |
| `exception-monitor` | Exception monitoring and tracking |
| `logging` | SLF4J/Log4j2 logging integration |

The names in this table are the values returned by `WeaverPlugin.name()` in the corresponding plugin module, and they are what `disabledPlugins` / `enabledPlugins` / `depends()` match. The bundled plugin set is much larger than the table (about 120 modules); the module directory name and the plugin name usually agree but not always (`weaver-girl-plugin-timing` is `method-timing`, `weaver-girl-plugin-exception` is `exception-monitor`). Read `name()` rather than guessing from the artifact id.

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

Every plugin reads its settings through `PluginContext`, so the same key can be supplied in
several places. The lookup order, first match wins:

1. system property `weavergirl.plugin.<plugin-name>.<key>`
2. YAML `plugins.<plugin-name>.<key>`
3. plain system property `<key>`
4. agent arguments (`-javaagent:...=config=...,<key>=<value>`) and top-level YAML keys
5. the default passed by the plugin

A YAML section is just a shorthand for the namespaced keys: `plugins.jdbc.logSql` is delivered to
the plugin named `jdbc` as `logSql`. Agent arguments of the form `plugins.jdbc.logSql=true` are
also accepted and win over the YAML file.

```yaml
plugins:
  # JDBC Plugin
  jdbc:
    slowQueryThreshold: 1000    # ms; log queries slower than this
    logSql: true                # log SQL statements
    maxSqlLength: 500           # truncate SQL longer than this

  # Redis Plugin
  redis:
    slowCommandThreshold: 100   # ms; log commands slower than this
    logKeys: true               # log Redis keys
    maxKeyLength: 200           # truncate keys longer than this

  # Kafka Plugin
  kafka:
    slowThreshold: 500          # ms; log operations slower than this
    logTopic: true              # log Kafka topics

  # Servlet Plugin
  servlet:
    slowRequestThreshold: 2000  # ms; log requests slower than this
    logUri: true                # log request URIs

  # MethodTiming Plugin (name() is "method-timing", not "timing")
  method-timing:
    slowThreshold: 100          # ms; log methods slower than this

  # Logging Plugin
  logging:
    logLevel: INFO              # capture logs at this level and above

  # ExceptionMonitor Plugin (name() is "exception-monitor")
  exception-monitor:
    maxStackTraceLength: 1000   # truncate stack traces longer than this

  # HttpClient Plugin
  httpclient:
    slowThreshold: 2000         # ms; log requests slower than this
    logUrl: true                # log request URLs

  # gRPC Plugin
  grpc:
    slowThreshold: 1000         # ms; log calls slower than this

  # MongoDB Plugin
  mongo:
    slowThreshold: 1000         # ms; log operations slower than this

  # TraceCorrelation Plugin
  trace-correlation:
    headerName: X-Trace-Id      # HTTP header for trace ID propagation

  # Spring Plugin
  spring:
    slowThreshold: 500          # ms; log bean method calls slower than this
```

### Capability options at the top level

Runtime capabilities (`metrics-port`, `management-api`, `jmx`, `span-export`, `plugin-watcher`,
...) are configured with plain scalar keys, exactly as they would be passed as agent arguments.
Any top-level key that is not an agent setting listed above is handed to the plugins; nested maps
are not, so a capability option is always a scalar.

```yaml
metricsPort: 9400
apiPort: 9402
watch: true                  # hot reload

# Trace export (otlpEndpoint is the historical alias of otlpTracesEndpoint)
otlpTracesEndpoint: http://collector:4318/v1/traces
otlpEndpoint: http://collector:4318/v1/traces   # deprecated alias
otlpHeaders: "Authorization=Bearer token;X-Tenant=acme"

# OTLP resource attributes: distinguish services in one collector
serviceName: orders-api
serviceVersion: 2.1.0
deploymentEnvironment: prod

# Interception events as OTLP log records (separate endpoint from traces)
otlpLogsEndpoint: http://collector:4318/v1/logs
otlpTimeoutMs: 1000
```

These merge into the same launch map as agent arguments, and an agent argument with the same key
wins. A map-valued top-level key that is not `plugins:` (or another structured agent setting) is
logged and ignored.

The full signal-by-signal reference (trace propagation, Prometheus metric catalog, OTLP payloads,
limitations) is in [observability.md](observability.md).

### Plugin directory

`plugins:` is also the agent's switch for loading plugin jars from a directory:

```yaml
plugins: /opt/weaver-girl/plugins
```

This is equivalent to `-javaagent:...=plugins=/opt/weaver-girl/plugins` and takes the collector
plugin path rather than the map form. A plugin loaded this way is loaded once; the agent does not
also preload the same directory into its own class loader.

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
