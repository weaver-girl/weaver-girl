# Changelog

## [1.0.0-SNAPSHOT] - 2026-06-01

### Added
- ByteBuddy-based bytecode instrumentation framework
- Three hook modes: programmatic API, annotation, YAML
- Plugin system with SPI discovery and ClassLoader isolation
- Bootstrap class injection for java.* / javax.* interception
- Dynamic attach support (agentmain) with class retransformation
- YAML configuration hot-reload with file watching
- Interceptor circuit breaker (5-failure threshold, 60s cooldown)
- Adaptive sampling under high load
- JMX MBean self-diagnostics
- Cross-thread context propagation (ThreadContext)
- Plugin dependency resolution (topological sort)
- Conditional enhancement (skip non-existent classes)
- Health check and status reporting
- JMH micro-benchmarks

### Fixed
- InterceptAdvice skipMethod / return value override
- YamlConfigLoader advice caching (was per-invocation reflection)
- DefaultInterceptorRegistry atomic index rebuild
- ThreadLocal leak in ThreadContext
- ConfigWatcher debounce for file change events
- Agent SLF4J packaging and shade relocation
- MethodInvocation Method object exposure
- suppressException mechanism for circuit-breaker patterns
- PluginClassLoader security (parent-first for API packages)
