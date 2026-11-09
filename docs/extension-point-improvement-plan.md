# 可观测性扩展点完善方案

## 当前扩展点状态

### 已实现的扩展点（通过 PluginContext 注册）

| 扩展点 | 接口 | 注册方法 | 调用时机 | 用途 |
|--------|------|----------|----------|------|
| SpanProcessor | SpanProcessor | registerSpanProcessor | span结束，消费者之前 | 过滤/脱敏/补属性 |
| SpanSink | SpanSink | registerSpanSink | 处理器链之后 | 自定义APM后端 |
| MetricSource | MetricSource | registerMetricSource | Prometheus抓取时 | 队列/缓存/连接池指标 |
| ResourceAttributeProvider | ResourceAttributeProvider | registerResourceAttributes | OTLP导出时 | 集群/区域/租户元数据 |
| HealthCheck | HealthCheck | registerHealthCheck | 探针/抓取时 | 数据库/缓存/下游健康 |
| AlertChannel | AlertChannel | registerAlertChannel | 规则命中时 | 钉钉/Slack/工单 |
| AlertRule | AlertRule | registerAlertRule | 指标评估时 | 插件指标告警规则 |
| SamplingStrategy | SamplingStrategy | registerSamplingStrategy | 拦截调用时 | 自定义采样策略 |
| InterceptorEventListener | InterceptorEventListener | registerEventListener | 事件发布时 | 订阅拦截事件 |
| DataExporter | DataExporter | registerDataExporter | 事件发布时 | 事件导出到后端 |

### 自动清理机制

`DefaultPluginContext.withdrawServices()` 在插件卸载时自动清理：
- 所有注册的 observability 扩展点
- 插件发布的服务
- 按实例清理，避免误删后来注册的同名实例

## 现状分析

### 优势

1. **完整的扩展点覆盖** - 涵盖了可观测性的所有关键维度
2. **自动生命周期管理** - 插件卸载时自动清理注册的组件
3. **示例完整** - CustomObservabilityPlugin 展示了所有扩展点的使用
4. **文档清晰** - observability.md 第9节详细说明了扩展点

### 待改进点

1. **缺少扩展点优先级机制** - 某些扩展点需要执行顺序
2. **缺少扩展点依赖管理** - 扩展点之间可能有依赖关系
3. **缺少扩展点健康检查** - 扩展点本身可能失效
4. **缺少统一的事件总线** - 事件和指标是分开的两套体系
5. **文档分散** - 扩展点设计理念、最佳实践不够集中

## 完善方案

### 1. 扩展点优先级机制

**目标**：控制扩展点的执行顺序，特别是 SpanProcessor 和 MetricSource

**实现**：在扩展点接口中添加优先级字段

```java
public interface SpanProcessor {
    String name();
    SpanContext process(SpanContext span);
    
    /**
     * Execution priority. Lower values run first. Default is 0.
     * 
     * @return priority value
     */
    default int priority() {
        return 0;
    }
}
```

在注册时按优先级排序：
```java
private final List<SpanProcessor> spanProcessors = new CopyOnWriteArrayList<>();

@Override
public void registerSpanProcessor(SpanProcessor processor) {
    // 按优先级插入
    spanProcessors.add(processor);
    spanProcessors.sort(Comparator.comparingInt(SpanProcessor::priority));
    SpanProcessors.register(processor);
}
```

### 2. 扩展点依赖管理

**目标**：支持扩展点之间的依赖关系，确保依赖先注册

**实现**：添加 `@DependsOn` 注解或接口方法

```java
@Retention(RetentionPolicy.RUNTIME)
@Target(ElementType.TYPE)
public @interface DependsOn {
    String[] value(); // 依赖的扩展点类型
}

public interface SpanProcessor {
    @DependsOn(MetricSource.class)
    String name();
    SpanContext process(SpanContext span);
}
```

或者在 PluginContext 中添加依赖检查：
```java
@Override
public void registerSpanProcessor(SpanProcessor processor) {
    // 检查依赖
    if (processor instanceof DependentExtension) {
        String[] dependencies = ((DependentExtension) processor).dependencies();
        for (String dep : dependencies) {
            if (!isExtensionRegistered(dep)) {
                throw new IllegalStateException(
                    "SpanProcessor " + processor.name() + " depends on " + dep + " which is not registered");
            }
        }
    }
    // 正常注册
}
```

### 3. 扩展点健康检查

**目标**：扩展点本身需要健康检查，确保数据处理不中断

**实现**：添加 `HealthCheckExtension` 接口

```java
public interface HealthCheckExtension {
    /**
     * Check if this extension is healthy.
     * 
     * @return health status
     */
    default HealthCheckResult checkHealth() {
        return HealthCheckResult.up();
    }
}
```

让关键扩展点实现此接口：
```java
public interface SpanSink extends HealthCheckExtension {
    String name();
    void onSpanEnd(SpanRecord span);
    
    @Override
    default HealthCheckResult checkHealth() {
        // 检查 buffer 是否溢出、后端是否可达等
        return HealthCheckResult.up();
    }
}
```

在健康检查报告中包含扩展点状态：
```java
public interface ExtensionHealthReport {
    Map<String, HealthCheckResult> getExtensionHealth();
}
```

### 4. 统一事件总线

**目标**：统一事件和指标的分发机制，避免两套体系

**实现**：创建 `ObservabilityEventBus` 统一事件分发

```java
public final class ObservabilityEventBus {
    private static final List<ObservabilityEventListener> listeners = new CopyOnWriteArrayList<>();
    
    public static void publish(ObservabilityEvent event) {
        for (ObservabilityEventListener listener : listeners) {
            try {
                listener.onEvent(event);
            } catch (Throwable t) {
                // 隔离异常
            }
        }
    }
    
    public static void register(ObservabilityEventListener listener) {
        listeners.add(listener);
    }
    
    public static void unregister(ObservabilityEventListener listener) {
        listeners.remove(listener);
    }
}

public interface ObservabilityEventListener {
    void onEvent(ObservabilityEvent event);
}

public enum ObservabilityEventType {
    SPAN_COMPLETED,
    METRIC_RECORDED,
    HEALTH_CHECK_EVALUATED,
    ALERT_TRIGGERED
}
```

现有扩展点适配：
```java
// SpanSink 适配
public class SpanSinkAdapter implements ObservabilityEventListener {
    private final SpanSink sink;
    
    public SpanSinkAdapter(SpanSink sink) {
        this.sink = sink;
    }
    
    @Override
    public void onEvent(ObservabilityEvent event) {
        if (event.getType() == ObservabilityEventType.SPAN_COMPLETED) {
            sink.onSpanEnd(event.getSpan());
        }
    }
}
```

### 5. 扩展点配置化

**目标**：支持通过配置动态启用/禁用扩展点

**实现**：添加扩展点配置接口

```java
public interface ConfigurableExtension {
    /**
     * Check if this extension is enabled by configuration.
     * 
     * @param context plugin context
     * @return true if enabled
     */
    default boolean isEnabled(PluginContext context) {
        return true;
    }
    
    /**
     * Configuration key for this extension.
     * 
     * @return configuration key, e.g., "spanSink.enabled"
     */
    default String configKey() {
        return null;
    }
}
```

在 PluginContext 中支持条件注册：
```java
@Override
public void registerSpanSink(SpanSink sink) {
    if (sink instanceof ConfigurableExtension) {
        ConfigurableExtension ext = (ConfigurableExtension) sink;
        if (!ext.isEnabled(this)) {
            return;
        }
    }
    SpanSinks.register(sink);
    spanSinks.add(sink);
}
```

### 6. 扩展点指标

**目标**：每个扩展点自动暴露自己的性能指标

**实现**：为扩展点添加统计接口

```java
public interface InstrumentedExtension {
    /**
     * Get performance metrics for this extension.
     * 
     * @return metrics snapshot
     */
    default ExtensionMetrics getMetrics() {
        return ExtensionMetrics.empty();
    }
}

public final class ExtensionMetrics {
    private final long invocations;
    private final long errors;
    private final double avgDurationMs;
    
    // getters...
}
```

在 PrometheusExporter 中添加扩展点指标：
```
# TYPE weavergirl_extension_invocations_total counter
weavergirl_extension_invocations_total{extension="my-sink",type="SpanSink"} 1523
# TYPE weavergirl_extension_errors_total counter
weavergirl_extension_errors_total{extension="my-sink",type="SpanSink"} 2
# TYPE weavergirl_extension_avg_duration_ms gauge
weavergirl_extension_avg_duration_ms{extension="my-sink",type="SpanSink"} 1.5
```

### 7. 扩展点文档集中化

**目标**：创建专门的扩展点开发指南

**实现**：创建 `docs/extension-point-guide.md`

内容包括：
- 扩展点设计哲学
- 每个扩展点的详细说明
- 最佳实践和性能注意事项
- 完整示例代码
- 常见陷阱和解决方案
- 测试扩展点的方法

## 新增扩展点建议

### 1. EventTransformer

**用途**：转换拦截事件，统一事件格式

```java
public interface EventTransformer {
    String name();
    InterceptorEvent transform(InterceptorEvent event);
}
```

### 2. MetricProcessor

**用途**：在指标记录前进行转换或过滤

```java
public interface MetricProcessor {
    String name();
    MetricRecord process(MetricRecord record);
}
```

### 3. TagEnricher

**用途**：自动为 span/metric 添加标签

```java
public interface TagEnricher {
    String name();
    Map<String, String> enrichTags(SpanContext span);
}
```

### 4. SpanExportFilter

**用途**：在导出前过滤 span（与 SpanProcessor 不同）

```java
public interface SpanExportFilter {
    String name();
    boolean shouldExport(SpanRecord span);
}
```

## 实施优先级

### 高优先级（已完成）

1. **扩展点优先级机制** - ✅ 已完成
   - SpanProcessor 添加 priority() 方法
   - SpanProcessors 按优先级排序
   - MetricSource 添加 priority() 方法
   - MetricSources 按优先级排序

2. **扩展点文档集中化** - ✅ 已完成
   - 创建 extension-point-improvement.md
   - 创建 extension-point-guide.md（开发指南）
   - 包含扩展点设计哲学、最佳实践、示例代码

3. **插件性能监控** - ✅ 已完成
   - PluginPerformanceTracker 性能追踪器
   - 插件级性能指标
   - 自动健康评估
   - Prometheus 指标导出

### 中优先级（待实施）

4. **扩展点健康检查** - 为关键扩展点添加健康检查
5. **扩展点配置化** - 支持动态启用/禁用
6. **TagEnricher** - 等待完善接口实现

### 低优先级（长期）

7. **扩展点依赖管理** - 实现依赖检查
8. **统一事件总线** - 重构事件分发
9. **EventTransformer** - 事件转换
10. **MetricProcessor** - 指标处理

## 总结

当前的可观测性扩展点设计已经相当完善，支持：
- ✅ 10种扩展点类型
- ✅ 自动生命周期管理
- ✅ 完整的示例代码
- ✅ 清晰的文档

主要改进方向：
- 🔄 扩展点优先级和依赖管理
- 📊 扩展点自身的可观测性
- 📚 更完善的开发指南
- 🔧 更灵活的配置机制

这些改进将使扩展点系统更加健壮、易用，便于客户开发自定义组件。
