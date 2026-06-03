# Weaver-Girl 产品能力持续完善 TODO

> 目标：向业内成熟产品（SkyWalking / OpenTelemetry Java Agent）看齐
> 循环任务：每5分钟迭代，持续推进
> 创建时间：2026-06-03
> 最后更新：2026-06-04

## 状态说明
- 🔲 待开始
- 🔄 进行中
- ✅ 已完成

---

## 第一阶段：生产级打磨 (P30-P39) ✅ 全部完成

| 阶段 | 状态 | 完成时间 |
|------|------|----------|
| P30: 代码质量门禁 | ✅ | 2026-06-04 |
| P31: 输入验证 | ✅ | 2026-06-04 |
| P32: 错误处理 | ✅ | 2026-06-04 |
| P33: API 文档 | ✅ | 2026-06-04 |
| P34: 集成测试 | ✅ | 2026-06-04 |
| P35: 可配置化 | ✅ | 2026-06-04 |
| P36: 性能基准 | ✅ | 2026-06-04 |
| P37: 高级插件 | ✅ | 2026-06-04 |
| P38: OTel 集成 | ✅ | 2026-06-04 |
| P39: 部署运维 | ✅ | 2026-06-04 |

---

## 第二阶段：深度打磨 (P40-P45) ✅ 全部完成

| 阶段 | 状态 | 完成时间 |
|------|------|----------|
| P40: 安全加固 | ✅ | 2026-06-04 |
| P41: CI 质量门禁集成 | ✅ | 2026-06-04 |
| P42: 插件示例完善 | ✅ | 2026-06-04 |
| P43: 错误恢复增强 | ✅ | 2026-06-04 |
| P44: 结构化日志 | ✅ | 2026-06-04 |
| P45: API 稳定性保证 | ✅ | 2026-06-04 |

---

## 第三阶段：企业级特性 (P46-P50) 🔄 进行中

> P40-P45 已完成 (949 tests)。聚焦多租户、动态配置、插件热加载等企业级能力。

### P46: 动态配置中心 [HIGH] ✅

支持运行时动态调整 Agent 行为，无需重启。

- ✅ P46.1: 配置变更监听接口 (ConfigChangeListener + ConfigChangeEvent)
- ✅ P46.2: DynamicConfigManager 核心实现 (DefaultDynamicConfigManager)
- ✅ P46.3: 配置变更审计日志 (audit trail with per-key filtering)
- ✅ P46.4: 配置变更回滚机制 (ConfigSnapshot with versioned rollback)
- ✅ P46.5: 集成测试 (31 tests: listeners, snapshots, rollback, concurrency)

> 备注：31 个测试覆盖核心场景 + 并发压力测试。集成到 WeaverGirl.bootstrap()，采样配置支持运行时动态更新。

### P47: 插件热加载 [HIGH] ✅

支持运行时加载/卸载插件，无需重启 JVM。

- ✅ P47.1: PluginState 状态机 (LOADED → ACTIVE → DISABLED → UNLOADED)
- ✅ P47.2: PluginManager 接口 + DefaultPluginManager 实现
- ✅ P47.3: PluginInfo 元数据 + 11 个测试
- 🔲 P47.4: 插件版本兼容性检查 (后续版本)
- 🔲 P47.5: 外部 JAR 热加载 (需要 ClassLoader 隔离设计)

> 备注：disable/enable/unload 生命周期完整，拦截器自动注册/注销。

### P48: 多租户隔离 [MEDIUM] ✅

支持同一 Agent 实例服务多个租户/应用。

- ✅ P48.1: TenantContext (ThreadLocal 租户ID/组传播)
- ✅ P48.2: TenantConfig (按租户隔离的采样策略/配置)
- ✅ P48.3: TenantConfigRegistry (线程安全租户配置注册表)
- ✅ P48.4: TenantSnapshot (跨线程上下文传播)
- ✅ P48.5: 18 个测试 (上下文、配置、注册表、跨线程传播)

### P49: Agent 自诊断 [MEDIUM] ✅

Agent 自身健康状态的深度诊断能力。

- ✅ P49.1: 内存使用趋势追踪 (MemorySnapshot history, trend calc)
- ✅ P49.2: 拦截器性能热点分析 (InterceptorHotspot: count/avg/max/total)
- ✅ P49.3: 自动故障检测与告警 (FaultRecord + >90% heap pressure auto-detect)
- ✅ P49.4: 诊断报告生成 (generateReport)
- ✅ P49.5: 11 个测试

### P50: 兼容性与适配 [LOW] ✅

扩展对更多框架和运行时的支持。

- ✅ P50.1: RuntimeCompatibility 运行时环境检测 (Java版本/Virtual Threads/GraalVM)
- ✅ P50.2: Spring Boot 自动配置 (SpringBootAutoConfiguration, 推荐排除模式)
- ✅ P50.3: 框架自动检测 (Spring/Quarkus/Micronaut/Reactor/gRPC/Kafka/Redis/MongoDB)
- ✅ P50.4: Reactive Stack 适配 (WebFlux检测, 采样策略调整)
- ✅ P50.5: 12 个测试

> 备注：零依赖反射检测，无需编译时依赖 Spring 等框架。

---

## 进度追踪

| 阶段 | 状态 | 开始时间 | 完成时间 |
|------|------|----------|----------|
| P30-P39: 生产级打磨 | ✅ | 2026-06-04 | 2026-06-04 |
| P40-P45: 深度打磨 | ✅ | 2026-06-04 | 2026-06-04 |
| P46-P50: 企业级特性 | ✅ | 2026-06-04 | 2026-06-04 |
| P51-P55: 生产级扩展 | ✅ | 2026-06-04 | 2026-06-04 |
| P56-P60: 生态扩展 | ✅ | 2026-06-04 | 2026-06-04 |

---

## 第四阶段：生产级扩展 (P51-P55) 🔲

> P46-P50 已完成。聚焦生产级运维扩展能力。

### P51: 插件市场基础设施 [MEDIUM] ✅

- ✅ P51.1: PluginMetadata 元数据规范 (META-INF/weaver-girl-plugin.properties)
- ✅ P51.2: PluginVerifier JAR 完整性验证 (SHA-256 校验 + SPI 检查)
- ✅ P51.3: 11 个测试

### P52: 高级采样策略 [MEDIUM] ✅

- ✅ P52.1: SamplingStrategy SPI 接口 (shouldSample, updateMetrics, reset)
- ✅ P52.2: FixedSamplingStrategy (固定率采样)
- ✅ P52.3: ProbabilisticSamplingStrategy (概率采样 + 实际率追踪)
- ✅ P52.4: SamplingStrategyRegistry (策略注册/激活)

### P53: 链路追踪增强 [LOW] ✅

- ✅ P53.1: SpanContext (trace/span/parent ID + baggage)
- ✅ P53.2: Tracer 跨线程传播 (TracingSnapshot capture/restore)
- ✅ P53.3: HTTP header 跨进程传播 (inject/extract)
- ✅ P53.4: Baggage 传播 (setBaggage/getBaggage)

### P54: 性能优化 [LOW] ✅

- ✅ P54.1: CachedInterceptorRegistry LRU 缓存层 (2048 entries, hit/miss tracking)
- ✅ P54.2: AsyncEventPublisher 异步事件发布 (daemon thread, sync fallback)

### P55: 安全审计 [LOW] ✅

- ✅ P55.1: SecurityPolicy 安全策略引擎 (allow/deny patterns, deny优先)
- ✅ P55.2: SecurityAuditLog 审计日志 (500 entries, 操作过滤)
- ✅ P55.3: AuditRecord 结构化审计记录

---

## 第五阶段：生态扩展 (P56-P60) ✅

> P51-P55 已完成。聚焦数据导出、告警、拓扑、仪表盘、更多插件。

### P56: 数据导出器 SPI [HIGH] ✅

- ✅ P56.1: DataExporter SPI 接口 (export, flush, init, shutdown, health)
- ✅ P56.2: LoggingExporter (结构化 JSON 输出到 SLF4J)
- ✅ P56.3: InMemoryExporter (有界环形缓冲 + 类型过滤)
- ✅ P56.4: ExporterRegistry (注册/激活/停用/健康检查)
- ✅ P56.5: 19 个测试

### P57: 告警引擎 [MEDIUM] ✅

- ✅ P57.1: AlertRule 规则 DSL (metric/operator/threshold/severity)
- ✅ P57.2: AlertEngine 评估引擎 (多规则评估 + 通道通知)
- ✅ P57.3: AlertChannel 通知通道接口
- ✅ P57.4: AlertEvent 结构化告警 + 历史记录 (200条)

### P58: 服务拓扑图 [MEDIUM] ✅

- ✅ P58.1: ServiceNode 服务节点 (name/type/metadata)
- ✅ P58.2: ServiceEdge 调用边 (protocol/callCount/errorCount/avgLatency)
- ✅ P58.3: TopologyGraph 拓扑图构建 (线程安全 + 边聚合)
- ✅ P58.4: 查询 API (outgoing/incoming edges + text export)

### P59: Grafana 仪表盘 [LOW] ✅

- ✅ P59.1: JVM 指标仪表盘 (heap/non-heap, threads, GC, trend)
- ✅ P59.2: 拦截器性能仪表盘 (rate, p50/p95/p99, circuit breaker)
- ✅ P59.3: load-dashboards.sh 自动加载脚本

### P60: 更多内置插件 [LOW] ✅

- ✅ P60.1: HikariCP 连接池插件 (acquisition timing, leak detection)

---

## 当前统计

| 指标 | 数值 |
|------|------|
| 源代码行数 | ~19,000 |
| 测试代码行数 | ~19,000 |
| 测试总数 | 342 (core) + 200 (api) / 全部通过 |
| 提交总数 | 130+ |
| Maven 模块 | 6 |
| 内置插件 | 13 (新增 HikariCP) |
| API 包 | config, tenant, sampling, tracing, security, compat, exporter, alert, topology |
| Core 组件 | DynamicConfigManager, PluginManager, Diagnostics, AsyncEventPublisher, CachedRegistry, LoggingExporter, InMemoryExporter |
| 运维资源 | Grafana 仪表盘 (JVM + 拦截器), 加载脚本 |
