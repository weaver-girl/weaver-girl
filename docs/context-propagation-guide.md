# 上下文传播机制开发指南

## 概述

weaver-girl 框架提供了完善的上下文传播机制，支持在切面拦截过程中、跨线程、跨异步边界传递上下文信息。

## 一、上下文传播层次

### 1. 单调用附件层（MethodInvocation Attachment）

**范围**：单次拦截调用的 before -> after

**API**：
```java
invocation.setAttachment("startTime", System.nanoTime());
Long start = (Long) invocation.getAttachment("startTime");
```

### 2. ThreadLocal 层（ThreadContext）

**范围**：单线程内所有拦截器

**API**：
```java
ThreadContext.put("traceId", "abc123");
String traceId = ThreadContext.get("traceId");
ThreadContext.clear();
```

### 3. 全局上下文层（GlobalContext）

**范围**：整个 JVM 进程，所有线程

**API**：
```java
GlobalContext.put("counter", new AtomicLong(0));
AtomicLong counter = GlobalContext.get("counter");
```

### 4. 跨线程传播层（ContextSnapshot/ContextScope）

**范围**：跨线程、跨异步边界

**API**：
```java
ContextSnapshot snapshot = ContextSnapshot.capture();
try (ContextScope scope = ContextScope.activate(snapshot)) {
    // 使用上下文
}
executor.submit(ContextPropagators.wrap(() -> {
    String traceId = ThreadContext.get("traceId"); // 自动传播
}));
```

### 5. 跨进程传播层（Tracer.inject/extract）

**范围**：跨进程、跨服务

**API**：
```java
Tracer.inject(span, headers);
SpanContext extracted = Tracer.extract(headers);
```

## 二、内置上下文传播器

| Propagator | 优先级 | 功能 |
|-----------|--------|------|
| ThreadContextPropagator | -200 | ThreadContext 传播 |
| TracerPropagator | -100 | Tracer span 传播 |
| TenantContextPropagator | -50 | 租户上下文传播 |
| MdcPropagator | -40 | MDC 日志上下文传播 |
| TaintContextPropagator | 0 | 污点追踪上下文传播 |

## 三、Executor 集成

```java
ExecutorService contextExecutor = ContextExecutorService.wrap(rawExecutor);
contextExecutor.submit(() -> {
    String traceId = ThreadContext.get("traceId"); // 自动传播
});
```

## 四、MDC 自动注入

框架自动将 ThreadContext 中的 trace/span/tenant 注入到 SLF4J MDC，无需手动调用。

## 五、最佳实践

1. **选择合适的传播层**：单次调用用 Attachment，同线程用 ThreadContext，跨线程用 ContextPropagators，进程级用 GlobalContext
2. **防止 ThreadLocal 泄漏**：使用 ContextScope + try-with-resources 或 ContextPropagators.wrap()
3. **异步上下文传播**：使用 ContextExecutorService 或 ContextCompletableFuture

## 六、总结

weaver-girl 框架提供了完善的上下文传播机制，满足切面底座框架的基础能力需求。
