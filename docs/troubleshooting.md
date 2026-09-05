# Troubleshooting Guide

Common issues and solutions when using weaver-girl.

## Agent Fails to Attach

### Symptom: "ByteBuddyAgent self-attach not available"

**Cause:** The JVM does not allow self-attach in this environment (container, security manager, etc.)

**Solution:** Use the `-javaagent` flag at JVM startup instead of dynamic attach:
```bash
java -javaagent:weaver-girl-agent.jar=config=/path/to/weaver.yml -jar your-app.jar
```

### Symptom: "FATAL: Agent initialization failed"

**Cause:** The agent JAR is corrupted, or a classpath conflict exists.

**Solution:**
1. Verify the JAR is not corrupted: `jar tf weaver-girl-agent.jar | head`
2. Check for classpath conflicts — the agent JAR is self-contained (shaded)
3. Check the agent version matches your JDK version (Java 8+ required)

## Interceptors Not Being Called

### Symptom: Agent starts but no interception happens

**Possible causes and solutions:**

1. **Config file not loaded**
   - Verify the config path: `java -javaagent:weaver-girl-agent.jar=config=/absolute/path/to/weaver.yml`
   - Check logs for "Config loaded from:" or "No config file specified"

2. **Class name pattern mismatch**
   - `className` is **exact match** — `com.example.UserService` matches only that exact class
   - `classPattern` is **regex** — `com\.example\..*Service` uses Java regex syntax
   - **Do NOT use glob patterns** like `com.example.*Service` — this will silently fail

3. **Target class not on classpath**
   - The agent can only intercept classes that exist in the JVM
   - Check logs for "Skipping interceptor: target class not found on classpath"

4. **Sampling is reducing interception**
   - Under high load, adaptive sampling may reduce interception rate
   - Check: `SamplingController.getSamplingRate()` via JMX
   - Set: `samplingThreshold: 999999` in config to effectively disable sampling

5. **Plugin is disabled**
   - Check `disabledPlugins` in your config
   - Check logs for "Plugin X is disabled via config"

6. **Circuit breaker is open**
   - After 5 consecutive failures, the circuit breaker disables an interceptor
   - Check logs for "Circuit breaker OPEN for interceptor"
   - Wait 60 seconds for cooldown, or fix the interceptor

7. **maxTransformations limit reached**
   - Default limit is 10,000 transformed classes
   - For large applications, increase: `maxTransformations: 50000`

8. **Target is a JDK bootstrap class with ARGUMENT_REWRITE advice**
   - `ARGUMENT_REWRITE` interceptors are skipped for bootstrap-loaded classes
     (`classLoader == null`) — e.g. `java.util.concurrent.CompletableFuture`,
     `ForkJoinPool`, JDK `Executors.*` implementations. The definition registers
     normally but never fires at runtime.
   - For executor-based async work, wrap explicitly:
     `ContextExecutorService.wrap(rawExecutor)` (see plugin-developer-guide,
     "Data and Context Propagation").
   - For CompletableFuture stages, use the `ContextCompletableFuture` API
     (`supplyAsync`/`runAsync` with context propagation) instead of raw
     `CompletableFuture` overloads — transparent weaving of the
     `supplyAsync`/`runAsync` factory methods is not supported.

## Verifying the Agent is Working

### Check startup logs

```
WeaverGirl agent v1.0.0-SNAPSHOT initializing... (premain)
Config loaded from: /path/to/weaver.yml
Loading plugin: spring
Loading plugin: jdbc
Loaded 12 plugins (12 succeeded, 0 failed) out of 12 discovered
WeaverGirl agent started with 15 interceptor definitions
```

### Check via JMX

Connect via JConsole or VisualVM to MBean: `com.github.cc11001100.weavergirl:type=Agent`

Attributes:
- `TransformationCount` — number of classes transformed
- `InterceptorInvocationCount` — total interceptor calls
- `ActivePluginCount` — number of active plugins
- `RegisteredInterceptorCount` — total registered interceptors

### Enable debug logging

```bash
java -javaagent:weaver-girl-agent.jar=config=/weaver.yml,debug=true -jar your-app.jar
```

Or in config: `logLevel: DEBUG`

## Performance Issues

### High GC pressure

**Cause:** Too many intercepted methods creating MethodInvocation objects.

**Solutions:**
1. Enable sampling: `samplingThreshold: 5000`
2. Use `onlyInterceptPackages` to limit scope
3. Disable unnecessary plugins: `disabledPlugins: [timing, logging]`

### Slow startup after agent attach

**Cause:** Large number of classes need transformation.

**Solutions:**
1. Use `onlyInterceptPackages` to limit scope
2. Increase `maxTransformations` if needed
3. Use `excludedClasses` to skip framework internals

### Memory usage too high

**Cause:** Intercepting too many methods stores state per invocation.

**Solutions:**
1. Reduce interception scope with `onlyInterceptPackages`
2. Disable heavy plugins: `disabledPlugins: [trace-correlation, timing]`
3. Enable sampling to reduce interceptor invocations

## Hot Reload Issues

### Config changes not picked up

**Cause:** `watch=true` not set in agent arguments.

**Solution:**
```bash
java -javaagent:weaver-girl-agent.jar=config=/weaver.yml,watch=true -jar your-app.jar
```

### Multiple reloads from single edit

**Cause:** Some editors write temporary files, triggering multiple change events.

**Solution:** The agent debounces events by 2 seconds. If you still see double-reloads, increase the debounce window in the source code.

## SLF4J Warnings

### "Multiple SLF4J bindings"

**Cause:** The agent bundles its own SLF4J binding (shaded). Your app likely has its own.

**Solution:** This is a harmless warning. The shaded SLF4J in the agent uses a different package namespace and does not conflict.

## Getting Help

If you're still stuck:

1. Search existing issues: https://github.com/cc11001100/weaver-girl/issues
2. Open a new issue with:
   - Java version (`java -version`)
   - Weaver-Girl version
   - Full agent log output (with `debug=true`)
   - Your config file (redact sensitive values)
