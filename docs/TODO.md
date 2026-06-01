# Weaver-Girl 产品能力完善 TODO

> 目标：向业内成熟产品（SkyWalking / OpenTelemetry Java Agent）看齐
> 更新时间：2026-06-01

## ✅ P0-P12 已完成

核心引擎 + 运营能力 + 文档打磨。详见 git history。

## ✅ P13 内置插件生态（已完成）

7 个插件: Servlet, JDBC, MethodTiming, TraceCorrelation, Spring, ExceptionMonitor, Redis

## ✅ P14 扩展插件生态（已完成）

### 新增 5 个插件

| 插件 | 拦截目标 | 行数 | 核心能力 |
|------|---------|------|---------|
| **HttpClientPlugin** | Apache HttpClient / OkHttp | 317 | HTTP 请求计时、traceId 传播、慢请求检测 |
| **GrpcPlugin** | gRPC Server/Client | 159 | gRPC 调用计时、慢调用检测 |
| **KafkaPlugin** | Kafka Producer/Consumer | 261 | 消息计时、topic 日志、traceId 头部传播 |
| **MongoPlugin** | MongoDB Driver | 188 | 查询计时、collection 日志、慢查询检测 |
| **LoggingPlugin** | SLF4J MDC | 93 | MDC traceId/spanId 注入，依赖 trace-correlation |

### 完整插件列表（12 个）

| # | 插件 | 行数 | 覆盖层 |
|---|------|------|--------|
| 1 | ServletPlugin | 148 | 🌐 Web |
| 2 | SpringPlugin | 117 | 🌐 Web |
| 3 | JdbcPlugin | 176 | 🗄️ 数据库 |
| 4 | MongoPlugin | 188 | 🗄️ 数据库 |
| 5 | RedisPlugin | 134 | 🗄️ 缓存 |
| 6 | HttpClientPlugin | 317 | 🔗 远程调用 |
| 7 | GrpcPlugin | 159 | 🔗 远程调用 |
| 8 | KafkaPlugin | 261 | 📨 消息 |
| 9 | MethodTimingPlugin | 97 | ⏱️ 通用 |
| 10 | TraceCorrelationPlugin | 116 | 🔍 可观测性 |
| 11 | ExceptionMonitorPlugin | 88 | ⚠️ 异常 |
| 12 | LoggingPlugin | 93 | 📋 日志 |

## 📊 统计

- **源代码**: 7,279 行 (62 文件)
- **测试代码**: 6,583 行 (48 文件)
- **测试总数**: 768 个，全部通过
- **提交总数**: 75 个
- **模块**: 6 个 Maven 模块
- **内置插件**: 12 个