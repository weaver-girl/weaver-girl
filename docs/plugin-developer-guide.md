# Plugin Developer Guide

## Creating a Weaver-Girl Plugin

### Step 1: Add Dependency

```xml
<dependency>
    <groupId>com.github.cc11001100</groupId>
    <artifactId>weaver-girl-api</artifactId>
    <version>1.0.0-SNAPSHOT</version>
    <scope>provided</scope>
</dependency>
```

### Step 2: Implement a Plugin

Extend `AbstractPlugin` for a fluent builder API, or implement `WeaverPlugin` directly:

```java
package com.example.myplugin;

import com.github.cc11001100.weavergirl.api.plugin.*;
import com.github.cc11001100.weavergirl.api.registry.InterceptorRegistry;

public class MyPlugin extends AbstractPlugin {
    @Override
    public String name() {
        return "my-plugin";
    }

    @Override
    public void registerInterceptors(InterceptorRegistry registry) {
        registry.register(
            intercept("com.example.TargetService")
                .method("process")
                .before(inv -> {
                    System.out.println("Intercepted: " + inv.getMethodName());
                })
                .build()
        );
    }
}
```

`registerInterceptors` is optional. A capability plugin — one that starts a server, an exporter, a
background checker, or publishes a service other plugins use — overrides `name()` (and `init` /
`destroy`) without weaving anything. The default implementation is a no-op.

### Step 3: Register SPI

Create `META-INF/services/com.github.cc11001100.weavergirl.api.plugin.WeaverPlugin`:

```
com.example.myplugin.MyPlugin
```

### Step 4: Package and Deploy

```bash
mvn clean package
cp target/my-plugin.jar /path/to/plugins/
```

The agent will discover and load your plugin automatically.

Before that run, [testing the plugin in-process](#testing-the-plugin-in-process) checks the
callbacks in a plain unit test. The end-to-end run is still worth keeping: only it proves the jar
layout and the class-loader isolation.

## Plugin Lifecycle

1. **Discovery** -- Loaded via `java.util.ServiceLoader`
2. **Dependency Resolution** -- Plugins sorted by `depends()` declarations
3. **Initialization** -- `init(PluginContext)` called with config access
4. **Enable check** -- `isEnabled(PluginContext)`; returning `false` marks the plugin inactive, not failed, and skips registration
5. **Registration** -- `registerInterceptors(InterceptorRegistry)` called; the default no-op means a capability plugin can skip it
6. **Runtime** -- Interceptors invoked as matched methods are called
7. **Destroy** -- `destroy()` called at agent shutdown

`init` runs before the enable check, so it must stay cheap and side-effect free even when the plugin ends up disabled. The [platform lifecycle](#platform-lifecycle) section explains why.

## Using Plugin Configuration

```java
@Override
public void init(PluginContext context) {
    String threshold = context.getConfig("threshold", "1000");
}
```

`name()` is the only thing a key is scoped by, and the lookup order is first match wins:

1. system property `weavergirl.plugin.my-plugin.threshold`
2. YAML `plugins.my-plugin.threshold`
3. plain system property `threshold`
4. agent argument `threshold=...` and top-level YAML keys
5. the default you pass

So `plugins.<name>.<key>` in YAML and `weavergirl.plugin.<name>.<key>` as a system property both
address *your* plugin and do not leak into another plugin that happens to read the same key. A
scalar top-level YAML key such as `metricsPort: 9400` reaches every plugin as `metricsPort`, which
is how the runtime capability plugins are configured; `getAllConfig()` returns that flat launch
map with the namespaced keys written out in full.

## Interceptor API

### Before/After

```java
intercept("com.example.Service")
    .method("process")
    .before(inv -> log("before"))
    .after(inv -> log("after"))
    .build()
```

### Around

```java
intercept("com.example.Service")
    .method("process")
    .around(
        inv -> log("before"),
        inv -> log("after")
    )
    .build()
```

### Skip Method Execution

```java
.before(inv -> {
    if (shouldSkip(inv)) {
        inv.skipMethod();
        inv.setReturnValue(defaultValue);
    }
})
```

### Suppress Exception

```java
.onException(inv -> {
    inv.suppressException();
    inv.setReturnValue(fallbackValue);
})
```

### Data and Context Propagation

Weaver-Girl provides several context layers for plugin cooperation:

- `MethodInvocation` arguments, return values, and attachments are for one intercepted call.
- `ThreadContext` is for sharing request or trace data across interceptors running on the same thread.
- `ContextSnapshot`, `ContextScope`, and `ContextPropagator` capture and restore thread context across async boundaries.
- `ContextExecutor`, `ContextExecutorService`, and `ContextScheduledExecutorService` wrap executors so every submitted or scheduled task receives the caller's current context.
- `GlobalContext` is for process-wide values that should be visible to every thread and plugin.

For simple executor usage, prefer wrapping the executor once:

```java
ExecutorService contextExecutor = ContextExecutorService.wrap(rawExecutor);

before(inv -> {
    ThreadContext.put("traceId", traceId);
    contextExecutor.submit(() -> {
        String id = ThreadContext.get("traceId");
        // ...
    });
});
```

The async context propagation plugin can automatically weave executor
implementations loaded by the application or plugin classloader. JDK bootstrap
executors created through `Executors.*` should be wrapped explicitly with
`ContextExecutorService.wrap(...)` or `ContextScheduledExecutorService.wrap(...)`.

For CompletableFuture stages, use the `ContextCompletableFuture` API — the
agent cannot weave `CompletableFuture.supplyAsync`/`runAsync` because
`CompletableFuture` itself lives on the bootstrap classloader (where
`ARGUMENT_REWRITE` advice is skipped) and the no-executor overloads use
`ForkJoinPool.commonPool()` internally:

```java
// Instead of:
CompletableFuture.supplyAsync(() -> loadUser(id), executor);
// Use:
ContextCompletableFuture.supplyAsync(() -> loadUser(id), executor);
```

For a single task, use `ContextPropagator` or the direct wrappers:

```java
.before(inv -> {
    ThreadContext.put("traceId", traceId);
    executor.submit(ContextPropagator.wrap(() -> {
        String id = ThreadContext.get("traceId");
        // ...
    }));
})
```

## Building a product on the platform

A plugin is the supported way to build a product on top of weaver-girl — an APM agent, an IAST scanner, an audit trail. The contract below is everything such a product should need, and everything it can rely on staying stable. Code against `weaver-girl-api` only; that module is the boundary.

### What a plugin may depend on

`weaver-girl-api`, and the JDK. Nothing else.

A drop-in plugin jar is loaded by a child-first class loader whose parent is the agent's own isolated loader. That loader delegates to its parent only for `com.github.cc11001100.weavergirl.api`, `com.github.cc11001100.weavergirl.annotation`, and the JDK. Classes from `weaver-girl-core` are reachable by accident, through fall-through, and that is not a contract: they are loaded inside the agent's isolated loader, so a class you touched from your own code and the one the engine uses can be two different copies. Depending on core also ties your plugin to internals that move between versions.

The one exception is a plugin that ships *with* the agent as one of its own capabilities (the `weaver-girl-runtime-*` modules). Those are loaded by the agent's loader itself and may depend on core, because they drive the engine rather than observing an application. A third-party product is not one of those.

### Registering more than method interception

The fluent builder covers three kinds of join point, and each one weaves a different piece of advice. They are separate chains on purpose: a definition carries exactly one advice mode, and giving a field interceptor a method definition means the field advice is never woven and the interceptor never runs.

```java
@Override
public void registerInterceptors(InterceptorRegistry registry) {
    // A method, before and after it runs.
    registry.register(
        intercept("com.example.PaymentService")
            .method("charge")
            .around(inv -> span.start(), inv -> span.end())
            .build());

    // A field. Both reads and writes match; implement whichever you need.
    registry.register(
        interceptField("com.example.User", "password")
            .onGet((invocation, value) -> invocation.setReturnValue("***"))
            .onSet((invocation, target, value) -> invocation.skipWrite())
            .build());

    // Every field of a given type, not just one name.
    registry.register(
        interceptField("com.example.User", FieldMatcher.byType("java.lang.String"))
            .onGet((invocation, value) -> record(invocation.getFieldName()))
            .build());

    // A catch block. Subclasses of the named exception match too.
    registry.register(
        interceptCatch("com.example.Repository", "java.sql.SQLException")
            .onCatch(invocation -> report(invocation.getCaughtException()))
            .build());
}
```

`interceptField` produces a definition with `AdviceMode.FIELD` whose interceptor implements `FieldInterceptor`; `interceptCatch` produces `AdviceMode.CATCH` and a `CatchInterceptor`. That pairing is what the transformer and the advice both check, so building it by hand means getting both halves right. Prefer the builder.

Field selection happens inside the advice, through `FieldInterceptor.fieldPointcuts()`. The definition's method matcher only decides which classes get woven, which is why it matches every method of the target class.

### Reporting what you observed

These services are static and live in `weaver-girl-api`, so a plugin can use them without holding any engine object. They are process-wide: every plugin reads and writes the same instances.

| Need | API |
|------|-----|
| Spans, baggage, trace context across a call | `Tracer` — `startSpan()`, `endSpan(operation, status)`, `inject` / `extract`, `setBaggage` |
| A metric with a time window and percentiles | `MetricRegistry.record(name, value)`, `MetricRegistry.snapshot(name)` |
| Ship events to your own backend | implement `DataExporter`, then `ExporterRegistry.register` and `activate` |
| A service calling another | `TopologyGraph.recordCall(source, target, protocol, durationMs, error)` |
| Fire when a metric crosses a line | `AlertEngine.addRule`, `AlertEngine.addChannel` |
| Tell every other plugin that something happened | `InterceptorEventPublisher.getInstance().publish(...)` |
| Track untrusted input to a dangerous call | `Taint`, `TaintPropagation`, `TaintFindings` |
| Filter, redact or enrich spans before anything consumes them | `PluginContext.registerSpanProcessor(SpanProcessor)` |
| Receive every completed span (own APM backend) | `PluginContext.registerSpanSink(SpanSink)` |
| Produce Prometheus metrics at scrape time | `PluginContext.registerMetricSource(MetricSource)` |
| Expose a latency distribution as a Prometheus histogram | `MetricCollector.histogram(name, help, bounds, cumulativeCounts, sum)` inside your source |
| Add runtime metadata to OTLP resource attributes | `PluginContext.registerResourceAttributes(provider)` |

The last four are the observability extension points. Register them through the `PluginContext`
you were given, not through the static helpers, so the engine withdraws them when your plugin is
unloaded or hot-replaced. A `SpanProcessor` runs inside `Tracer.endSpan` before any consumer, so
returning `null` drops the span for the built-in exporter and every sink at once; it also runs
synchronously on the application thread, so keep it to attribute work only.
`SpanSink.onSpanEnd` runs on that same thread after the processor chain — queue the record and ship
it from your own thread rather than doing IO inline. Metrics sources are pulled on every scrape and
need no thread. Full rules, precedence, and a complete example plugin are in
[observability.md §9](observability.md#9-扩展点插件自定义可观测) and
[examples/CustomObservabilityPlugin.java](examples/CustomObservabilityPlugin.java).

An exporter is how your product gets data out. Registering it is not enough; it also has to be activated:

```java
public class MyBackendExporter implements DataExporter {
    @Override public String name() { return "my-backend"; }

    @Override public void export(InterceptorEvent event) {
        // Non-blocking. This is called for every event.
    }
}

ExporterRegistry.register(new MyBackendExporter());
ExporterRegistry.activate("my-backend");
```

`AlertEngine.evaluate(metric, value)` returns the alerts that tripped and delivers them to every registered channel, so call it from the same place you record the metric.

### Taint tracking for an IAST product

`Taint` stores the origin of a value, `TaintPropagation` carries that origin through string operations, and the sinks decide when a tainted value reaching a dangerous call is a finding.

```java
Taint.openScope();
try {
    Taint.tag(request.getParameter("q"), Taint.PARAMETER_PREFIX + "q");
    // propagation hooks copy the tag onto derived strings
    if (Taint.isTainted(sql)) {
        TaintFindings.install();
    }
} finally {
    Taint.closeScope();
}
```

`TaintFindings.install()` publishes each finding as an `InterceptorEvent` of type `TaintFindings.EVENT_TYPE` (`"iast-finding"`), so the same listener and exporter path that carries everything else carries findings too. Call it once, at startup.

### Platform lifecycle

`PluginLoader` calls the hooks in this order, and the order matters:

1. **Discovery** — `ServiceLoader` reads `META-INF/services/com.github.cc11001100.weavergirl.api.plugin.WeaverPlugin` from your jar.
2. **Dependency order** — plugins are sorted by `depends()`, across jars. A plugin in your jar that depends on one in another jar still waits for it.
3. **Contract check** — a jar that declares `plugin.apiVersion` newer than the running agent is refused and not activated. Jars that declare nothing are loaded.
4. **`init(PluginContext)`** — always called, and called *before* the enable check.
5. **`isEnabled(PluginContext)`** — return `false` and the plugin is recorded as inactive, not as a failure. Its interceptors are not registered.
6. **`registerInterceptors`** — only when enabled. Optional: the default is a no-op, so a capability-only plugin does not need an empty method.
7. **`destroy()`** — at agent shutdown.

Because `init` runs even when the plugin is switched off, it must not start servers, open sockets, or register anything that `destroy` would have to undo. Read configuration there; do the real work in `registerInterceptors` or behind the `isEnabled` check.

```java
@Override
public void init(PluginContext context) {
    this.threshold = context.getConfigLong("slowThreshold", 1000L);
}

@Override
public boolean isEnabled(PluginContext context) {
    return context.getConfig("enabled", "true").equals("true");
}
```

`name()` is the key everything else uses: configuration is scoped by it, `depends()` refers to it, and `disabledPlugins` / `enabledPlugins` match the jar by it. Keep it stable.

### Offering a service to other plugins

`depends()` orders initialization, but it also names the other plugin. A capability other plugins should be able to build on is a service instead: you publish an implementation of an interface, and anyone who wants it looks the interface up. Neither side names the other, so a product adds or removes the capability by adding or removing the plugin that provides it.

The interface has to live where both plugins can see it. Each plugin jar is loaded by its own class loader, and the only classes every plugin shares are `weaver-girl-api`. A type that belongs to one product does not have to move there: the plugin that owns it names the package in `plugin.exports`, and a plugin that names the owner in `depends()` can load exactly those packages and nothing else the owner defines. A plugin that does not depend on the owner cannot see the package at all, and exporting `com.example.clock` does not expose `com.example.clock.internal`.

An entry written as `com.example.clock.Clock class` exports that one class instead of its package, so the implementation sitting next to the interface stays hidden. Without the `class` marker the entry is a package.

```
META-INF/weaver-girl-plugin.properties
    plugin.name=clock
    plugin.exports=com.example.clock, com.example.clock.Clock class
```

```java
@Override
public String[] depends() {
    return new String[] {"clock"};
}
```

```java
@Override
public void init(PluginContext context) {
    context.registerService(Clock.class, new SystemClock());
}
```

```java
for (Clock clock : PluginServices.get(Clock.class)) {
    clock.now();
}
```

`PluginServices.get` returns every provider registered so far, in registration order, or an empty list when there is none. Registering the same instance twice publishes it once. A service registered through the context is withdrawn when the plugin is unloaded or destroyed, so a consumer that asks afterwards is not handed a plugin that has already gone. A registration made on `PluginServices` directly is not tracked that way; the plugin has to `unregister` it from `destroy()` itself.

### Depending on another plugin

`depends()` names plugins that must be initialised before yours, and it holds across jars — your product jar can depend on a built-in plugin and be sure that plugin's `init` has finished first. A dependency that was not loaded at all is logged and does not stop your plugin from loading.

```java
@Override
public String[] depends() {
    return new String[] {"jdbc"};
}
```

`requires()` is the stricter form. A plugin that requires something not loaded is not activated at all — its `init` never runs — because starting without it means intercepting calls it cannot handle. A plugin that requires one of those is left out too, so a chain drops from the missing end. A plugin that only `depends()` on the missing one still loads.

The requirement is checked again after loading. Unloading a plugin unloads every active plugin that requires it, and then the plugins that required those, for the same reason: they would keep intercepting calls they declared they cannot handle. Deleting or replacing the jar does the same, because that is just the other way a plugin goes away. A plugin that only `depends()` on the unloaded one stays. The requirement is on the name, so it is still met while any other plugin of that name remains active, and unloading one of two plugins that share a name removes nothing else.

Disabling is not unloading: the plugin stays loaded and can be enabled again. A plugin that requires a disabled one is suspended rather than unloaded — its interceptors are removed, but the instance is kept — and enabling the requirement brings it back, along with anything suspended because of it. Enabling the suspended plugin on its own does nothing while the requirement is still disabled. A plugin disabled directly is not suspended, so it stays disabled until someone enables it.

A plugin described entirely by its jar, with no code of its own overriding `requires()`, states the same requirement in the metadata file. The two are added together when a plugin states both.

```
META-INF/weaver-girl-plugin.properties
    plugin.requires=jdbc, redis
```

```java
@Override
public String[] requires() {
    return new String[] {"jdbc"};
}
```

### Declaring the contract you built against

Put this next to the SPI file. `plugin.apiVersion` is the `WeaverPlugin.API_VERSION` you compiled against; the loader compares it with the running agent's and refuses the jar when the agent is older, instead of letting it fail halfway through `init`. Leave it out and the jar is loaded unchecked, which is how the built-in jars ship.

```
META-INF/weaver-girl-plugin.properties
    plugin.name=my-plugin
    plugin.version=1.0.0
    plugin.apiVersion=1.0.0
    plugin.minimumAgentVersion=1.0.0
    plugin.exports=com.example.clock, com.example.clock.Clock class
```

`plugin.minimumAgentVersion` is the agent release you need, as opposed to the contract version. Both are checked; either one failing keeps the plugin out.

### Logging

Use `context.getLogger()`. A drop-in jar has its own class loader, so a logger it creates itself binds to the application's logging and can be silent. The logger from the context is the agent's, named `weavergirl.plugin.<name>`.

```java
@Override
public void init(PluginContext context) {
    context.getLogger().info("starting");
}
```

### Testing the plugin in-process

The agent path above is the real acceptance test, but it is slow: build, copy the jar, launch an
application, read the log. A unit test usually wants a narrower answer — *does this callback fire,
and what does it do to the call?* `weaver-girl-testkit` attaches the instrumentation agent to the
test JVM, runs the plugin through the same lifecycle, and weaves only the classes the test names.

```xml
<dependency>
    <groupId>com.github.cc11001100</groupId>
    <artifactId>weaver-girl-testkit</artifactId>
    <version>1.0.0-SNAPSHOT</version>
    <scope>test</scope>
</dependency>
```

```java
@Test
void renderMasksTheTicket() {
    try (PluginTestHarness harness = PluginTestHarness.create()
            .config("ticket-redaction", "logMasked", "true")
            .install(new RedactionPlugin())) {

        harness.weave(TicketStore.class);

        TicketStore store = new TicketStore();
        store.file("Ada", "card 4111 1111 1111 1111");
        assertEquals("card 4111 ****** 1111", store.render(1));
    }
}
```

`create()` attaches the agent; a JVM that forbids self-attachment makes it throw
`IllegalStateException`, which a test can turn into an assumption. Configuration is set *before*
`install`, because `init` reads it there. `install` itself follows the agent's order: `init`,
`isEnabled`, the `instrumentedLibraries()` check, then `registerInterceptors`; `isActive(name)`
reports whether the plugin got that far. `weave(Class...)` retransforms the named classes and
*fails* when none of the registered definitions matched one of them, so a matcher typo surfaces as a
test failure instead of a green test that asserted nothing. Use `retransform` when a class is
deliberately expected not to match.

The harness exposes `registry()` for definitions registered directly and `context(name)` for the
context the plugin was initialized with. One harness owns the JVM's interceptor registry while it is
open, so do not open two at once. `close()` destroys the plugins newest first, withdraws the services
they published, restores every woven class, and puts the global switch, sampling rate and registry
back, so the next test starts clean.

This does not replace the plugins-directory run: the harness loads the plugin on the test's class
path, so it cannot prove that a plugin only touches classes it is allowed to see, nor that the jar
carries the SPI and capabilities files. Keep one end-to-end run per release for those, and use the
harness for the callbacks.

### Shipping the jar

A third-party plugin needs two files inside the jar and nothing else:

```
META-INF/services/com.github.cc11001100.weavergirl.api.plugin.WeaverPlugin
    com.example.myproduct.MyPlugin

META-INF/weavergirl-capabilities
    my-plugin
```

The service file is what `ServiceLoader` discovers. The capabilities file lists one plugin `name()` per line and is how the agent decides whether to load the jar at all: `enabledPlugins` is an allowlist when set, otherwise `disabledPlugins` excludes. A jar that carries neither file is treated as a drop-in and always loaded.

Put the jar in the `plugins/` directory next to the agent jar and point the agent at it with `plugins=<dir>`. The directory is scanned while the agent boots, before the transformer is installed, so classes the application loads afterwards are woven on their first load. It is also watched afterwards: a jar copied into the directory while the process is running is loaded once the copy has finished, a jar deleted from it unloads the plugin it contributed, interceptors included, and a jar overwritten in place is reloaded — the previous version's plugins are unloaded and the new version loaded. A jar whose content has not changed is not loaded again. Classes loaded before the change are retransformed by the engine itself, so a plugin added, replaced or removed at runtime applies to them too, not only to classes loaded afterwards. The jar is not shaded into the agent, and it must not bundle `weaver-girl-api` — the agent supplies that, and bundling your own copy defeats the parent-first delegation that keeps the contract to a single copy.

`scripts/run-audit-demo.sh` is a working example of exactly this. `payment-audit` (`weaver-girl-sample`, classifier `audit-plugin`) watches `com.example.payments.PaymentService` by name, masks card numbers, records charged cents through `MetricRegistry` and ships each line through its own `DataExporter`. The service and the demo never reference the plugin. Under the agent the demo exits 0 with three audit lines; without it, it exits 2.

`scripts/run-scenario-demos.sh` shows the same loading path doing something to the program instead of only observing it. `ticket-redaction` (classifier `redaction-plugin`) rewrites the return value of `com.example.support.TicketStore.render`, so the card number and phone number a support agent would paste into chat come back masked while the stored ticket is untouched. `gateway-resilience` (classifier `resilience-plugin`) wraps `com.example.gateway.PaymentGateway.charge`: after three consecutive failures it answers the next charge with a queued fallback and never calls the provider. Both applications exit 0 under the agent and 2 without it.

The registry keeps one definition per name, so two definitions built for the same plugin, class and method collapse into whichever was registered last unless each is given its own name. Which plugin an interceptor belongs to is recorded as it is registered, whichever registry the call lands on and whichever thread makes it — including a registration a plugin makes later from a thread it started itself. The call is credited to the plugin whose class made it. A registration that never passes through the plugin's own classes — one delegated to a helper class — is credited too, once the plugin has named that class with `DefaultInterceptorRegistry.track(this, helper)`. The class is named exactly, so the claim does not extend to anything else the helper's loader defines: another plugin's classes stay that plugin's even when both were loaded by the same loader. Several plugins may name the same helper class, and each keeps its own claim; a registration is credited to the plugin whose own class sits nearest the call, so the plugin that declared the helper last does not take the earlier one's registrations. The credit is decided from the class that actually ran, not from the class name on the frame, so two plugins that happen to ship a class of the same name — each in its own jar and its own loader — are still told apart, and so are several plugins loaded from one jar. A plugin that is unloaded drops its claim, and the others keep theirs. On a Java 8 runtime, which has no `StackWalker`, the class object of a frame comes from `SecurityManager.getClassContext()` instead, so two plugins that ship a class of the same name are still told apart. Only a runtime where that call also fails falls back to the class name, and then the first plugin loader that defined a class of that name stands for the frame. Without the declaration, a call made entirely from outside the plugin cannot be told apart from one the application made itself. Disabling or unloading a plugin removes exactly the interceptors that plugin registered, whatever they are named. Two plugins may share a name, and each keeps its own interceptors: disabling the name disables every plugin that carries it, but neither loses track of what the other registered. Leave the name to the builder unless there is a reason not to. An interceptor that has to decide whether a call proceeds and also see how it ended is therefore one definition with all three callbacks:

```java
registry.register(
    intercept("com.example.gateway.PaymentGateway")
        .method("charge")
        .around(this::guard, this::onSuccess, this::onFailure)
        .build());
```

`guard` runs before the call and skips it by not calling `proceed()`. `onSuccess` and `onFailure` run after it, on the advice's exit path. The two-callback `around(before, after)` cannot do this: `skipMethod()` there does not stop the method body.
