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
| P61-P65: 运维体验 | ✅ | 2026-06-04 | 2026-06-04 |
| P66-P70: 广度扩展 | ✅ | 2026-06-04 | 2026-06-04 |

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

## 第六阶段：运维体验 (P61-P65) ✅

### P61: 指标聚合器 [HIGH] ✅
- ✅ TimeWindowAggregator (滑动窗口 + count/sum/min/max/avg/p50/p90/p95/p99)
- ✅ MetricSnapshot (不可变快照 + rate-per-second)
- ✅ MetricRegistry (命名聚合器注册表)
- ✅ 17 个测试

### P62: Agent REST API [MEDIUM] ✅
- ✅ AgentApiServer (7 endpoints: status/plugins/topology/alerts/metrics/diagnostics)
- ✅ 7 个集成测试

### P63: 配置 Schema 验证 [MEDIUM] ✅
- ✅ ConfigValidator (BOOLEAN/POSITIVE_INT/POSITIVE_LONG/PORT/STRING)
- ✅ ConfigValidationResult (errors + warnings)
- ✅ 12 个测试

### P64: 插件健康监控 [LOW] ✅
- ✅ PluginHealth (HEALTHY/DEGRADED/UNHEALTHY)
- ✅ PluginHealthRegistry (线程安全健康追踪)
- ✅ 10 个测试

### P65: 文档更新 [LOW] ✅
- ✅ README.md 更新 (30+ 特性, 13 插件)
- ✅ CHANGELOG.md 更新 (P40-P65 完整记录)

---

## 第七阶段：广度扩展 (P66-P70) ✅

> P61-P65 已完成。聚焦更多框架覆盖、端到端测试、状态持久化。

### P66: OkHttp 插件 [HIGH] ✅

- ✅ OkHttp 专用插件 (okhttp3.RealCall 拦截 + 连接池监控)
- ✅ 支持 OkHttp 3.x/4.x (RealCall 路径兼容)
- ✅ 连接池指标追踪 (idleConnectionCount, connectionCount)
- ✅ 请求/响应详情提取 (URL, method, code, body size, protocol)
- ✅ Trace 传播 (X-Trace-Id, X-Span-Id header injection)
- ✅ 28 个测试

### P67: RabbitMQ 插件 [MEDIUM] ✅

- ✅ 生产者拦截 (basicPublish: exchange, routingKey, message size)
- ✅ 消费者 ACK 拦截 (basicAck, basicNack, basicReject + deliveryTag)
- ✅ 连接生命周期拦截 (newConnection: host, port, timing)
- ✅ AMQP 消息头 Trace 传播 (X-Trace-Id, X-Span-Id)
- ✅ 28 个测试

### P68: 端到端集成测试 [MEDIUM] ✅

- ✅ FullAgentLifecycleTest: 10 阶段完整生命周期测试
  - Bootstrap & Registration
  - Interception & Events
  - Circuit Breaker (trip, cooldown, reset)
  - Sampling Controller
  - Dynamic Config (listener, rollback)
  - State Persistence
  - Multi-Plugin Coordination
  - Concurrent Stress (registration, lookup)
  - Error Recovery (隔离失败插件)
  - Graceful Shutdown
  - Unregistration
  - Registry Lookup (byInterface)
- ✅ 16 个测试

### P69: Agent 状态持久化 [LOW] ✅

- ✅ AgentStateSnapshot (Properties 格式持久化)
  - 拦截器计数、变换类计数、调用计数、错误计数
  - Agent 运行时间、采样率、熔断阈值
  - 配置快照、插件状态、自定义指标、健康状态
  - 链式 API、线程安全 (ConcurrentHashMap)
- ✅ AgentStatePersister (定期快照调度器)
  - 可配置的快照间隔和目录
  - 定时快照 + 优雅关机最终快照
  - SnapshotListener 回调 (onSnapshot, onRestore)
  - StateProvider SPI 接口
- ✅ 32 + 19 = 51 个测试

### P70: Elasticsearch 插件 [LOW] ✅

- ✅ RestHighLevelClient 拦截 (search, index, bulk, delete, update, get, msearch, scroll, reindex, count, exists)
- ✅ ES 8.x Java Client 拦截 (co.elastic.clients.elasticsearch.ElasticsearchClient)
- ✅ RestClient 底层拦截 (performRequest + endpoint + statusCode)
- ✅ 索引名提取 (getIndex/index/indices)
- ✅ 搜索结果提取 (hitCount via getHits/total/value)
- ✅ 服务端耗时提取 (took)
- ✅ Bulk 操作大小追踪
- ✅ Trace Header 注入 (setHeader/putHeader)
- ✅ 28 个测试

---

## 当前统计

| 指标 | 数值 |
|------|------|
| 源代码行数 | ~25,000 |
| 测试代码行数 | ~27,000 |
| 测试总数 | 1050 (227 api + 518 core + 283 plugins + 15 agent + 7 sample) / 全部通过 |
| 提交总数 | 140+ |
| Maven 模块 | 6 |
| 内置插件 | 16 |
| API 包 | config, tenant, sampling, tracing, security, compat, exporter, alert, topology, metrics, plugin(扩展) |
| Core 组件 | DynamicConfigManager, PluginManager, Diagnostics, AsyncEventPublisher, CachedRegistry, LoggingExporter, InMemoryExporter, AgentApiServer, ConfigValidator, AgentStateSnapshot, AgentStatePersister |
| 运维资源 | Grafana 仪表盘 (JVM + 拦截器), 加载脚本, REST API (7 endpoints) |

---

## 第八阶段：成熟度提升 (P71-P75) 🔄

> P66-P70 已完成。聚焦文档完善、性能调优、国际化、Benchmark 套件、Plugin 版本兼容性。

### P71: Plugin 版本兼容性检查 [HIGH] ✅

- ✅ PluginCompatibilityChecker (版本兼容性检查引擎)
  - Agent 版本检查 (minimumAgentVersion)
  - 重复插件检测 (同名同版本/旧版本拒绝)
  - 新版本替代提示 (同名新版本警告)
  - 依赖存在检查 (depends 列表验证)
  - 版本格式校验 (semver 格式验证)
  - 不兼容插件自动禁用 (可配置)
  - CompatibilityReport (兼容性报告: warnings + errors + autoDisabled)
  - compareVersions (semver 比较工具方法)
- ✅ 40 个测试

### P72: 性能调优 — LockFree 对象池 [MEDIUM] ✅

- ✅ LockFreeObjectPool<T> (无锁高性能对象池)
  - ConcurrentLinkedQueue 实现 (无锁 borrow/release)
  - 有界池大小 (防止内存泄漏)
  - 统计追踪 (hit/miss/borrow/return/eviction)
  - 命中率计算 (getHitRate)
  - PoolStats 不可变快照
  - 线程安全并发测试 (20 线程 × 500 操作)
  - 池大小上限验证 (10 容量压力测试)
- ✅ 19 个测试

### P73: 国际化 (i18n) [MEDIUM] ✅

- ✅ AgentMessages (轻量级国际化框架)
  - ResourceBundle 消息外部化
  - UTF-8 编码加载 (PropertyResourceBundle)
  - 参数化消息 ({0}, {1}, {2}... 占位符替换)
  - 多 Locale 缓存 (ConcurrentHashMap)
  - 自动回退到英文 (缺失翻译时)
  - 系统属性自动检测 (weaver-girl.messages / user.language)
- ✅ 消息资源文件:
  - agent-messages.properties (英文 — 50+ 消息)
  - agent-messages_zh_CN.properties (简体中文 — 完整翻译)
- ✅ 覆盖: Bootstrap, Plugin, Interceptor, Config, Diagnostics, Security, Export, Alert, Persistence, Compatibility, Errors
- ✅ 32 个测试

### P74: 文档完善 [LOW] ✅

- ✅ ARCHITECTURE.md 更新
  - 模块结构更新 (12 → 16 内置插件)
  - 模块详情表更新 (新增 OkHttp, RabbitMQ, Elasticsearch 插件)
  - 新增 "Enterprise Features (P46-P73)" 章节 (12 个子节)
  - 线程安全模型更新 (新增 10 个组件)
- ✅ README.md 已在之前阶段更新 (16 插件表格 + State Persistence 特性)
- ✅ CHANGELOG.md 完整记录 P66-P73 所有变更

### P75: 发布准备 [LOW] 🔲

- 🔲 Release 自动化流程完善
- 🔲 Maven Central 发布准备
- 🔲 安全签名和校验
