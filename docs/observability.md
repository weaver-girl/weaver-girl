# 可观测性设计（Observability）

> 本文说明 weaver-girl 的 Trace、Metrics、Events、Health（含告警）四类信号如何产生、如何导出，
> 以及在 OpenTelemetry / Prometheus 体系里如何消费。实现分布在 `weaver-girl-api`（传播与指标
> 注册）、`weaver-girl-core`（Span 导出管线）和 `weaver-girl-runtime-*` 运行时插件（Prometheus、
> OTLP 日志、JSON 事件、健康检查）中。

## 1. 设计原则

1. **底座只提供语义和传播，不绑定后端。** `Tracer` 负责 span 生命周期、W3C 上下文和属性；
   导出由 `SpanExporter` 批量完成，后端只是 formatter。
2. **默认零成本。** 未配置 `metricsPort`、`otlpEndpoint` / `otlpTracesEndpoint` 时不启动任何
   线程、不注册任何监听器。
3. **导出失败不影响业务。** 采集、导出、指标渲染都在 try/catch 内，出错只降级为“这条信号丢了”。
4. **可观测数据必须合规。** Trace 上下文使用 W3C `traceparent` + `baggage`，OTLP 载荷使用
   `ExportTraceServiceRequest` / `ExportLogsServiceRequest` 的标准信封，Prometheus 使用
   text exposition 0.0.4。

## 2. 信号全景

```
                      ┌─────────────────────── 业务 / 插件 ───────────────────────┐
                      │ Tracer.startSpan / setAttribute / recordException        │
                      │ MetricRegistry.record(...)                               │
                      │ InterceptorEventPublisher.publish(...)                   │
                      └───────────────┬───────────────┬──────────────────────────┘
                                      │               │
                 ┌────────────────────┘               └─────────────────────┐
                 ▼                                                        ▼
      SpanCompletionListener                                   InterceptorEventListener
                 │                                                        │
     TracerSpanExporterBridge                                ┌───────────────┼───────────────┐
                 ▼                                          ▼               ▼               ▼
          SpanExporter（批量/缓冲）                  PrometheusExporter  OtlpEventListener  JsonEventsPlugin
                 │                                          │               │               │
                 ▼                                          ▼               ▼               ▼
      OTLP/HTTP JSON → /v1/traces                    GET /metrics      /v1/logs        stdout JSON
```

除此之外，插件还能直接参与全部信号的生产：`SpanProcessor` 在导出前治理 span、`SpanSink` 接收
每个结束的 span、`MetricSource` 在每次抓取时生产指标、`ResourceAttributeProvider` 在 OTLP 导出
前补充资源属性、`HealthCheck` 为探针和 `/metrics` 提供组件健康、`AlertChannel` / `AlertRule`
接入告警通知与规则。这些扩展点由插件体系托管（随插件卸载自动摘除），详见
[第 9 节](#9-扩展点插件自定义可观测)。

## 3. Trace

### 3.1 基本用法

```java
SpanContext span = Tracer.startSpan("GET /orders");
try {
  Tracer.setAttribute("http.method", "GET");
  Tracer.setAttribute("url.path", "/orders");
  doWork();
} catch (RuntimeException e) {
  Tracer.recordException(e);   // error=true, error.type, error.message
  throw e;
} finally {
  Tracer.endSpan(null, "OK");  // 显式状态优先；否则取 error 属性
}
```

- `startChildSpan(name)` 继承 traceId、sampled 和 baggage，并把父 spanId 写入 `parentSpanId`。
- `endSpan(operationName, status)` 依次决定状态：显式参数 → span 已有 `errorStatus` →
  `error=true` 属性 → 默认 `OK`。
- 跨线程：`TracingSnapshot snapshot = Tracer.capture()`，在目标线程 `Tracer.restore(snapshot)`。
- `startSpan` 生成 32 位十六进制 traceId 和 16 位 spanId（非全零），可直接用于 W3C。

### 3.2 线上格式（W3C Trace Context）

`Tracer.inject(span, headers)` 写出：

| Header | 内容 | 说明 |
| --- | --- | --- |
| `traceparent` | `00-<32 hex trace>-<16 hex span>-<2 hex flags>` | 仅当 ID 合法时写出；flags 最低位表示 sampled |
| `baggage` | `k=v,k2=v2` | 百分号编码，值里的空格编码为 `%20` |
| `X-Trace-Id` / `X-Span-Id` / `X-Parent-Span-Id` / `X-Sampled` | 旧格式 | 始终写出，兼容老客户端 |
| `X-Baggage-<key>` | 原始值 | 旧格式 baggage |

`Tracer.extract(headers)` 的优先级：

1. 头名大小写不敏感；
2. `traceparent` 合法则优先（拒绝版本 `ff`、非法十六进制、全零 ID；未来版本允许额外字段）；
3. 否则回退到 `X-*` 旧格式；
4. baggage 先读 W3C `baggage`，再用 `X-Baggage-*` 覆盖同名键。

> 手工构造的非 W3C ID（例如 `trace-abc`）不会写 `traceparent`，避免把整条 trace 变成
> collector 无法解析的数据；旧格式头仍然写出。

Servlet 插件是内置的传播示例：入站读取上下文，服务端 span 作为上游 client span 的**子 span**
（不再复用上游 spanId）；出站把当前 span 写回响应头；嵌套的 Filter → Servlet 会形成父子链并在
内层结束时恢复外层 span。

### 3.3 Span 语义约定

| 键 / 字段 | 来源 | 说明 |
| --- | --- | --- |
| operation name | `startSpan(name)` | 例如 `GET /orders`；结束时未传名字则沿用 |
| `span.kind` 属性 | 插件设置 | `server` / `client` / `producer` / `consumer`，导出时转为 OTLP `kind` 字段（2/3/4/5），默认 1=INTERNAL，不作为普通属性输出 |
| `http.method` | servlet 插件 | 请求方法 |
| `url.path` | servlet 插件 | 请求路径 |
| `error` / `error.type` / `error.message` | `Tracer.recordException` | 异常可观测化；`error=true` 会把状态提升为 ERROR |
| `baggage.<key>` | `TracerSpanExporterBridge` | baggage 以命名空间形式出现在 span 属性里 |

新增插件建议直接使用 OpenTelemetry 语义约定名称（`db.system`、`messaging.system`、
`http.status_code` 等），导出后无需二次映射。

### 3.4 导出管线

`SpanExporter` 是线程安全的批处理器：

- 环形缓冲 `spanExportBufferSize`（默认 10000），满了直接丢弃并计入 `dropped`；
- 每 `spanExportIntervalMs`（默认 5000）或累积 `spanExportBatchSize`（默认 100）触发导出；
- 导出失败不影响业务线程，失败批次计入 `failed`；
- 监听器通过 `Tracer.addCompletionListener` **追加**注册，任何组件卸载时只能
  `removeCompletionListener`，不能清空全部监听器。

OTLP formatter 发送完整的 `ExportTraceServiceRequest`：

```json
{
  "resourceSpans": [{
    "resource": {"attributes": [
      {"key": "service.name", "value": {"stringValue": "orders"}},
      {"key": "service.version", "value": {"stringValue": "2.1.0"}},
      {"key": "telemetry.sdk.name", "value": {"stringValue": "weaver-girl"}},
      {"key": "telemetry.sdk.language", "value": {"stringValue": "java"}}
    ]},
    "scopeSpans": [{
      "scope": {"name": "weaver-girl-agent"},
      "spans": [{
        "traceId": "4bf92f3577b34da6a3ce929d0e0e4736",
        "spanId": "00f067aa0ba902b7",
        "parentSpanId": "a3ce929d0e0e4736",
        "name": "GET /orders",
        "kind": 2,
        "startTimeUnixNano": "1730000000000000000",
        "endTimeUnixNano": "1730000000042000000",
        "status": {"code": "STATUS_CODE_OK"},
        "attributes": [{"key": "http.method", "value": {"stringValue": "GET"}}]
      }]
    }]
  }]
}
```

资源属性用于区分同一个 collector 下的不同服务：

| 配置键 | 默认值 | 资源属性 |
| --- | --- | --- |
| `serviceName` | 系统属性 `weavergirl.service.name`，否则 `weaver-girl` | `service.name` |
| `serviceVersion` | 包实现版本，未知时 `unknown` | `service.version` |
| `deploymentEnvironment` | 不设置 | `deployment.environment` |

## 4. Metrics（Prometheus）

配置 `metricsPort=9400` 后，`GET /metrics` 返回
`text/plain; version=0.0.4; charset=utf-8`。每次抓取都会实时渲染以下内置指标组；此外插件还可以在
抓取时动态贡献指标（见 4.5）。

### 4.1 拦截事件指标

只统计“由事件驱动、有界基数”的维度（plugin / type），可直接做错误率和慢调用率。

| 指标 | 类型 | 标签 | 含义 |
| --- | --- | --- | --- |
| `weavergirl_events_total` | counter | `plugin`, `type` | 全部事件，做比率的分子/分母 |
| `weavergirl_slow_operations_total` | counter | `plugin`, `type` | `slow-*` 事件 |
| `weavergirl_error_operations_total` | counter | `plugin` | `*-error` 事件 |
| `weavergirl_operation_duration_ms_sum` | counter | `plugin` | 累计耗时（毫秒） |
| `weavergirl_operation_duration_ms_count` | counter | `plugin` | 有耗时的操作数 |
| `weavergirl_iast_findings_total` | counter | `sink` | IAST 切片命中，仅 sink 类型 |

### 4.2 Agent 自监控指标

数据来自 `AgentStatusHolder`；未安装引擎（例如单独测试插件）时不输出这些序列，而不是输出 0。

| 指标 | 类型 | 含义 |
| --- | --- | --- |
| `weavergirl_agent_transformations_total` | counter | 已织入类数 |
| `weavergirl_agent_transformation_errors_total` | counter | 织入失败数 |
| `weavergirl_agent_interceptor_invocations_total` | counter | 拦截器调用次数 |
| `weavergirl_agent_interceptor_errors_total` | counter | 拦截器抛异常次数 |
| `weavergirl_agent_registered_interceptors` | gauge | 已注册拦截器定义数 |

### 4.2.1 切面级指标（按拦截器定义）

`weavergirl_agent_*` 是整个进程的汇总值；要知道“哪个切面慢/哪个切面在抛异常”，看按定义拆分的
`weavergirl_interceptor_*`。数据直接来自引擎对每个回调的计时，没有额外开销：

| 指标 | 类型 | 标签 | 含义 |
| --- | --- | --- | --- |
| `weavergirl_interceptor_invocations_total` | counter | `interceptor` | 该定义的回调次数 |
| `weavergirl_interceptor_errors_total` | counter | `interceptor` | 该定义回调抛异常次数 |
| `weavergirl_interceptor_slow_calls_total` | counter | `interceptor` | 超过慢钩子阈值（默认 50ms）的次数 |
| `weavergirl_interceptor_duration_seconds_total` | counter | `interceptor` | 回调累计耗时（秒） |
| `weavergirl_interceptor_duration_seconds_bucket` | histogram | `interceptor`, `le` | 回调耗时分布（1ms…1s、`+Inf`） |
| `weavergirl_interceptor_duration_seconds_sum` / `_count` | histogram | `interceptor` | 分布的总和/总数 |

序列数上界是“已注册定义数”，抓取时实时渲染，不做跨进程聚合。没被调用过的定义不出现。

### 4.2.2 插件健康（PluginHealthRegistry）

插件通过 `PluginHealthRegistry.reportHealthy/reportDegraded/reportUnhealthy` 上报自身状态，
Prometheus 抓取时渲染。样本值恒为 1，当前状态放在 `status` 标签里，方便直接告警：

```text
# TYPE weavergirl_plugin_health gauge
weavergirl_plugin_health{plugin="prometheus",status="healthy"} 1
weavergirl_plugin_health{plugin="jdbc",status="degraded"} 1
weavergirl_plugin_health_timestamp_seconds{plugin="jdbc"} 1730000000.123
```

告警表达式：`weavergirl_plugin_health{status!="healthy"} == 1`。从未上报的插件不输出序列（而不是
伪造 `unknown`）；`_timestamp_seconds` 用于发现“卡住不更新”的健康检查。

### 4.2.3 SpanSink 投递指标

用户插件通过 `SpanSink` 接自己的后端时，回调异常会被隔离（只限流打日志），业务侧看不到。
`SpanSinkStats` 的计数在这里暴露：

| 指标 | 类型 | 标签 | 含义 |
| --- | --- | --- | --- |
| `weavergirl_span_sink_deliveries_total` | counter | `sink` | 交给该 sink 的 span 数（含失败） |
| `weavergirl_span_sink_errors_total` | counter | `sink` | `onSpanEnd` 抛异常的次数 |

没有注册任何 sink 时不输出。`errors_total` 增长说明用户后端在丢数据，而业务无损——
这正是需要用指标而不是日志发现的问题。

### 4.2.4 插件生命周期状态

从 `PluginManager` 读取每个受管插件的状态和它注册的拦截器数：

```text
# TYPE weavergirl_plugin_state gauge
weavergirl_plugin_state{plugin="servlet",state="active"} 1
weavergirl_plugin_state{plugin="jdbc",state="disabled"} 1
weavergirl_plugin_interceptors{plugin="servlet"} 3
```

`state` 取值 `loaded` / `active` / `disabled` / `unloaded`。告警：
`weavergirl_plugin_state{state!="active"} == 1` 可以发现被误禁用或卡在初始化中的插件；
`weavergirl_plugin_interceptors` 掉到 0 通常意味着插件注册失败。未安装 `PluginManager`
（单独运行插件测试）时不输出。

### 4.2.5 组件健康检查（HealthCheck）

`/metrics` 每次抓取都会调用所有插件注册的 `HealthCheck`（与 `MetricSource` 同一时机），把结果
渲染成 gauge：

```text
# TYPE weavergirl_health_overall gauge
weavergirl_health_overall{status="degraded"} 1
# TYPE weavergirl_health_check gauge
weavergirl_health_check{component="orders-db",status="up"} 1
weavergirl_health_check{component="payments",status="down"} 1
weavergirl_health_check_timestamp_seconds{component="orders-db"} 1730000000.123
```

告警：`weavergirl_health_check{status="down"} == 1`。与 `weavergirl_plugin_health`（4.2.2）不同，
这里关心的是**业务依赖**（数据库、缓存、下游网关）而不是插件自身是否加载成功。没有注册任何
检查时不输出。检查在抓取线程上同步执行，必须轻量。

### 4.2.6 Span 处理器指标（SpanProcessor）

插件通过 `SpanProcessor` 做过滤/脱敏/补属性时，最危险的情况是“过滤条件写错，把所有 span 都
吃掉”。计数在这里暴露（见 9.5）：

| 指标 | 类型 | 标签 | 含义 |
| --- | --- | --- | --- |
| `weavergirl_span_processor_processed_total` | counter | `processor` | 该处理器放行并返回的 span 数 |
| `weavergirl_span_processor_dropped_total` | counter | `processor` | 该处理器返回 `null` 丢弃的 span 数 |
| `weavergirl_span_processor_errors_total` | counter | `processor` | 处理器抛异常、span 原样放行的次数 |

没有注册任何处理器时不输出。告警建议：`dropped_total` 突然跳到接近该服务的总 span 量，
或 `errors_total > 0` 持续增长（后者说明规则本身有 bug）。

### 4.3 JVM 指标

基于 `java.lang.management`，每次抓取读取一次：

| 指标 | 类型 | 标签 |
| --- | --- | --- |
| `weavergirl_jvm_memory_used_bytes` | gauge | `area=heap` / `nonheap` |
| `weavergirl_jvm_memory_committed_bytes` | gauge | `area` |
| `weavergirl_jvm_memory_max_bytes` | gauge | `area`（JVM 未定义上限时不输出） |
| `weavergirl_jvm_threads_current` / `_peak` | gauge | — |
| `weavergirl_jvm_classes_loaded` | gauge | — |
| `weavergirl_jvm_uptime_seconds` | gauge | — |
| `weavergirl_jvm_gc_collection_count_total` | counter | `gc` |
| `weavergirl_jvm_gc_collection_time_seconds_total` | counter | `gc` |

### 4.4 业务指标（MetricRegistry）

插件通过 `MetricRegistry.record(name, labels, value)` 记录自定义数值，按滑动窗口（默认 60s）
聚合。窗口内没有样本的指标不会输出（避免把“空闲”误读为 0）。

导出形态固定为 `weavergirl_metric_*`，原始指标名放在 `metric` 标签里，因此
`request.duration_ms` 这类带点的名字也能安全输出：

```text
# HELP weavergirl_metric_p99 Windowed value of a MetricRegistry business metric
# TYPE weavergirl_metric_p99 gauge
weavergirl_metric_p99{metric="request.duration_ms",route="/users"} 420
weavergirl_metric_count{metric="request.duration_ms",route="/users"} 128
```

可用后缀：`count`、`sum`、`avg`、`min`、`max`、`p50`、`p90`、`p95`、`p99`。标签名会按
`[a-zA-Z_][a-zA-Z0-9_]*` 清洗，标签值按 Prometheus 规则转义。

导出管线自身也会写入注册表，用于监控“监控管道”：

| 指标名 | 含义 |
| --- | --- |
| `weavergirl.span.export.spans` | 每批成功交给 formatter 的 span 数 |
| `weavergirl.span.export.dropped` | 缓冲满被丢弃的 span 数 |
| `weavergirl.span.export.failed` | formatter 导出失败次数 |

例如 `weavergirl_metric_count{metric="weavergirl.span.export.dropped"}` 持续增长，表示 collector
太慢或 `spanExportBufferSize` 太小。

### 4.5 插件自定义指标（MetricSource）

插件通过 `PluginContext.registerMetricSource(...)` 注册一个 `MetricSource`，Prometheus 插件在
**每次抓取时**调用它的 `collect(collector)`。适合“抓取时现算”的值（队列长度、缓存命中率、
连接池状态），不需要自己开线程或维护滚动窗口：

```java
context.registerMetricSource(new MetricSource() {
  @Override public String name() { return "job-queue"; }

  @Override public void collect(MetricCollector collector) {
    collector.gauge("jobs_pending", "Jobs waiting in the queue", queue.size());
    collector.counter(
        "jobs_processed_total", "Jobs finished, by queue",
        Collections.singletonMap("queue", "mail"), processed.get());
    // 已经在自己库里维护的分布，直接上报快照（累计口径）
    collector.histogram(
        "job_wait_seconds", "Job wait time",
        new double[] {0.01, 0.1, 1.0},
        new long[] {12, 40, 55},
        8.7);
  }
});
```

`histogram` 的两个数组是平行且**累计**的：`upperBounds[i]` 是桶上界，`cumulativeCounts[i]` 是
小于等于该上界的观测数，最后一个元素就是总数；`+Inf` 桶、`_sum`、`_count` 由渲染器补齐。数组
长度不一致、上界不递增或计数递减的输入会被静默忽略（否则整次抓取会被 Prometheus 拒绝），
用户自带的 `le` 标签会被桶上界覆盖。如果你的采集库保存的是非累计的每桶计数，请先自行累加。

渲染规则：

- 指标名按 `[a-zA-Z_:][a-zA-Z0-9_:]*` 清洗（非法字符变 `_`）；标签名按
  `[a-zA-Z_][a-zA-Z0-9_]*` 清洗，标签值按 Prometheus 规则转义。
- **`weavergirl_` 前缀保留给底座**。插件使用该前缀的指标族会被整族跳过，避免与内置指标冲突。
- 同一指标名被两个源声明为不同类型时，保留先出现的类型；同名 + 同标签的首个样本获胜。
- 每个源独立采集：某个 `collect` 抛异常只记一次
  `weavergirl_metric_source_errors_total{source="..."}`，其余源照常输出。
- 输出按源名、指标名、标签块排序，抓取结果稳定。

### 4.6 常用 PromQL

```promql
# 错误率（按插件）
sum by (plugin) (rate(weavergirl_error_operations_total[5m]))
  / sum by (plugin) (rate(weavergirl_events_total[5m]))

# 慢请求占比
sum by (plugin, type) (rate(weavergirl_slow_operations_total[5m]))
  / sum by (plugin, type) (rate(weavergirl_events_total[5m]))

# 业务接口 p99
weavergirl_metric_p99{metric="request.duration_ms"}

# 织入错误是否在增长
increase(weavergirl_agent_transformation_errors_total[1h])
```

## 5. Events

拦截事件是 Trace 与 Metrics 之外的第三类信号，默认只发布到进程内事件总线。

| 出口 | 配置 | 形态 |
| --- | --- | --- |
| 进程内 | 始终可用 | `InterceptorEventPublisher`，供自定义插件消费 |
| Prometheus | `metricsPort` | 聚合为 4.1 的计数器 |
| OTLP 日志 | `otlpLogsEndpoint` | `ExportLogsServiceRequest` → `/v1/logs`，一条记录一个事件 |
| JSON stdout | `jsonEvents=true` | 每行一个结构化 JSON |

OTLP 日志的严重级别：`*-error` 事件为 `ERROR`，其余为 `INFO`；公共属性包括
`weavergirl.plugin`、`weavergirl.type`、`code.namespace`、`code.function`、
`weavergirl.duration_ms`，事件自带属性统一加 `weavergirl.` 前缀。

> **注意：`otlpEndpoint` 专用于 Trace。** 事件日志请使用 `otlpLogsEndpoint`。历史版本两者共用
> `otlpEndpoint`；现在只有当它不以 `/v1/traces` 结尾时才会被日志导出复用，避免把日志 POST 到
> traces receiver 而被 collector 拒绝。Trace 端也可显式写 `otlpTracesEndpoint`。

## 6. 配置参考

| 配置键 | 默认值 | 作用 |
| --- | --- | --- |
| `metricsPort` | 无 | Prometheus 端口；不配置则无 `/metrics` |
| `otlpEndpoint` | 无 | Trace 导出端点（历史别名），建议写完整 `/v1/traces` URL |
| `otlpTracesEndpoint` | 无 | Trace 导出端点（优先于 `otlpEndpoint`） |
| `otlpLogsEndpoint` | 无 | 事件日志导出端点，写完整 `/v1/logs` URL |
| `otlpHeaders` | 无 | 两个 OTLP 导出共用的附加头：`Key=Value;Key2=Value2` |
| `otlpTimeoutMs` | 1000 | 事件日志导出的连接/读取超时 |
| `spanExport` | false | 无端点时把 span 打到日志 |
| `spanExportBatchSize` | 100 | 批量大小 |
| `spanExportIntervalMs` | 5000 | 批量导出周期 |
| `spanExportBufferSize` | 10000 | 缓冲上限，满则丢弃 |
| `serviceName` | `weaver-girl` | OTLP 资源属性 `service.name` |
| `serviceVersion` | 包版本 | OTLP 资源属性 `service.version` |
| `deploymentEnvironment` | 无 | OTLP 资源属性 `deployment.environment` |
| `jsonEvents` | false | 输出 JSON 事件到 stdout |

YAML 写法见 [yaml-config-reference.md](yaml-config-reference.md)，Collector 示例见
[otel-collector-example.yaml](otel-collector-example.yaml)。

## 7. 与 OpenTelemetry Collector 对接

```yaml
receivers:
  otlp:
    protocols:
      http:
        endpoint: 0.0.0.0:4318
service:
  pipelines:
    traces:
      receivers: [otlp]
      exporters: [otlp/jaeger]
    logs:
      receivers: [otlp]
      exporters: [logging]
```

启动应用：

```bash
java -javaagent:weaver-girl-agent.jar=\
metricsPort=9400,\
otlpTracesEndpoint=http://collector:4318/v1/traces,\
otlpLogsEndpoint=http://collector:4318/v1/logs,\
serviceName=orders-api,\
deploymentEnvironment=prod \
-jar app.jar
```

验证：

```bash
curl -s http://localhost:9400/metrics | grep -E 'weavergirl_(agent|jvm|events)'
# collector 端应能收到 service.name=orders-api 的 resourceSpans
```

## 8. 已知限制

- OTLP 仅支持 **HTTP/JSON**；没有 gRPC、重试和指数退避。失败批次计入 `failed`，由下一批继续。
- Span 导出是“尽力而为”：缓冲满即丢弃；生产环境请同时监控 `SpanExporter` 的 dropped/failed 日志。
- `sampled` 只在上下文里传播，本地拦截器仍会执行；如需真正降低采集量，请在 collector 使用
  `probabilistic_sampler` / `tail_sampling`。
- Prometheus 输出不含 exemplar（text 0.0.4 限制）。业务指标（`MetricRegistry`）没有原生
  histogram，分布看 p50/p90/p95/p99 窗口值；需要真正的分布桶请用 `MetricSource` 的
  `collector.histogram(...)`（4.5）。切面回调延迟有固定桶的 histogram（4.2.1）。
- `MetricRegistry` 指标是滑动窗口值而非进程生命周期累计值；窗口内无样本时序列会消失。
- `SpanSink` / `SpanProcessor` / `MetricSource` / `HealthCheck` 都在调用方线程上同步执行：慢
  sink 或慢处理器会拖慢业务线程，慢 `collect` 会拖慢抓取，慢检查会拖慢探针（探针超时可能导致
  容器被重启）。需要 IO 请在自己的实现里缓冲或异步化。
- `SpanProcessor` 只能看到已结束的 span。要真正少产生 span（例如按 trace 采样），仍需在
  collector 侧做；处理器负责的是“产生后、导出前”的治理。
- JVM 指标反映的是**被织入进程**，一个 JVM 一个 scrape target。
- 跨进程传播目前落在 Servlet 边界；HTTP client、消息队列等协议需要对应插件调用
  `Tracer.inject` / `Tracer.extract`（后续插件可直接复用这套 API）。

## 9. 扩展点：插件自定义可观测

可观测能力本身就是插件：本仓库的 `span-export`、`otlp`、`prometheus`、`json-events`、
`health`、`diagnostics` 都是 `weaver-girl-runtime-*` 插件，agent 入口没有硬编码任何后端。上层
产品用同一套机制接入自己的采集端即可，不需要修改底座。

### 9.1 扩展点总览

| 扩展点 | 注册方法 | 调用时机 | 典型用途 |
| --- | --- | --- | --- |
| `SpanProcessor` | `context.registerSpanProcessor(processor)` | 每个 span 结束（`Tracer.endSpan`）时、送达任何消费者之前 | 丢弃噪声 span、脱敏敏感属性、补充应用元数据 |
| `SpanSink` | `context.registerSpanSink(sink)` | 处理器链之后，同步回调 | 自有 APM/日志后端 |
| `MetricSource` | `context.registerMetricSource(source)` | Prometheus 每次抓取时 | 队列深度、缓存命中率、连接池状态 |
| `ResourceAttributeProvider` | `context.registerResourceAttributes(provider)` | OTLP 每次导出时 | 集群/区域/租户等运行时元数据 |
| `HealthCheck` | `context.registerHealthCheck(check)` | 探针请求、`/metrics` 抓取时 | 数据库/缓存/下游依赖健康 |
| `AlertChannel` | `context.registerAlertChannel(channel)` | 规则命中时同步通知 | 钉钉/Slack/工单/值班系统 |
| `AlertRule` | `context.registerAlertRule(rule)` | 指标值进入 `AlertEngine.evaluate` 时 | 插件为自己产生的指标定义阈值 |

```text
Tracer.endSpan ──► SpanProcessors ──► SpanSinks ──► 你的 SpanSink（同步）
                │            └────► 其它 SpanCompletionListener（内置 OTLP 导出器）
                └─ 返回 null 时整条链路都不再看到这个 span
GET /metrics   ──► MetricSources ──► 你的 MetricSource.collect(...)
GET /metrics   ──► HealthChecks  ──► 你的 HealthCheck.check()
OTLP 导出批次  ──► ResourceAttributes ──► 你的 provider（覆盖默认值，可被显式配置覆盖）
AlertEngine    ──► AlertChannels ──► 你的 AlertChannel.onAlert(...)
```

`SpanProcessor` 是**唯一的统一治理点**：它位于所有消费者之前，所以一次过滤同时作用于内置
OTLP 导出、所有 `SpanSink` 和其它监听器，不会出现“导出器看到的和 sink 看到的不是同一份数据”。

**注册必须走 `PluginContext`。** `DefaultPluginContext` 会记录插件注册了什么，并在插件卸载/热
替换时调用 `withdrawServices()` 自动摘除。直接调用静态的 `SpanSinks.register` 也能生效，但
`destroy()` 里必须自己 `unregister`，否则旧实例会继续收到数据（这是插件热重载后“数据重复”的
最常见原因）。

### 9.2 SpanSink

```java
public final class MySpanSink implements SpanSink {
  @Override public String name() { return "my-backend"; }

  @Override public void onSpanEnd(SpanRecord span) {
    if (!span.isSampled()) {
      return; // 未采样的 span 也会送达，按需丢弃
    }
    buffer.offer(span); // 不要在这里做网络 IO
  }
}
```

`SpanRecord` 是结束时刻的不可变快照：`traceId`、`spanId`、`parentSpanId`、`operationName`、
`kind`（`internal` / `server` / `client` / `producer` / `consumer`）、`startTimeMs`、`durationMs`、
`status`、`sampled`、`attributes`、`baggage`。`isError()` 在 `status=ERROR` 或属性
`error=true` 时为真。

行为约定：

- 回调在**业务线程**上同步执行。慢 sink 会直接拖慢业务，所有 IO 请自行缓冲/异步化。
- 每个 sink 的异常被隔离：记录一条限流的 WARNING，其它 sink 和业务代码不受影响。失败次数可在
  `/metrics` 的 `weavergirl_span_sink_errors_total{sink}` 里观察到（见 4.2.3）。
- 同一个实例重复注册只回调一次；注销最后一个 sink 时自动摘除 `Tracer` 上的内部监听器，不影响
  其它 `SpanCompletionListener`。
- **不要用 `Tracer.setCompletionListener(...)`**：它是覆盖式 API，会清掉所有监听器（包括其它
  插件的 sink）。

### 9.3 MetricSource

```java
context.registerMetricSource(new MetricSource() {
  @Override public String name() { return "connection-pool"; }

  @Override public void collect(MetricCollector collector) {
    MetricPoolStats s = pool.stats();
    collector.gauge("pool_active_connections", "Connections in use", s.active());
    collector.gauge(
        "pool_idle_connections", "Connections idle",
        Collections.singletonMap("pool", "primary"), s.idle());
  }
});
```

抓取时现算，不需要后台线程。渲染规则见 4.5；要点：`weavergirl_` 前缀保留、单源异常隔离并计入
`weavergirl_metric_source_errors_total`、同类型名冲突保留先出现者。

### 9.4 ResourceAttributeProvider

资源属性按以下顺序合并，后者覆盖前者：

1. 底座内置默认值（`telemetry.sdk.name`、`telemetry.sdk.language`、`service.name` 等）；
2. 插件注册的 provider，**每次导出时**读取，按注册顺序后者覆盖前者；
3. 显式配置（`serviceName`、`serviceVersion`、`deploymentEnvironment` 或
   `resourceAttribute(...)`）。

```java
context.registerResourceAttributes(() -> {
  Map<String, String> attrs = new HashMap<>();
  attrs.put("cloud.region", System.getenv("REGION"));
  attrs.put("k8s.pod.name", System.getenv("POD_NAME"));
  return attrs;
});
```

返回 `null` 的键值对会被忽略；单个 provider 抛异常只影响它自己。由于在导出线程读取，provider
可以查询原本只在运行时才确定的信息（发布的版本号、灰度分组等）。

### 9.5 SpanProcessor

在 span 交给任何消费者之前做一次治理。返回值就是后续所有消费者看到的 span：

- 返回**修改后的 span**：用 `span.toBuilder()` 复制一份，加属性或删属性；
- 返回**原 span**：不改变；
- 返回 **`null`**：丢弃该 span，链路到此结束，导出器和 sink 都不会收到它；
- **抛异常**：该处理器被跳过，span 原样继续传给下一个处理器，失败计入
  `weavergirl_span_processor_errors_total`（不会打断业务线程）。

```java
context.registerSpanProcessor(new SpanProcessor() {
  @Override public String name() { return "governance"; }

  @Override public SpanContext process(SpanContext span) {
    // 1. 丢弃健康检查等噪声
    String path = span.getAttribute("url.path");
    if ("/health".equals(path) || "/ready".equals(path)) {
      return null;
    }
    // 2. 脱敏：SpanContext 不可变，改属性必须 toBuilder()
    SpanContext.Builder b = span.toBuilder();
    if (span.getAttribute("http.header.authorization") != null) {
      b.removeAttribute("http.header.authorization");
    }
    // 3. 补充应用维度
    return b.attribute("app.tenant", TenantContext.current()).build();
  }
});
```

约定：

- 按**注册顺序**依次执行，后一个处理器看到前一个的输出，因此“先补属性再按属性过滤”是可行
  的组合方式。插件热卸载时 `DefaultPluginContext` 会自动注销它注册的处理器。
- 在业务线程上同步执行，保持轻量：不要做网络 IO，不要锁。
- 不要修改 `traceId` / `spanId`：消费端靠它们做关联。`toBuilder()` 保留了全部字段，只改你需要
  改的。
- 处理器不可用时 span 仍然正常发布（异常被隔离），可以通过 4.2.6 的计数确认它是否在工作。

### 9.6 HealthCheck

为插件负责的组件（连接池、缓存、下游客户端）注册健康检查。同一份结果同时供探针和 Prometheus
使用：

```java
context.registerHealthCheck(new HealthCheck() {
  @Override public String name() { return "orders-db"; }

  @Override public HealthCheckResult check() {
    if (!pool.isReachable()) {
      return HealthCheckResult.down("connection refused");
    }
    return pool.idle() == 0
        ? HealthCheckResult.degraded("pool exhausted, queries will queue")
        : HealthCheckResult.up(Collections.singletonMap("idle", String.valueOf(pool.idle())));
  }
});
```

行为约定：

- 状态为 `UP` / `DEGRADED` / `DOWN` / `UNKNOWN`；整体状态取最差值（`DOWN` > `DEGRADED` >
  `UNKNOWN` > `UP`），没有注册检查时整体为 `UP`。
- 单个检查抛异常或返回 `null` 只把**自己**标成 `DOWN`（详情里有 `error`/`message`），其它检查
  照常运行。
- 同名检查以**先注册者**为准；先注册的插件卸载后，后注册的同名检查自动接管。
- 代码在探针/抓取线程上同步执行，必须轻量；耗时的探测请在自己的后台线程缓存结果。
- `/health`（存活，始终 200）和 `/health/checks`（整体 `DOWN` 时 503）会渲染组件详情，见
  `weaver-girl-runtime-health` 插件。

### 9.7 AlertChannel / AlertRule

```java
context.registerAlertRule(
    AlertRule.builder()
        .name("orders-pool-exhausted")
        .metric("orders_pool_idle")   // 与 MetricRegistry / MetricSource 中的名字一致
        .operator("lt")
        .threshold(1)
        .severity("critical")
        .message("orders 连接池已耗尽")
        .build());

context.registerAlertChannel(alert -> chatWebhook.send(alert)); // 不要在这里做阻塞 IO
```

- 规则按名字覆盖：后注册的同名规则生效；插件卸载时只移除**自己注册的那个实例**，不会误删后来
  的替代规则。
- 通知渠道在规则的评估线程上同步回调；慢渠道会拖慢评估方，请自行入队。
- 与 `MetricSource` 配合即可形成“插件产生指标 → 插件定义规则 → 插件接收告警”的闭环，底座
  不参与具体后端。

### 9.8 完整示例

一个同时注册多种扩展点的插件见
[docs/examples/CustomObservabilityPlugin.java](examples/CustomObservabilityPlugin.java)。
