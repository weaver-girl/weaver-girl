# Weaver Girl

A **底层支撑库** (underlying library) for building Java Agent bytecode instrumentation tools.

**Weaver Girl is NOT an APM tool, diagnostic tool, or monitoring system.** It is the foundational library that tools like those can be built upon — similar to how ByteKit powers Arthas, or how `apm-agent-core` powers SkyWalking.

## Features

- **Declarative** — Define hooks via annotations (`@WeaveClass`, `@Before`, `@After`, `@Around`)
- **Configuration** — Define hooks via YAML configuration files
- **Programmatic** — Define hooks via fluent Java API

## Module Structure

| Module | Type | Description |
|--------|------|-------------|
| `weaver-girl-annotation` | Public API (zero-dep) | Annotation definitions for declarative hooks |
| `weaver-girl-api` | Plugin SDK | Interfaces and types for building plugins — **this is what plugin developers depend on** |
| `weaver-girl-core` | Core Engine (internal) | ByteBuddy-based implementation: transformer, registry, config parsing |
| `weaver-girl-agent` | Agent Assembly | premain/agentmain entry point, shaded uber-jar |
| `weaver-girl-sample` | Sample (not published) | Demonstrates how to build a custom agent using weaver-girl |

## Quick Start

### For Plugin Developers

Add `weaver-girl-api` as your only dependency:

```xml
<dependency>
    <groupId>com.github.cc11001100</groupId>
    <artifactId>weaver-girl-api</artifactId>
    <version>1.0.0-SNAPSHOT</version>
</dependency>
```

### Build

```bash
mvn clean package
```

### Run

```bash
java -javaagent:weaver-girl-agent/target/weaver-girl-agent-1.0.0-SNAPSHOT.jar -jar your-app.jar
```

## Requirements

- Java 1.8+
- Maven 3.6+
