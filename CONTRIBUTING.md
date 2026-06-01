# Contributing to Weaver-Girl

Thank you for your interest in contributing to Weaver-Girl! This guide covers everything you need to set up your development environment, write code, add plugins, and submit changes.

## Code of Conduct

Be respectful, constructive, and inclusive. We follow the [Contributor Covenant](https://www.contributor-covenant.org/) Code of Conduct.

---

## Development Setup

### Prerequisites

- **Java 8** or later (the project targets Java 8 bytecode via `maven.compiler.source=1.8` and `maven.compiler.target=1.8`)
- **Maven 3.6+**

### Clone and Build

```bash
git clone https://github.com/cc11001100/weaver-girl.git
cd weaver-girl/weaver-girl
mvn clean install
```

This compiles all six modules, runs the full test suite, and installs artifacts to your local Maven repository.

### Verify the Agent JAR

```bash
AGENT_JAR=$(find weaver-girl-agent/target -name "weaver-girl-agent-*.jar" ! -name "*sources*" ! -name "*javadoc*" | head -1)
unzip -p "$AGENT_JAR" META-INF/MANIFEST.MF | grep Premain-Class
```

You should see `Premain-Class: com.github.cc11001100.weavergirl.agent.WeaverGirlAgent`.

---

## Building and Testing

### Full Build (compile + test + package)

```bash
mvn clean install
```

### Run Tests Only

```bash
mvn test
```

### Build a Single Module

```bash
mvn clean install -pl weaver-girl-plugins
```

### Skip Tests (quick compile check)

```bash
mvn clean install -DskipTests
```

### Test Requirements

All 914+ tests must pass. The test suite includes:

- **Unit tests** in every module (JUnit 5 + Mockito)
- **Integration tests** in `weaver-girl-core` (agent bootstrap, multi-agent coexistence, circuit breaker, sampling)
- **E2E tests** in `weaver-girl-agent` (full agent lifecycle with `byte-buddy-agent`)
- **Benchmarks** in `weaver-girl-core` (JMH micro-benchmarks for interceptor and matcher performance)
- **Regression tests** in `weaver-girl-plugins` (`PluginMatchTypeRegressionTest` verifies correct `MatchType` usage across all 12 plugins)

CI runs the full build matrix across JDK 8, 11, 17, and 21 (see `.github/workflows/ci.yml`).

---

## Code Style Conventions

- **Java 8 compatible** -- no `var`, no `Set.of()`, no `ProcessHandle`, no text blocks, no switch expressions
- **4-space indentation**, no tabs
- **Javadoc on all public classes and methods** -- the project generates Javadoc JARs as part of the build
- **No `System.out.println`** in production code; use SLF4J (`org.slf4j.Logger`)
- **No empty catch blocks** -- at minimum, log the exception
- **Interceptors must be thread-safe** -- a single interceptor instance may be invoked concurrently by multiple threads. Use `ThreadLocal` or `ThreadContext` for per-invocation state
- **Interceptors must not throw exceptions** that escape callback methods -- catch internally; the framework also catches as a safety net, but plugins should handle their own errors gracefully
- **Checkstyle** rules may be added in a future phase; for now, follow the patterns established by the existing codebase

---

## How to Add a New Plugin

Plugins are the primary extension point. Here is the step-by-step process for adding a new plugin to the `weaver-girl-plugins` module.

### 1. Create the Plugin Class

Extend `AbstractPlugin` and implement the required methods:

```java
package com.github.cc11001100.weavergirl.plugins.myplugin;

import com.github.cc11001100.weavergirl.api.interceptor.Interceptor;
import com.github.cc11001100.weavergirl.api.interceptor.InterceptorDefinition;
import com.github.cc11001100.weavergirl.api.interceptor.MethodInvocation;
import com.github.cc11001100.weavergirl.api.matcher.ClassMatcher;
import com.github.cc11001100.weavergirl.api.matcher.MethodMatcher;
import com.github.cc11001100.weavergirl.api.plugin.AbstractPlugin;
import com.github.cc11001100.weavergirl.api.plugin.PluginContext;
import com.github.cc11001100.weavergirl.api.pointcut.Pointcut;
import com.github.cc11001100.weavergirl.api.registry.InterceptorRegistry;

public class MyPlugin extends AbstractPlugin {

    @Override
    public String name() {
        return "my-plugin";
    }

    @Override
    public void init(PluginContext context) {
        // Read configuration from PluginContext
        // e.g., context.getConfigLong("threshold", 1000);
    }

    @Override
    public void registerInterceptors(InterceptorRegistry registry) {
        // Register interceptor definitions using the fluent builder:
        registry.register(
            interceptImplementing("com.example.MyInterface")
                .method("process")
                .around(
                    inv -> { /* before logic */ },
                    inv -> { /* after logic */ }
                )
                .priority(10)
                .build()
        );

        // Or construct InterceptorDefinition directly:
        Interceptor myInterceptor = new Interceptor() {
            @Override
            public void before(MethodInvocation inv) { /* ... */ }

            @Override
            public void after(MethodInvocation inv) { /* ... */ }

            @Override
            public void onException(MethodInvocation inv) { /* ... */ }
        };

        registry.register(new InterceptorDefinition(
            name() + "-my-interceptor",
            new Pointcut(ClassMatcher.bySuperClass("com.example.BaseService"), MethodMatcher.byNamePattern("handle.*")),
            myInterceptor, 10
        ));
    }
}
```

### 2. Choose the Right ClassMatcher

The `ClassMatcher.MatchType` you choose is critical for correct interception:

| MatchType | Factory Method | When to Use |
|-----------|---------------|-------------|
| `EXACT_NAME` | `ClassMatcher.byName("com.example.Service")` | Concrete class you know the exact name of |
| `NAME_PATTERN` | `ClassMatcher.byNamePattern("com\\.example\\..*Service")` | Match multiple classes by regex |
| `ANNOTATION` | `ClassMatcher.byAnnotation("com.example.Traced")` | Match classes bearing a specific annotation |
| `SUPER_CLASS` | `ClassMatcher.bySuperClass("javax.servlet.http.HttpServlet")` | Match concrete subclasses (e.g., `FrameworkServlet`) |
| `INTERFACE` | `ClassMatcher.byInterface("java.sql.Statement")` | Match all implementations of an interface (e.g., `PgStatement`, `MySQLStatement`) |

**Important:** For interfaces like `java.sql.Statement`, `javax.servlet.Filter`, or `java.sql.Connection`, always use `byInterface()` -- real applications never instantiate the interface itself; they use driver-specific implementations.

### 3. Register the Plugin via SPI

Add your plugin class to the SPI service file:

**File:** `weaver-girl-plugins/src/main/resources/META-INF/services/com.github.cc11001100.weavergirl.api.plugin.WeaverPlugin`

```
com.github.cc11001100.weavergirl.plugins.timing.MethodTimingPlugin
com.github.cc11001100.weavergirl.plugins.servlet.ServletPlugin
com.github.cc11001100.weavergirl.plugins.jdbc.JdbcPlugin
com.github.cc11001100.weavergirl.plugins.trace.TraceCorrelationPlugin
com.github.cc11001100.weavergirl.plugins.spring.SpringPlugin
com.github.cc11001100.weavergirl.plugins.exception.ExceptionMonitorPlugin
com.github.cc11001100.weavergirl.plugins.redis.RedisPlugin
com.github.cc11001100.weavergirl.plugins.httpclient.HttpClientPlugin
com.github.cc11001100.weavergirl.plugins.grpc.GrpcPlugin
com.github.cc11001100.weavergirl.plugins.kafka.KafkaPlugin
com.github.cc11001100.weavergirl.plugins.mongo.MongoPlugin
com.github.cc11001100.weavergirl.plugins.logging.LoggingPlugin
com.github.cc11001100.weavergirl.plugins.myplugin.MyPlugin
```

### 4. Write Tests

Create a test class in `weaver-girl-plugins/src/test/java/`:

```java
package com.github.cc11001100.weavergirl.plugins.myplugin;

import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class MyPluginTest {

    @Test
    void nameReturnsExpectedValue() {
        MyPlugin plugin = new MyPlugin();
        assertEquals("my-plugin", plugin.name());
    }

    @Test
    void registersInterceptors() {
        MyPlugin plugin = new MyPlugin();
        // Use a mock registry and verify registerInterceptors() behavior
    }
}
```

Also add a `MatchType` regression test entry in `PluginMatchTypeRegressionTest` to verify your plugin uses the correct `MatchType` for each interceptor definition.

### 5. Publish Structured Events (Optional)

If your plugin detects interesting conditions (slow queries, errors, etc.), publish `InterceptorEvent` instances so consumers can integrate with metrics systems:

```java
InterceptorEventPublisher.getInstance().publish(
    InterceptorEvent.builder()
        .type("slow-query")
        .plugin("my-plugin")
        .className(inv.getTargetClass().getSimpleName())
        .methodName(inv.getMethodName())
        .durationMs(elapsedMs)
        .attribute("key", "value")
        .build()
);
```

---

## How to Run the Sample Application

The sample app (`weaver-girl-sample`) runs an embedded Jetty server with an H2 database, demonstrating real Servlet and JDBC interception.

### Build and Run

```bash
# Build everything
mvn clean package -DskipTests

# Run with the agent (no config file)
java \
    -javaagent:weaver-girl-agent/target/weaver-girl-agent-1.0.0-SNAPSHOT.jar \
    -cp "weaver-girl-sample/target/classes:weaver-girl-api/target/classes:weaver-girl-annotation/target/classes:weaver-girl-core/target/classes" \
    com.github.cc11001100.weavergirl.sample.app.SampleApplication
```

### Run with YAML Config and JSON Events

```bash
java \
    -javaagent:weaver-girl-agent/target/weaver-girl-agent-1.0.0-SNAPSHOT.jar=config=weaver-example.yml,jsonEvents=true \
    -cp "weaver-girl-sample/target/classes:weaver-girl-api/target/classes:weaver-girl-annotation/target/classes:weaver-girl-core/target/classes" \
    com.github.cc11001100.weavergirl.sample.app.SampleApplication
```

### Run with Prometheus Metrics

```bash
java \
    -javaagent:weaver-girl-agent/target/weaver-girl-agent-1.0.0-SNAPSHOT.jar=metricsPort=9400 \
    -cp "..." \
    com.github.cc11001100.weavergirl.sample.app.SampleApplication

# Then scrape metrics:
curl http://localhost:9400/metrics
```

### Or Use the Convenience Script

```bash
./run-sample.sh
```

### Test the Endpoints

```bash
curl http://localhost:8080/health
curl http://localhost:8080/users?name=Alice
curl http://localhost:8080/users?name=Bob
curl http://localhost:8080/users
curl http://localhost:8080/users?slow=true   # triggers SLOW-SERVLET warning
```

---

## Commit Message Convention

This project follows [Conventional Commits](https://www.conventionalcommits.org/):

```
<type>(<scope>): <description>

[optional body]

[optional footer]
```

### Types

| Type | Meaning |
|------|---------|
| `feat` | New feature |
| `fix` | Bug fix |
| `refactor` | Code restructuring with no behavior change |
| `test` | Adding or updating tests |
| `docs` | Documentation changes |
| `chore` | Build, CI, or tooling changes |
| `perf` | Performance improvement |

### Scopes

| Scope | Module |
|-------|--------|
| `api` | `weaver-girl-api` |
| `core` | `weaver-girl-core` |
| `agent` | `weaver-girl-agent` |
| `annotation` | `weaver-girl-annotation` |
| `plugins` | `weaver-girl-plugins` |
| `sample` | `weaver-girl-sample` |
| `scaffold` | Project structure / multi-module layout |

### Examples

```
feat(plugins): add MongoDB instrumentation plugin
fix(core): prevent ClassCircularityError when agent classes are transformed
refactor(scaffold): restructure to 5-module layered architecture
test(plugins): add MatchType regression tests for all 12 plugins
docs(api): add Javadoc to PluginContext config methods
chore(ci): add JDK 21 to build matrix
```

---

## Pull Request Process

1. **Fork** the repository
2. **Create a feature branch** from `main`:
   ```bash
   git checkout -b feat/my-feature
   ```
3. **Write code with tests** -- every new feature or bug fix must have corresponding tests
4. **Run the full build** to ensure all 914+ tests pass:
   ```bash
   mvn clean install
   ```
5. **Write conventional commit messages** (see above)
6. **Push your branch** and open a PR against `main`
7. **PR description** should include:
   - What the change does and why
   - How to test it
   - Any breaking changes or migration notes
8. **CI must pass** -- the build matrix runs on JDK 8, 11, 17, and 21
9. **Code review** -- at least one approval required before merge

### Bug Reports

When filing a bug report, include:

- Java version (`java -version`)
- Weaver-Girl version
- Steps to reproduce
- Expected vs actual behavior
- Agent log output (with `logLevel=DEBUG` or `debug=true` if possible)

---

## Plugin Dependency Declaration

If your plugin depends on another plugin being loaded first, override the `depends()` method:

```java
@Override
public String[] depends() {
    return new String[] { "trace-correlation" };
}
```

The `PluginDependencyResolver` performs topological sort to ensure correct initialization order.

---

## License

By contributing, you agree that your contributions will be licensed under the [MIT License](LICENSE).
