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

## Plugin Lifecycle

1. **Discovery** -- Loaded via `java.util.ServiceLoader`
2. **Dependency Resolution** -- Plugins sorted by `depends()` declarations
3. **Initialization** -- `init(PluginContext)` called with config access
4. **Registration** -- `registerInterceptors(InterceptorRegistry)` called
5. **Runtime** -- Interceptors invoked as matched methods are called
6. **Destroy** -- `destroy()` called at agent shutdown

## Using Plugin Configuration

```java
@Override
public void init(PluginContext context) {
    String threshold = context.getConfig("threshold", "1000");
    // System property: weavergirl.plugin.my-plugin.threshold=500
    // YAML: plugins.my-plugin.threshold: 500
}
```

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

### Cross-Thread Context

```java
.before(inv -> {
    ThreadContext.put("traceId", traceId);
    executor.submit(new ContextRunnable(() -> {
        String id = ThreadContext.get("traceId");
        // ...
    }));
})
```
