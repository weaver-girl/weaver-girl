# Weaver-Girl 产品能力完善 TODO

> 目标：向业内成熟产品（SkyWalking / OpenTelemetry Java Agent）看齐
> 更新时间：2026-06-01

## ✅ P0-P29 全部完成

### 核心引擎 (P0-P10)
- 5 模块 Maven 架构 (api/core/annotation/agent/plugins)
- ByteBuddy 字节码转换引擎
- SPI 插件加载机制
- 12 个内置插件 (Servlet/Spring/JDBC/Redis/Kafka/gRPC/MongoDB/HttpClient/MethodTiming/Trace/Exception/Logging)
- CI (GitHub Actions, JDK 8/11/17/21 矩阵)
- Docker 集成测试

### 生产安全 (P11-P15)
- 熔断器 (InterceptorCircuitBreaker)
- 自适应采样 (SamplingController)
- ThreadContext 上下文传播
- YAML 配置 + 热重载
- AgentStatus 运行状态

### 安全 + 文档 + 修复 (P16-P20)
- 安全加固 (ClassLoader 隔离, 资源限制)
- 完整文档 (README, quickstart, plugin-guide, config-reference)
- 关键 MatchType 修复 (Servlet/JDBC byName→byInterface/bySuperClass)

### 事件 + 发布 (P21-P24)
- 结构化事件系统 (InterceptorEvent → Publisher → Listener)
- JSON 事件输出 (jsonEvents=true)
- 所有 12 个插件接入事件系统
- MatchType 回归测试 (12 个防回归测试)
- Release workflow (tag → GitHub Release + SHA-256)

### 生产级能力 (P25-P29)
- **P25**: 实战化 Sample App (嵌入式 Jetty + H2 数据库)
- **P26**: Prometheus Metrics Export (metricsPort=9400, 4 个核心指标)
- **P27**: 配置验证 (getConfigLong/getConfigBoolean/getConfigEnum + 警告输出)
- **P28**: JMX 自身监控 (AgentMXBean: 拦截计数/耗时/转换类数)
- **P29**: 社区文档 (CONTRIBUTING.md, ARCHITECTURE.md, CHANGELOG.md)

## 📊 当前统计

| 指标 | 数值 |
|------|------|
| 源代码行数 | ~12,000 |
| 测试代码行数 | ~10,500 |
| 测试总数 | 914 (全部通过) |
| 提交总数 | 104 |
| Maven 模块 | 6 |
| 内置插件 | 12 (全部接入事件系统) |
| 文档文件 | 10+ |

## 🔮 后续可选 (非阻塞)

### P30: 高级插件能力 [LOW]
- Around advice（跳过原方法执行）
- 异步拦截支持（CompletableFuture/Reactor）
- 方法返回值修改

### P31: OpenTelemetry 集成 [LOW]
- OTel Span 导出 (InterceptorEvent → OTel Span)
- OTel Metric 导出 (替代自建 Prometheus)

### P32: 插件市场 [LOW]
- 外部插件 JAR 加载
- 版本兼容性检查
- 插件依赖管理

### P33: 性能基准 [LOW]
- JMH 基准测试套件
- 对比 SkyWalking/OTel Agent 的开销
- 性能回归 CI 检测
