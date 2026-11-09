# 插件健康检查与性能监控实现总结

## 概述

本次实现为 weaver-girl 插件系统添加了完整的健康检查和性能监控机制，包括：

1. **插件性能追踪器** - 记录每个插件的调用次数、错误次数、平均耗时
2. **插件健康评估** - 基于性能指标自动评估插件健康状态
3. **Prometheus指标导出** - 暴露插件性能指标用于监控告警
4. **PluginInfo增强** - 包含性能指标的插件信息

## 核心组件

### 1. PluginPerformanceTracker (API)

位置：`weaver-girl-api/src/main/java/com/github/cc11001100/weavergirl/api/plugin/PluginPerformanceTracker.java`

功能：
- 线程安全地记录每个插件的拦截器调用统计
- 统计指标：调用次数、错误次数、平均耗时、最后调用时间、最后错误时间
- 提供 `PluginMetricsSnapshot` 不可变快照

关键方法：
```java
// 通过插件实例记录
public static void recordInvocation(WeaverPlugin plugin, long durationMs, boolean error)

// 通过插件名称记录（InterceptAdvice 调用）
public static void recordInvocationByName(String pluginName, long durationMs, boolean error)

// 获取插件指标快照
public static PluginMetricsSnapshot getMetrics(String pluginName)

// 获取所有插件指标
public static ConcurrentMap<String, PluginMetricsSnapshot> getAllMetrics()
```

### 2. InterceptorHolder 集成

位置：`weaver-girl-core/src/main/java/com/github/cc11001100/weavergirl/core/InterceptorHolder.java`

在 `recordOutcome` 方法中添加了插件性能追踪：
```java
public static void recordOutcome(String interceptorName, boolean success, long durationNanos) {
    circuitBreaker.recordOutcome(interceptorName, success, durationNanos);
    // 记录插件级性能指标
    try {
        String pluginName = extractPluginName(interceptorName);
        if (pluginName != null) {
            PluginPerformanceTracker.recordInvocationByName(
                pluginName, durationNanos / 1_000_000, !success);
        }
    } catch (Throwable t) {
        LOG.debug("Failed to record plugin performance metrics: {}", t.getMessage());
    }
}
```

插件名提取逻辑：
- 从拦截器定义名称中提取：`pluginName-classPattern-methodPattern`
- 处理 field/catch 特殊标记：`plugin-field-fieldName`、`plugin-catch-exceptionType`
- 识别最后一个 `-` 前的部分作为插件名

### 3. DefaultPluginManager 健康检查

位置：`weaver-girl-core/src/main/java/com/github/cc11001100/weavergirl/core/plugin/DefaultPluginManager.java`

新增功能：
- 定期健康检查调度器（默认30秒）
- 基于性能指标的自动健康评估
- 可配置的阈值（系统属性）

配置参数：
- `weavergirl.plugin.healthCheckInterval` - 健康检查间隔（秒），默认30
- `weavergirl.plugin.degradedErrorRate` - 降级错误率阈值，默认0.05 (5%)
- `weavergirl.plugin.unhealthyErrorRate` - 不健康错误率阈值，默认0.20 (20%)
- `weavergirl.plugin.degradedDurationMs` - 降级耗时阈值（毫秒），默认100

健康评估逻辑：
```java
private void evaluatePluginHealth() {
    for (PluginEntry entry : pluginEntries.values()) {
        if (entry.state != PluginState.ACTIVE) continue;
        
        entry.refreshMetrics();
        PluginMetricsSnapshot metrics = entry.metrics;
        
        // 样本数不足（<10）则报告健康
        if (metrics == null || metrics.getInvocationCount() < 10) {
            PluginHealthRegistry.reportHealthy(entry.plugin.name());
            continue;
        }
        
        double errorRate = metrics.getErrorRate();
        double avgDuration = metrics.getAvgDurationMs();
        
        // 错误率 ≥ 20% -> UNHEALTHY
        if (errorRate >= unhealthyErrorRateThreshold) {
            PluginHealthRegistry.reportUnhealthy(...);
        }
        // 错误率 ≥ 5% 或 耗时 ≥ 100ms -> DEGRADED
        else if (errorRate >= degradedErrorRateThreshold 
                || avgDuration >= degradedDurationThresholdMs) {
            PluginHealthRegistry.reportDegraded(...);
        }
        // 否则 HEALTHY
        else {
            PluginHealthRegistry.reportHealthy(...);
        }
    }
}
```

### 4. PluginInfo 增强

位置：`weaver-girl-api/src/main/java/com/github/cc11001100/weavergirl/api/plugin/PluginInfo.java`

新增字段：
```java
private final long invocationCount;      // 总调用次数
private final long errorCount;          // 总错误次数
private final double avgDurationMs;     // 平均耗时（毫秒）
private final long lastInvocationAt;    // 最后调用时间戳
```

提供向后兼容的构造函数。

### 5. Prometheus指标导出

位置：`weaver-girl-runtime-plugins/weaver-girl-runtime-prometheus/src/main/java/com/github/cc11001100/weavergirl/runtime/metrics/PrometheusExporter.java`

新增指标：

```
# 插件调用总数
weavergirl_plugin_invocations_total{plugin="jdbc"} 1523

# 插件错误总数
weavergirl_plugin_errors_total{plugin="jdbc"} 45

# 插件错误率
weavergirl_plugin_error_rate{plugin="jdbc"} 0.0295

# 插件平均耗时
weavergirl_plugin_avg_duration_ms{plugin="jdbc"} 23.45
```

## 使用示例

### 1. 查看插件性能指标

```bash
curl -s http://localhost:9400/metrics | grep weavergirl_plugin_
```

### 2. 查看插件健康状态

```bash
curl -s http://localhost:9400/metrics | grep weavergirl_plugin_health
```

### 3. 通过 PluginManager 获取插件信息

```java
PluginManager pm = PluginManager.getInstance();
Optional<PluginInfo> info = pm.getPluginInfo("jdbc");
if (info.isPresent()) {
    System.out.println("Invocations: " + info.get().getInvocationCount());
    System.out.println("Error rate: " + info.get().getErrorRate());
    System.out.println("Avg duration: " + info.get().getAvgDurationMs() + "ms");
}
```

### 4. 自定义健康检查阈值

```bash
java -javaagent:weaver-girl-agent.jar=\
metricsPort=9400,\
-Dweavergirl.plugin.healthCheckInterval=60,\
-Dweavergirl.plugin.degradedErrorRate=0.10,\
-Dweavergirl.plugin.unhealthyErrorRate=0.30,\
-Dweavergirl.plugin.degradedDurationMs=200 \
-jar app.jar
```

## 测试

### 单元测试

- `PluginPerformanceTrackerTest` - 测试性能追踪器核心功能
- `PluginHealthCheckIntegrationTest` - 测试健康检查集成

### 测试覆盖

- 记录正常调用和错误调用
- 错误率计算
- 与 PluginHealthRegistry 集成
- 通过插件名称记录（模拟 InterceptorHolder 调用）

## 监控告警建议

### Prometheus 告警规则

```yaml
groups:
  - name: weaver-girl-plugins
    rules:
      # 插件错误率过高
      - alert: PluginHighErrorRate
        expr: weavergirl_plugin_error_rate > 0.1
        for: 5m
        annotations:
          summary: "Plugin {{ $labels.plugin }} error rate high"
          
      # 插件平均耗时过高
      - alert: PluginSlowCallback
        expr: weavergirl_plugin_avg_duration_ms > 100
        for: 5m
        annotations:
          summary: "Plugin {{ $labels.plugin }} callbacks slow"
          
      # 插件不健康
      - alert: PluginUnhealthy
        expr: weavergirl_plugin_health{status="unhealthy"} == 1
        annotations:
          summary: "Plugin {{ $labels.plugin }} is unhealthy"
          
      # 插件降级
      - alert: PluginDegraded
        expr: weavergirl_plugin_health{status="degraded"} == 1
        annotations:
          summary: "Plugin {{ $labels.plugin }} is degraded"
```

## 性能影响

- **开销**：每次拦截器调用增加约 50-100ns（线程安全的 LongAdder 操作）
- **内存**：每个插件约 200 字节（几个 AtomicLong 字段）
- **健康检查**：独立线程执行，不影响业务线程
- **零成本开关**：未配置 `metricsPort` 时 Prometheus 插件不加载，完全零开销

## 六、可观测性扩展点优先级机制

### 1. SpanProcessor 优先级

**位置**：`weaver-girl-api/src/main/java/com/github/cc11001100/weavergirl/api/tracing/SpanProcessor.java`

**改进**：
- 添加 `priority()` 方法，默认返回 0
- 支持负优先级（噪声过滤）、正优先级（元数据补充）

**优先级建议**：
- -100 到 -10：噪声过滤（health check、心跳）
- -9 到 -1：必须执行的治理（脱敏、权限检查）
- 0：常规治理（元数据补充、标签补充）
- 1 到 10：数据丰富（业务上下文）
- 11 到 100：可选增强（性能分析、慢调用追踪）

### 2. MetricSource 优先级

**位置**：
- `weaver-girl-api/src/main/java/com/github/cc11001100/weavergirl/api/observability/MetricSource.java` - 添加 priority() 方法
- `weaver-girl-api/src/main/java/com/github/cc11001100/weavergirl/api/observability/MetricSources.java` - 按优先级排序

**改进**：
- MetricSource 添加 `priority()` 方法，默认返回 0
- 注册后按优先级排序
- 允许低优先级设置基准值，高优先级覆盖

### 3. SpanProcessors 排序

**位置**：`weaver-girl-api/src/main/java/com.github.cc11001100/weavergirl/api/tracing/SpanProcessors.java`

**改进**：
- Holder 添加 priority 字段
- 注册后按优先级排序
- 低优先级先执行，高优先级后执行

1. **插件降级自动处理** - 根据健康状态自动禁用不健康的插件
2. **性能历史** - 记录性能指标的历史趋势
3. **插件依赖健康** - 依赖的插件不健康时也影响依赖者
4. **热配置更新** - 运行时调整健康检查阈值
5. **更多维度** - 按拦截器定义级别记录性能指标

## 七、扩展点级别性能指标与健康组件扩展

在插件级性能之上（见上文），本次把同类计数下沉到**扩展点实例**，并补齐可观测插件的两个
扩展形态。

### 1. ExtensionPerformanceTracker - 扩展点级性能

**位置**：`weaver-girl-api/src/main/java/com/github/cc11001100/weavergirl/api/observability/ExtensionPerformanceTracker.java`

- 按 `kind + name` 累计：调用次数、异常次数、总耗时、末次调用/异常时间
- 已接入的扩展点：`span-processor`、`span-sink`、`metric-source`、`health-check`、
  `tag-enricher`。每个扩展点在调用点用 `record(kind, name, nanos, error)` 计一次
- 指标采集永不外抛：`record` 内部吞掉任何异常，坏掉的扩展点不能反过来拖垮被插桩的管线
- 插件卸载时 `DefaultPluginContext.withdrawServices()` 摘除自己注册的扩展点并清掉对应计数器

**Prometheus 输出**（见 `docs/observability.md` 4.2.7）：

```text
weavergirl_extension_invocations_total{kind="span-sink",extension="my-apm"} 120
weavergirl_extension_errors_total{kind="span-sink",extension="my-apm"} 3
weavergirl_extension_error_rate{kind="span-sink",extension="my-apm"} 0.0250
weavergirl_extension_avg_duration_ms{kind="span-sink",extension="my-apm"} 0.412
```

### 2. HealthCheckExtension - 组件健康扩展

**位置**：
- `weaver-girl-api/src/main/java/com/github/cc11001100/weavergirl/api/observability/HealthCheckExtension.java`
- `weaver-girl-api/src/main/java/com/github/cc11001100/weavergirl/api/observability/HealthChecks.java`

- `checkHealth()` 返回 `HealthCheckResult`，默认 `UP`；`name()` 默认类名
- 通过 `context.registerHealthCheckExtension(extension)` 注册，组件名 = `插件名/扩展名`，
  两个插件上报同名组件互不覆盖
- 与普通 `HealthCheck` 在同一次 `checkAll()` 中评估：前者先执行，组件名冲突先注册者胜出
- 抛异常或返回 `null` 只把自己标成 `DOWN`；每次评估都计入 `kind="health-check"` 的性能计数

### 3. TagEnricher - 独立标签增强扩展点

**位置**：
- `weaver-girl-api/src/main/java/com/github/cc11001100/weavergirl/api/observability/TagEnricher.java` - 增加 `priority()`（默认 0）
- `weaver-girl-api/src/main/java/com/github/cc11001100/weavergirl/api/observability/TagEnrichers.java` - 独立注册表
- `weaver-girl-api/src/main/java/com/github/cc11001100/weavergirl/api/tracing/Tracer.java` - `endSpan` 接入

- 不再需要包装成 `SpanProcessor`：`context.registerTagEnricher(enricher)` 直接注册
- 在 `Tracer.endSpan` 中**早于** `SpanProcessors` 链执行（先补标签、后治理/脱敏）
- 按 `priority()` 升序执行，同优先级保持注册顺序；后执行的增强器能看到前者写入的属性
- 返回 `null`/空 map 不修改；抛异常只跳过自己并计入 `kind="tag-enricher"` 计数

## 文件变更清单

### 新增文件
- `weaver-girl-api/src/main/java/com/github/cc11001100/weavergirl/api/plugin/PluginPerformanceTracker.java`
- `weaver-girl-api/src/test/java/com/github/cc11001100/weavergirl/api/plugin/PluginPerformanceTrackerTest.java`
- `weaver-girl-core/src/test/java/com/github/cc11001100/weavergirl/core/plugin/PluginHealthCheckIntegrationTest.java`
- `weaver-girl-api/src/main/java/com/github/cc11001100/weavergirl/api/observability/ExtensionPerformanceTracker.java` - 扩展点级性能
- `weaver-girl-api/src/main/java/com/github/cc11001100/weavergirl/api/observability/ExtensionStats.java` - 性能快照
- `weaver-girl-api/src/main/java/com/github/cc11001100/weavergirl/api/observability/HealthCheckExtension.java` - 组件健康扩展
- `weaver-girl-api/src/main/java/com/github/cc11001100/weavergirl/api/observability/TagEnrichers.java` - 标签增强注册表
- `weaver-girl-api/src/test/java/com/github/cc11001100/weavergirl/api/observability/ExtensionPerformanceTrackerTest.java`
- `weaver-girl-api/src/test/java/com/github/cc11001100/weavergirl/api/observability/HealthCheckExtensionTest.java`
- `weaver-girl-api/src/test/java/com/github/cc11001100/weavergirl/api/observability/TagEnrichersTest.java`
- `weaver-girl-core/src/test/java/com/github/cc11001100/weavergirl/core/plugin/PluginContextEnricherAndHealthExtensionTest.java`
- `weaver-girl-core/src/test/java/com/github/cc11001100/weavergirl/core/integration/ContextPropagationIntegrationTest.java` - 上下文传播织入集成测试
- `docs/extension-point-guide.md` - 扩展点开发指南

### 修改文件
- `weaver-girl-api/src/main/java/com/github/cc11001100/weavergirl/api/plugin/PluginInfo.java` - 增加性能指标字段
- `weaver-girl-core/src/main/java/com/github/cc11001100/weavergirl/core/InterceptorHolder.java` - 集成性能追踪
- `weaver-girl-core/src/main/java/com/github/cc11001100/weavergirl/core/plugin/DefaultPluginManager.java` - 健康检查调度
- `weaver-girl-runtime-plugins/weaver-girl-runtime-prometheus/src/main/java/com/github/cc11001100/weavergirl/runtime/metrics/PrometheusExporter.java` - 导出插件与扩展点性能指标
- `weaver-girl-api/src/main/java/com/github/cc11001100/weaver-girl/api/tracing/SpanProcessor.java` - 添加 priority() 方法
- `weaver-girl-api/src/main/java/com/github/cc11001100/weavergirl/api/tracing/SpanProcessors.java` - 按优先级排序，扩展点计时
- `weaver-girl-api/src/main/java/com/github/cc11001100/weavergirl/api/observability/MetricSource.java` - 添加 priority() 方法
- `weaver-girl-api/src/main/java/com/github/cc11001100/weavergirl/api/observability/MetricSources.java` - 按优先级排序
- `weaver-girl-api/src/main/java/com/github/cc11001100/weavergirl/api/observability/HealthChecks.java` - 集成 HealthCheckExtension
- `weaver-girl-api/src/main/java/com/github/cc11001100/weavergirl/api/observability/SpanSinks.java` - 扩展点计时
- `weaver-girl-api/src/main/java/com/github/cc11001100/weavergirl/api/observability/TagEnricher.java` - 添加 priority() 方法
- `weaver-girl-api/src/main/java/com/github/cc11001100/weavergirl/api/tracing/Tracer.java` - endSpan 接入 TagEnrichers
- `weaver-girl-api/src/main/java/com/github/cc11001100/weavergirl/api/plugin/PluginContext.java` - 新增 registerHealthCheckExtension/registerTagEnricher
- `weaver-girl-core/src/main/java/com/github/cc11001100/weavergirl/core/plugin/DefaultPluginContext.java` - 记录并卸载新的扩展点
