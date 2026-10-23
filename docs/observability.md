# 可观测性设计（Observability）

> 本文说明 weaver-girl 的 Trace、Metrics、Events 三类信号如何产生、如何导出，以及在
> OpenTelemetry / Prometheus 体系里如何消费。实现分布在 `weaver-girl-api`（传播与指标注册）、
> `weaver-girl-core`（Span 导出管线）和 `weaver-girl-runtime-*` 运行时插件（Prometheus、
> OTLP 日志、JSON 事件）中。

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
`text/plain; version=0.0.4; charset=utf-8`。每次抓取都会实时渲染以下四组指标。

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

### 4.5 常用 PromQL

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
- Prometheus 输出不含 histogram 和 exemplar（text 0.0.4 限制）；业务分布看 p50/p90/p95/p99 窗口值。
- `MetricRegistry` 指标是滑动窗口值而非进程生命周期累计值；窗口内无样本时序列会消失。
- JVM 指标反映的是**被织入进程**，一个 JVM 一个 scrape target。
- 跨进程传播目前落在 Servlet 边界；HTTP client、消息队列等协议需要对应插件调用
  `Tracer.inject` / `Tracer.extract`（后续插件可直接复用这套 API）。
