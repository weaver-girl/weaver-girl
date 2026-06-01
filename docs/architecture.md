# Architecture

## Overview

Weaver-Girl is a Java bytecode instrumentation framework built on [ByteBuddy](https://bytebuddy.net/). It intercepts method calls at the bytecode level, allowing plugins to observe and modify application behavior without source code changes.

## Core Components

### InterceptAdvice

The heart of the framework. Uses ByteBuddy's `@Advice` API to inline interceptor callbacks directly into target methods at class-load time. This means:

- **Zero dispatch overhead** — no reflection or proxy calls at runtime
- **Method entry** — `onMethodEnter()` runs before the method body
- **Method exit** — `onMethodExit()` runs after the method body (normal or exceptional)

### WeaverTransformer

Registers ByteBuddy type transformers based on interceptor definitions. For each registered interceptor:

1. Builds a type matcher from the `ClassMatcher` (exact name, regex pattern, annotation, superclass, or interface)
2. Builds a method matcher from the `MethodMatcher` (exact name, regex pattern, or any)
3. Applies `InterceptAdvice` to matching methods

Excludes bridge, synthetic, native, and abstract methods that cannot be instrumented.

### DefaultInterceptorRegistry

Thread-safe registry that stores interceptor definitions and provides fast lookup by class name. Supports:

- Exact name lookup via index (O(1))
- Pattern/annotation/superclass/interface matching via scan
- Atomic index rebuild (no read-write contention)
- Replace-on-duplicate-name semantics

### Plugin System

Plugins are discovered via `java.util.ServiceLoader`. Each plugin gets:

- Its own `PluginClassLoader` (child-first, isolated)
- A `PluginContext` with configuration access
- Dependency resolution via topological sort
- Automatic `lib/` directory scanning for third-party dependencies

### Configuration

Three ways to configure interceptors:

1. **Programmatic API** — `WeaverGirl.intercept("class").method("name").before(...)`
2. **Annotations** — `@WeaveClass` + `@Before` / `@After` / `@OnException`
3. **YAML** — `weaver.yml` with hot-reload support

## Data Flow

```
JVM Class Load
    │
    ▼
WeaverTransformer.type() ── Does this class match any ClassMatcher?
    │
    ├─ No → Skip (logged in debug mode)
    │
    └─ Yes → Apply InterceptAdvice to matching methods
              │
              ▼
         Target method bytecode now contains:
              1. onMethodEnter() — find interceptors, call before()
              2. [original method body]
              3. onMethodExit() — call after() or onException()
```

## Self-Protection

The agent excludes its own packages from instrumentation to prevent `ClassCircularityError`:

- `com.github.cc11001100.weavergirl.core.*`
- `com.github.cc11001100.weavergirl.agent.*`
- `com.github.cc11001100.weavergirl.shade.*`
- `net.bytebuddy.*`
- `sun.*`, `jdk.internal.*`, `com.sun.*`

User-configured exclusions from YAML are also applied.

## Thread Safety

- `DefaultInterceptorRegistry` — volatile index swap, synchronized writes
- `AgentStatus` — AtomicLong counters, CopyOnWriteArrayList
- `InterceptorCircuitBreaker` — ConcurrentHashMap with atomic operations
- `MethodInvocationPool` — ThreadLocal per-thread pooling
- `ThreadContext` — ThreadLocal with remove() to prevent leaks

## Performance

- **InterceptAdvice** is inlined into target methods (no proxy/dispatch)
- **MethodInvocation** objects are pooled per-thread (reduced GC)
- **Circuit breaker** prevents wasted CPU on failing interceptors
- **Adaptive sampling** skips interceptors under high load
- **maxTransformations** limits total bytecode modifications
