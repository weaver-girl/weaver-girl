# 切面底座上下文传播机制审查报告

## 概述

基于对 weaver-girl 项目的深入审查，发现框架已经具备完善的跨切点上下文传播机制，包括 ThreadLocal、ContextSnapshot、ContextScope、ContextPropagator 等多层次的上下文管理能力。

## 一、现有上下文传播机制

### 1. ThreadContext - ThreadLocal 基础层

**位置**：`weaver-girl-api/src/main/java/com/github/cc11001100/weavergirl/api/context/ThreadContext.java`

**功能**：
- 每个线程拥有独立的上下文（ThreadLocal<Map<String, Object>>）
- 支持 put/get/remove/clear 操作
- 支持 capture() 和 restore() 跨线程传播
- 提供 snapshot() 返回 ContextSnapshot

**关键方法**：
```java
public static void put(String key, Object value)
public static <T> T get(String key)
public static <T> T get(String key, T defaultValue)
public static Map<String, Object> capture()
public static void restore(Map<String, Object> captured)
public static ContextSnapshot snapshot()
```

**使用场景**：
- 在 before() 中存储状态：`ThreadContext.put("traceId", "abc")`
- 在 after() 中读取状态：`ThreadContext.get("traceId")`
- 跨线程传播：`ThreadContext.capture()` + `ThreadContext.restore()`

### 2. ContextPropagator - SPI 扩展层

**位置**：`weaver-girl-api/src/main/java/com/github/cc11001100/weavergirl/api/context/ContextPropagator.java`

**功能**：
- SPI 接口，允许第三方扩展上下文传播
- capture() - 在提交线程捕获状态
- restore() - 在工作线程恢复状态
- cleanup() - 清理/恢复工作线程先前状态
- priority() - 执行优先级

**内置 Propagators**：
- ThreadContextPropagator (priority -200) - ThreadContext 传播
- TracerPropagator (priority -100) - Tracer span 传播
- TenantContextPropagator (priority -50) - 租户上下文传播
- MdcPropagator (priority -40) - MDC 日志上下文传播
- TaintContextPropagator - 污点追踪上下文传播

### 3. ContextSnapshot - 统一快照层

**位置**：`weaver-girl-api/src/main/java/com/github/cc11001100/weavergirl/api/context/ContextSnapshot.java`

**功能**：
- 捕获所有注册的 ContextPropagator 的状态
- 支持 activate() 激活快照
- 支持 isEmpty() 判断
- 提供 getThreadContext() 获取 ThreadContext 部分
- 提供 fromThreadContext() 从 ThreadContext map 创建

**关键方法**：
```java
public static ContextSnapshot capture()
public ContextScope activate()
public boolean isEmpty()
public Map<String, Object> getThreadContext()
public Map<String, Object> values()
```

### 4. ContextScope - 自动管理作用域

**位置**：`weaver-girl-api/src/main/java/com/github/cc11001100/weavergirl/api/context/ContextScope.java`

**功能**：
- AutoCloseable 接口，支持 try-with-resources
- activate() 激活快照，close() 恢复先前状态
- 防止 ThreadLocal 泄漏
- 支持嵌套拦截器流程

**使用方式**：
```java
try (ContextScope scope = ContextScope.activate(snapshot)) {
    // 使用上下文
}  // 自动恢复先前状态
```

### 5. ContextPropagators - 工具类

**位置**：`weaver-girl-api/src/main/java/com/github/cc11001100/weavergirl/api/context/ContextPropagators.java`

**功能**：
- capture() - 捕获当前上下文
- scope() - 激活快照
- wrap(Runnable) - 包装 Runnable
- wrap(Callable) - 包装 Callable
- wrap(Function) - 包装 Function
- wrap(Consumer) - 包装 Consumer
- wrap(BiFunction) - 包装 BiFunction
- wrap(BiConsumer) - 包装 BiConsumer
- wrapCallables() - 批量包装

**使用方式**：
```java
// 包装 Runnable
executor.submit(ContextPropagators.wrap(() -> {
    String traceId = ThreadContext.get("traceId"); // 自动可用
}));

// 包装 Callable
executor.submit(ContextPropagators.wrap(() -> {
    return processData();
}));
```

### 6. ContextExecutorService - Executor 包装

**位置**：`weaver-girl-api/src/main/java/com/github/cc11001100/weavergirl/api/context/ContextExecutorService.java`

**功能**：
- 包装 ExecutorService，自动传播上下文
- submit()、execute()、invokeAll()、invokeAny() 全部支持
- 自动捕获和传播上下文

**使用方式**：
```java
ExecutorService contextExecutor = ContextExecutorService.wrap(rawExecutor);
contextExecutor.submit(() -> {
    String traceId = ThreadContext.get("traceId"); // 自动传播
});
```

### 7. ContextCompletableFuture - CompletableFuture 支持

**位置**：`weaver-girl-api/src/main/java/com/github/cc11001100/weavergirl/api/context/ContextCompletableFuture.java`

**功能**：
- supplyAsync()、runAsync() 支持上下文传播
- 配合 ContextExecutorService 使用

### 8. MethodInvocation - 单调用附件层

**位置**：`weaver-girl-api/src/main/java/com/github/cc11001100/weavergirl/api/interceptor/MethodInvocation.java`

**功能**：
- setAttachment() / getAttachment() - 在 before/after 之间传递状态
- drainAttachments() - 在 onMethodEnter/onMethodExit 之间传递
- 附件在池化时自动清理

## 二、自动上下文传播机制

### 1. InterceptAdvice 自动传播

**位置**：`weaver-girl-core/src/main/java/com/github/cc11001100/weavergirl/core/InterceptAdvice.java`

在 InterceptAdvice 中：
- onMethodEnter：捕获当前 ThreadContext 状态
- onMethodExit：恢复 ThreadContext 状态
- MDC 注入：自动注入 trace/span/tenant 到 MDC
- 附件传递：before 阶段的 attachment 自动传递到 after 阶段

### 2. MdcInjector - MDC 自动注入

**位置**：`weaver-girl-core/src/main/java/com/github/cc11001100/weavergirl/core/context/MdcInjector.java`

**功能**：
- 自动从 ThreadContext 注入 trace/span/tenant 到 SLF4J MDC
- 在方法入口 inject()，出口 restore()
- 确保日志自动关联 trace context

## 三、上下文传播链路

```
Interceptor before()
    │
    ├── ThreadContext.put("key", value)  // 存储到 ThreadLocal
    │
    ├── MethodInvocation.setAttachment("key", value)  // 存储到 invocation
    │
    └── GlobalContext.put("key", value)  // 存储到全局（可选）

Interceptor after()
    │
    ├── ThreadContext.get("key")  // 从 ThreadLocal 读取
    │
    └── MethodInvocation.getAttachment("key")  // 从 invocation 读取

跨线程传播:
    │
    ├── ThreadContext.capture() -> ContextSnapshot
    │
    ├── ContextScope.activate(snapshot)
    │
    └── ContextPropagators.wrap(Runnable)
```

## 四、使用示例

### 1. 单调用附件（before -> after）

```java
@Override
public void before(MethodInvocation invocation) {
    invocation.setAttachment("startTime", System.nanoTime());
}

@Override
public void after(MethodInvocation invocation) {
    Long start = (Long) invocation.getAttachment("startTime");
    long elapsed = System.nanoTime() - start;
}
```

### 2. ThreadLocal 上下文

```java
@Override
public void before(MethodInvocation invocation) {
    ThreadContext.put("traceId", invocation.getArgument(0));
}

@Override
public void after(MethodInvocation invocation) {
    String traceId = ThreadContext.get("traceId");
}
```

### 3. 跨线程传播

```java
@Override
public void before(MethodInvocation invocation) {
    ThreadContext.put("requestId", "req-123");
    
    // 自动传播
    executor.submit(ContextPropagators.wrap(() -> {
        String requestId = ThreadContext.get("requestId"); // 自动可用
        processAsync(requestId);
    }));
}
```

### 4. Executor 包装

```java
ExecutorService contextExecutor = ContextExecutorService.wrap(rawExecutor);

@Override
public void before(MethodInvocation invocation) {
    ThreadContext.put("traceId", "trace-456");
    contextExecutor.submit(() -> {
        // traceId 自动传播
        String traceId = ThreadContext.get("traceId");
    });
}
```

### 5. CompletableFuture

```java
ContextCompletableFuture.supplyAsync(() -> {
    String traceId = ThreadContext.get("traceId"); // 自动传播
    return loadUser(traceId);
}, contextExecutor);
```

## 五、评估结论

### 已具备的能力 ✅

1. **ThreadLocal 上下文** - ThreadContext 提供完整的 ThreadLocal 支持
2. **跨线程传播** - ContextSnapshot、ContextScope、ContextPropagators 提供完整的跨线程传播
3. **Executor 集成** - ContextExecutorService、ContextScheduledExecutorService 自动传播
4. **CompletableFuture 集成** - ContextCompletableFuture 支持
5. **MDC 自动注入** - MdcInjector 自动注入 trace 上下文到日志
6. **attachment 传递** - MethodInvocation 附件在 before/after 之间自动传递
7. **SPI 扩展** - ContextPropagator 允许第三方扩展上下文传播
8. **优先级排序** - Propagators 按优先级执行

### 待完善的问题

1. **ThreadLocal 泄漏风险** - InterceptAdvice 中 ThreadContext 的清理机制需要确认
2. **MDC 快照恢复** - MdcInjector.restore() 需要验证正确性
3. **附件池化清理** - MethodInvocationPool.release() 需要确保 attachment 清理
4. **文档完善** - 需要更详细的上下文传播指南

### 建议

1. **确认 InterceptAdvice 中 ThreadContext 的自动清理** - 检查 onMethodExit 是否调用 ThreadContext.clear()
2. **添加上下文传播指南** - 在文档中详细说明上下文传播的最佳实践
3. **验证 MdcInjector** - 确保 MDC 状态正确恢复
4. **添加测试** - 验证跨线程上下文传播的正确性

## 六、总结

weaver-girl 框架已经具备了完善的上下文传播机制，包括：

- ✅ ThreadLocal 上下文（ThreadContext）
- ✅ 跨线程传播（ContextSnapshot、ContextScope、ContextPropagators）
- ✅ Executor 集成（ContextExecutorService、ContextScheduledExecutorService）
- ✅ CompletableFuture 集成（ContextCompletableFuture）
- ✅ MDC 自动注入（MdcInjector）
- ✅ attachment 传递（MethodInvocation）
- ✅ SPI 扩展（ContextPropagator）
- ✅ 优先级排序（ContextPropagator.priority()）

这些机制已经满足切面底座框架的基础能力需求。后续可以进一步完善文档和测试，确保用户能够正确使用这些能力。