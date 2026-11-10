# 可观测性扩展点开发指南

## 概述

weaver-girl 的可观测性系统采用插件化架构，所有导出能力（Trace、Metrics、Events、Health、Alert）都是通过扩展点实现的。本文档指导如何开发自定义可观测性组件。

## 扩展点架构

### 核心原则

1. **零侵入** - 客户应用代码无需修改，插件通过字节码织入自动生效
2. **可组合** - 多个扩展点可以组合使用，实现复杂场景
3. **自动清理** - 通过 PluginContext 注册的扩展点在插件卸载时自动清理
4. **性能优先** - 扩展点必须轻量，禁止阻塞 I/O
5. **异常隔离** - 单个扩展点异常不影响其他扩展点和业务代码

### 扩展点类型

| 扩展点 | 接口 | 注册方法 | 调用时机 | 典型用途 |
|--------|------|----------|----------|----------|
| SpanProcessor | SpanProcessor | registerSpanProcessor | span结束，消费者之前 | 过滤/脱敏/补属性 |
| SpanSink | SpanSink | registerSpanSink | 处理器链之后 | 自定义APM后端 |
| MetricSource | MetricSource | registerMetricSource | Prometheus抓取时 | 队列/缓存/连接池指标 |
| MetricProcessor | MetricProcessor | registerMetricProcessor | 抓取时、每个样本渲染之前 | 丢噪声指标、改指标名、去高基数标签 |
| ResourceAttributeProvider | ResourceAttributeProvider | registerResourceAttributes | OTLP导出时 | 集群/区域/租户元数据 |
| HealthCheck | HealthCheck | registerHealthCheck | 探针/抓取时 | 数据库/缓存/下游健康 |
| HealthCheckExtension | HealthCheckExtension | registerHealthCheckExtension | 探针/抓取时 | 可观测插件的组件健康，组件名带插件前缀 |
| AlertChannel | AlertChannel | registerAlertChannel | 规则命中时 | 钉钉/Slack/工单 |
| AlertRule | AlertRule | registerAlertRule | 指标评估时 | 插件指标告警规则 |
| SamplingStrategy | SamplingStrategy | registerSamplingStrategy | 段截调用时 | 自定义采样策略 |
| InterceptorEventListener | InterceptorEventListener | registerEventListener | 事件发布时 | 订阅拦截事件 |
| InterceptorEventTransformer | InterceptorEventTransformer | registerEventTransformer | 事件发布时、消费者之前 | 脱敏/改写/丢弃事件 |
| DataExporter | DataExporter | registerDataExporter | 事件发布时 | 事件导出到后端 |
| TagEnricher | TagEnricher | registerTagEnricher | span结束、处理器链之前 | 自动补充标签 |

## 开发指南

### 1. SpanProcessor - Span 治理器

**用途**：在 span 导出到任何消费者之前进行治理

**适用场景**：
- 丢弃噪声 span（health check、心跳）
- 脱敏敏感属性（认证头、SQL 语句）
- 补充应用元数据（租户 ID、请求 ID）

**开发示例**：

```java
public class NoiseFilterProcessor implements SpanProcessor {
  @Override
  public String name() {
    return "noise-filter";
  }

  @Override
  public int priority() {
    return -100; // 确保在所有其他处理器之前运行
  }

  @Override
  public SpanContext process(SpanContext span) {
    String path = span.getAttribute("url.path");
    if ("/health".equals(path) || "/ready".equals(path)) {
      return null; // 丢弃
    }
    return span;
  }
}
```

**注册**：

```java
@Override
public void init(PluginContext context) {
  context.registerSpanProcessor(new NoiseFilterProcessor());
}
```

**注意事项**：
- 在业务线程上执行，必须轻量
- 返回 null 会丢弃整个 span，后续处理器不会执行
- 使用 `SpanContext.toBuilder()` 创建副本，不要修改原对象
- 处理器按优先级执行，低优先级先执行

### 2. SpanSink - Span 接收器

**用途**：接收完成的 span 并发送到自定义后端

**适用场景**：
- 发送到自定义 APM 系统
- 发送到日志系统
- 发送到实时分析平台

**开发示例**：

```java
public class MyBackendSink implements SpanSink {
  private final BlockingQueue<SpanRecord> queue = new ArrayBlockingQueue<>(4096);
  private final Thread flusher;

  @Override
  public String name() {
    return "my-backend";
  }

  @Override
  public void onSpanEnd(SpanRecord span) {
    // 在业务线程上执行，不能阻塞
    if (!queue.offer(span)) {
      context.getLogger().warn("Span buffer full, dropping span");
    }
  }

  @Override
  public void init(PluginContext context) {
    // 启动后台线程
    flusher = new Thread(this::flushLoop, "my-backend-flush");
    flusher.setDaemon(true);
    flusher.start();
  }

  private void flushLoop() {
    List<SpanRecord> batch = new ArrayList<>(64);
    while (running) {
      SpanRecord first = queue.poll(1, TimeUnit.SECONDS);
      if (first == null) continue;
      batch.add(first);
      queue.drainTo(batch, 63);
      sendToBackend(batch);
      batch.clear();
    }
  }

  @Override
  public void destroy() {
    running = false;
    flusher.interrupt();
  }
}
```

**注意事项**：
- `onSpanEnd` 在业务线程执行，必须异步化
- 使用队列缓冲，避免阻塞业务线程
- 处理网络错误，不要抛异常
- 处理采样，未采样的 span 可直接跳过

### 3. MetricSource - 指标源

**用途**：在 Prometheus 抓取时暴露动态指标

**适用场景**：
- 连接池状态
- 队列深度
- 缓存命中率
- JVM 自定义指标

**开发示例**：

```java
public class ConnectionPoolMetricSource implements MetricSource {
  private final HikariDataSource dataSource;

  @Override
  public String name() {
    return "connection-pool";
  }

  @Override
  public int priority() {
    return 0;
  }

  @Override
  public void collect(MetricCollector collector) {
    if (dataSource == null) {
      return;
    }
    HikariPoolMXBean pool = dataSource.getHikariPoolMXBean();
    collector.gauge(
        "pool_active_connections",
        "Active connections in the pool",
        pool.getActiveConnections());
    collector.gauge(
        "pool_idle_connections",
        "Idle connections in the pool",
        pool.getIdleConnections());
    collector.gauge(
        "pool_total_connections",
        "Total connections in the pool",
        pool.getTotalConnections());
  }
}
```

**注意事项**：
- `collect` 在抓取线程执行，必须快速
- 不要在 collect 中做网络 I/O
- 指标名不要使用 `weavergirl_` 前缀（保留给底座）
- 同一指标名多次设置，后注册的生效

### 4. HealthCheck - 健康检查

**用途**：暴露插件自身的健康状态

**适用场景**：
- 数据库连接状态
- 后端服务可用性
- 缓存健康状态

**开发示例**：

```java
public class BackendHealthCheck implements HealthCheck {
  private final HttpClient httpClient;

  @Override
  public String name() {
    return "backend";
  }

  @Override
  public HealthCheckResult check() {
    try {
      HttpResponse response = httpClient.send(
          HttpRequest.newBuilder()
              .uri(URI.create("http://backend:8080/health"))
              .timeout(Duration.ofSeconds(2))
              .GET()
              .build());
      if (response.statusCode() == 200) {
        return HealthCheckResult.up();
      } else {
        return HealthCheckResult.down(
            "Backend returned " + response.statusCode());
      }
    } catch (Exception e) {
      return HealthCheckResult.down("Backend unreachable: " + e.getMessage());
    }
  }
}
```

**注意事项**：
- `check` 在探针线程执行，必须快速（2秒内）
- 不要阻塞，设置合理的超时
- 返回 DOWN 时在 details 中记录原因
- 每次调用都是全新的检查，不要缓存结果

#### 4.1 HealthCheckExtension - 组件健康能力

`HealthCheckExtension` 是给**可观测插件**用的形态：插件实现它，交给 `PluginContext`，引擎负责
组件命名、评估和卸载清理，插件不用自己管注册表。

```java
public class BackendHealthExtension implements HealthCheckExtension {
  @Override
  public String name() { return "backend"; }   // 默认是类名

  @Override
  public HealthCheckResult checkHealth() {
    return backend.isUp()
        ? HealthCheckResult.up(Collections.singletonMap("latencyMs", "3"))
        : HealthCheckResult.down("backend unreachable");
  }
}

context.registerHealthCheckExtension(new BackendHealthExtension());
// 组件名 = 插件名 + "/" + 扩展名，例如 "orders-plugin/backend"
```

- 与普通 `HealthCheck` 在同一次 `checkAll()` 里评估，普通检查先执行；组件名冲突时先注册者胜出。
- 抛异常或返回 `null` 只把自己标成 `DOWN`（详情里带原因），其它组件照常评估。
- 卸载/热替换时随 `withdrawServices()` 自动摘除，并清掉 `weavergirl_extension_*` 里属于它的计数。
- 与普通检查一样跑在探针/抓取线程上，必须轻量。

### 5. TagEnricher - 标签增强器

**用途**：自动为 span 添加应用特定的标签

**适用场景**：
- 租户 ID
- 请求 ID
- 用户 ID
- 环境标识

**开发示例**：

```java
import com.github.cc11001100.weavergirl.api.observability.TagEnricher;

public class TenantTagEnricher implements TagEnricher {
  @Override
  public String name() {
    return "tenant-enricher";
  }

  @Override
  public int priority() {
    return 100; // 越小越先，默认 0
  }

  @Override
  public Map<String, String> enrichTags(SpanContext span) {
    Map<String, String> tags = new LinkedHashMap<>();
    String tenantId = TenantContext.current();
    if (tenantId != null) {
      tags.put("tenant.id", tenantId);
    }
    String requestId = RequestIdContext.current();
    if (requestId != null) {
      tags.put("request.id", requestId);
    }
    return tags;
  }
}

// TagEnricher 是独立扩展点，直接注册即可
context.registerTagEnricher(new TenantTagEnricher());
```

**注意事项**：
- 在 `Tracer.endSpan` 里**早于 SpanProcessor 链**执行，先补标签再治理
- 按 `priority()` 升序执行，同优先级保持注册顺序；后面的增强器能看到前面写入的属性
- 标签会成为 span 属性，可在后端查询；同名属性以后写者为准
- 返回 `null` 或空 map 等于不修改；抛异常只跳过自己，span 照常发布
- 每次调用都要读取上下文，不要缓存结果
- 直接调用 `TagEnrichers.register/unregister` 也能注册，但不会随插件卸载自动摘除

### 6. MetricProcessor - 指标治理器

**用途**：在指标被渲染给后端之前，对每个样本做过滤、改名、去标签或限值

**适用场景**：
- 去掉高基数标签（`path`、`user_id`、`trace_id`）
- 把插件自己的指标名改成后端约定的名字
- 丢弃调试用的临时指标
- 给无界值加上限，避免异常值污染曲线

**开发示例**：

```java
import com.github.cc11001100.weavergirl.api.observability.MetricPoint;
import com.github.cc11001100.weavergirl.api.observability.MetricProcessor;

public class CardinalityProcessor implements MetricProcessor {
  @Override
  public String name() {
    return "cardinality";
  }

  @Override
  public int priority() {
    return -10; // 越小越先，默认 0
  }

  @Override
  public MetricPoint process(MetricPoint point) {
    if ("debug_pool_snapshot".equals(point.name())) {
      return null; // 丢弃
    }
    if (point.labels().containsKey("path")) {
      Map<String, String> labels = new LinkedHashMap<>(point.labels());
      labels.remove("path");
      return point.toBuilder().labels(labels).build();
    }
    return point;
  }
}

context.registerMetricProcessor(new CardinalityProcessor());
```

**注意事项**：
- 运行在抓取线程上，只影响本次抓取；不要修改 `MetricSource` 的状态
- 按 `priority()` 升序执行，后一个处理器看到前一个改写后的结果
- 返回 `null` 丢弃并结束链条；返回 `toBuilder()` 构造的新点可改名/改标签/改值
- 返回值仍受指标名校验和 `weavergirl_` 命名空间保护
- 抛异常只跳过自己，样本原样保留，并计入 `kind="metric-processor"` 的异常计数

### 7. InterceptorEventTransformer - 事件治理器

**用途**：在事件到达任何监听器、导出器和事件历史之前统一脱敏/改写/丢弃

**适用场景**：
- 脱敏 SQL、URL、认证头等敏感属性
- 丢弃心跳/健康检查类噪声事件
- 统一事件类型或插件名，对齐后端规范

**开发示例**：

```java
import com.github.cc11001100.weavergirl.api.event.InterceptorEvent;
import com.github.cc11001100.weavergirl.api.event.InterceptorEventTransformer;

public class EventRedactor implements InterceptorEventTransformer {
  @Override
  public String name() {
    return "redactor";
  }

  @Override
  public InterceptorEvent transform(InterceptorEvent event) {
    if ("heartbeat".equals(event.getType())) {
      return null; // 对所有消费者隐藏
    }
    if (event.getAttributes().containsKey("sql")) {
      return event.toBuilder().attribute("sql", "***").build();
    }
    return event;
  }
}

context.registerEventTransformer(new EventRedactor());
```

**注意事项**：
- 在 `InterceptorEventPublisher.publish` 的调用线程上执行，保持轻量
- `toBuilder()` 保留原始时间戳和 traceId/spanId，不需要手工复制
- 返回 `null` 后监听器、`DataExporter`、`getHistory()` 都收不到该事件
- 抛异常只跳过自己，事件原样继续投递，并计入 `kind="event-transformer"` 的异常计数

## 测试扩展点

### 单元测试

```java
@Test
void testSpanProcessorPriority() {
  TestPluginContext context = new TestPluginContext();
  
  SpanProcessor lowPriority = new TestSpanProcessor("low", -100);
  SpanProcessor highPriority = new TestSpanProcessor("high", 100);
  
  context.registerSpanProcessor(highPriority);
  context.registerSpanProcessor(lowPriority);
  
  List<SpanProcessor> processors = SpanProcessors.getProcessors();
  assertEquals("low", processors.get(0).name());
  assertEquals("high", processors.get(1).name());
}
```

### 集成测试

```java
@Test
void testTagEnricherIntegration() {
  try (PluginTestHarness harness = PluginTestHarness.create()
      .install(new TagEnricherPlugin())) {
    
    harness.weave(Service.class);
    
    TenantContext.set("tenant-123");
    Service service = new Service();
    service.process();
    
    // 验证 span 包含 tenant.id 标签
    // ...
  }
}
```

## 性能优化

### 1. 避免对象分配

```java
// 差的做法
public SpanContext process(SpanContext span) {
  Map<String, String> tags = new HashMap<>(); // 每次调用都创建
  // ...
}

// 好的做法
private static final Map<String, String> COMMON_TAGS = new ConcurrentHashMap<>();

public SpanContext process(SpanContext span) {
  // 使用线程安全的静态缓存
  // ...
}
```

### 2. 短路执行路径

```java
// 避免条件判断
public SpanContext process(SpanContext span) {
  if (shouldDrop(span)) {  // 条件判断开销
    return null;
  }
  // ...
}

// 优先级排序，避免运行到不需要的处理器
public int priority() {
  return -100; // 噪声的处理器优先执行
}
```

### 3. 异步化

```java
// Sink 必须异步化
public void onSpanEnd(SpanRecord span) {
  buffer.offer(span);  // 队列缓冲
}

// Source 可以同步
public void collect(MetricCollector collector) {
  collector.gauge("pool_size", pool.size());  // 快速读取
}
```

## 常见陷阱

### 1. 修改原对象

```java
// 错误：直接修改
public SpanContext process(SpanContext span) {
  span.setAttribute("key", "value");  // 可能抛异常
  return span;
}

// 正确：创建副本
public SpanContext process(SonSpanContext span) {
  SpanContext.Builder builder = span.toBuilder();
  builder.setAttribute("key", "value");
  return builder.build();
}
```

### 2. 阻塞 I/O

```java
// 错误：同步网络调用
public void onSpanEnd(SpanRecord span) {
  httpClient.send(span.toJson());  // 阻塞业务线程
}

// 正确：异步发送
public void onSpanEnd(SpanRecord span) {
  queue.offer(span);  // 队列缓冲
}
```

### 3. 未处理异常

```java
// 错误：异常向上抛出
public SpanContext process(SpanContext span) {
  return span.toBuilder()
      .attribute("key", computeValue());  // 可能抛异常
      .build();
}

// 正确：捕获异常
public SpanContext process(SpanContext span) {
  try {
    String value = computeValue();
    return span.toBuilder()
        .attribute("key", value)
        .build();
  } catch (Exception e) {
    context.getLogger().warn("Failed to compute value", e);
    return span; 保留原值
  }
}
```

## 最佳实践

### 1. 优先级分配

| 优先级范围 | 用途 | 示例 |
|---------|------|------|
| -100 到 -10 | 噪声过滤 | 健康检查过滤、心跳过滤 |
| -9 到 -1 | 必须执行的治理 | 脱敏、权限检查 |
| 0 | 常规治理 | 元数据补充、标签补充 |
| 1 到 10 | 数据丰富 | 添加业务上下文 |
| 11 到 100 | 可选增强 | 性能分析、慢调用追踪 |

### 2. 扩展点组合

```java
@Override
public void init(PluginContext context) {
  // 1. 噪声过滤（优先级 -100）
  context.registerSpanProcessor(new NoiseFilterProcessor());
  
  // 2. 脱敏（优先级 -10）
  context.registerSpanProcessor(new RedactionProcessor());
  
  // 3. 标签补充（优先级 100）
  context.registerSpanProcessor(new TagEnricherProcessor());
  
  // 4. 自定义后端
  context.registerSpanSink(new MyBackendSink());
  
  // 5. 指标源
  context.registerMetricSource(new ConnectionPoolMetricSource());
  
  // 6. 健康检查
  context.registerHealthCheck(new BackendHealthCheck());
}
```

### 3. 配置化扩展点

```java
public class MyProcessor implements SpanProcessor, ConfigurableExtension {
  @Override
  public String name() {
    return "my-processor";
  }

  @Override
  public String configKey() {
    return "myProcessor.enabled";
  }

  @Override
  public boolean isEnabled(PluginContext context) {
    return context.getConfigBoolean(configKey(), true);
  }

  @Override
  public SpanContext process(SpanContext span) {
    // ...
  }
}
```

`DefaultPluginContext` 在每次 `registerXxx` 时调用一次 `isEnabled(context)`：

- 返回 `true` 才进入对应注册表，返回 `false` 则完全跳过——扩展不会占用任何运行时开销，卸载时也无需再撤销。
- 未实现 `ConfigurableExtension` 的扩展永远注册。
- 谓词抛异常按“禁用”处理并记录一条 WARN，不会让插件初始化失败。

管辖范围覆盖所有经 `PluginContext` 注册的可观测扩展：SpanSink、SpanProcessor、HealthCheck、HealthCheckExtension、TagEnricher、MetricProcessor、InterceptorEventTransformer、AlertChannel、AlertRule、SamplingStrategy、InterceptorEventListener、DataExporter、MetricSource、ResourceAttributeProvider。若绕过 `PluginContext` 直接调用静态注册方法，则不会经过该开关。

## 参考

- [CustomObservabilityPlugin](examples/CustomObservabilityPlugin.java) - 完整示例插件
- [observability.md](observability.md) - 可观测性总体设计
- [extension-point-improvement-plan.md](extension-point-improvement-plan.md) - 扩展点改进计划
