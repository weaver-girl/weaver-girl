# Weaver-Girl 产品成熟度路线图

> 向成熟 AOP/可观测性产品看齐（参考 SkyWalking、OpenTelemetry、New Relic Agent）
> 当前进度：141 commits, 145 主代码文件, 101 测试文件, 16 内置插件

---

## Phase 1: Core AOP 通用性提升 (P79-P84)

> 目标：三种注册路径（注解/Java API/配置文件）表达能力一致，新增切面无需改框架代码

- [x] **P79: Fix MethodMatcher.ANNOTATION/SIGNATURE in WeaverTransformer** ✅ 2026-06-05
  - 修复 `buildMethodMatcher()` 对 ANNOTATION 和 SIGNATURE 的 fallthrough 问题
  - 修复 `MethodMatcher.matches(String)` 对 ANNOTATION 类型返回 true
  - 文件: `WeaverTransformer.java`、`MethodMatcher.java`
  - 测试: `WeaverTransformerAnnotationTest` (4 tests, all pass)

- [x] **P80: PointcutExpression DSL — 统一切点表达式语言** ✅ 2026-06-05
  - 支持 `execution(* com.example..*(..))`、`@annotation()`、`@within()`、`subclassOf()` 等
  - Plan: 同上 Task 2
  - 文件: 新增 `PointcutExpression.java`、`PointcutParser.java`

- [x] **P81: AnnotationHandlerRegistry — 可扩展注解系统** ✅ 2026-06-05
  - 用户注册 `AnnotationHandler` 即可支持自定义注解

- [x] **P82: YAML 配置扩展 — 全匹配器支持** ✅ 2026-06-05
  - 新增 `classAnnotation`/`superClass`/`interfaceName`/`methodAnnotation`/`pointcut` 字段

- [x] **P83: Java API — interceptExpression() 集成** ✅ 2026-06-05

- [x] **P84: 三路径一致性集成测试** ✅ 2026-06-05

---

## Phase 2: Span 导出与链路追踪完善 (P85-P90)

> 目标：从单机拦截扩展到全链路追踪，对接 OpenTelemetry 生态

- [x] **P85: 提交 SpanData/SpanExporter/SpanFormatter** ✅ 2026-06-05
- [x] **P86: Tracer → SpanExporter 联通** ✅ 2026-06-05
- [x] **P87: 跨线程 Trace Context 传播** ✅ 2026-06-05

- [x] **P88: 跨服务 HTTP Trace Context 传播** ✅ 2026-06-05
- [x] **P89: OpenTelemetry OTLP Bridge** ✅ 2026-06-05
- [x] **P90: Baggage 与 Span Links** ✅ 2026-06-05

---

## Phase 3: 可观测性增强 (P91-P96)

> 目标：指标、告警、拓扑图，形成完整的可观测性三角（Metrics/Traces/Logs）

- [ ] **P91: Metric 标签体系与 Histogram**
  - Metric 支持标签（如 `http_requests_total{method="GET",path="/api/users"}`）
  - Histogram 百分位计算（P50/P95/P99）
  - 文件: 修改 `api/metrics/`、`core/metrics/`

- [ ] **P92: Alert 规则引擎**
  - 可配置告警规则：`if metric:http_error_rate > 5% for 3min then alert`
  - 规则 DSL: YAML 定义告警条件
  - 告警级别: INFO / WARN / CRITICAL
  - 文件: 新增 `core/alert/AlertRuleEngine.java`、`AlertRule.java`

- [ ] **P93: Alert 通知渠道**
  - Webhook（通用 HTTP 回调）
  - Email（SMTP）
  - 日志（SLF4J）
  - 可扩展 `AlertChannel` 接口
  - 文件: 新增 `core/alert/WebhookAlertChannel.java` 等

- [ ] **P94: Alert 去重与抑制**
  - 相同告警在时间窗口内不重复发送
  - 高级别告警抑制低级别（如 CRITICAL 抑制 WARN）
  - 告警恢复通知
  - 文件: 新增 `core/alert/AlertDeduplicator.java`

- [ ] **P95: Service Topology 自动构建**
  - 基于 trace 数据自动构建服务依赖图
  - 记录服务间调用关系、延迟、错误率
  - 导出 topology JSON 供可视化
  - 文件: 新增 `core/topology/TopologyBuilder.java`、`ServiceNode.java`

- [ ] **P96: Topology 实时更新与查询 API**
  - Topology 数据随 trace 实时更新
  - REST API 查询当前 topology
  - 支持按时间段查询历史 topology
  - 文件: 修改 `core/management/`

---

## Phase 4: 生产加固 (P97-P102)

> 目标：生产环境可用，性能可控，运维友好

- [ ] **P97: 性能基准测试 (JMH)**
  - 拦截开销基准：空拦截器 vs 无拦截
  - Registry 查找性能
  - Span 序列化性能
  - 内存占用测试
  - 文件: 新增 `bench/JmhBenchmark.java`

- [ ] **P98: Agent 自监控**
  - Agent 自身的 CPU/内存/线程使用监控
  - 拦截器执行时间统计
  - 缓冲区使用率告警
  - MBean 暴露所有内部指标
  - 文件: 修改 `core/management/AgentMonitor.java`

- [ ] **P99: 启动时间优化**
  - 延迟初始化非关键组件
  - 并行加载插件
  - ClassLoader 缓存预热
  - 目标: Agent 启动时间 < 500ms

- [ ] **P100: 兼容性测试矩阵**
  - Java 8 / 11 / 17 / 21 测试
  - Spring Boot 2.x / 3.x 兼容
  - Tomcat / Jetty / Undertow 测试
  - CI 矩阵自动化

- [ ] **P101: 故障隔离增强**
  - 单个插件异常不影响其他插件
  - 拦截器异常自动熔断
  - ClassLoader 泄漏检测
  - OOM 保护

- [ ] **P102: 优雅降级策略**
  - Buffer 满时自动降级采样率
  - 后端不可达时本地缓存
  - 极端情况下可完全禁用拦截
  - 配置开关：`weavergirl.emergency.disable=true`

---

## Phase 5: 文档与开发者体验 (P103-P107)

> 目标：新用户 30 分钟上手，开发者 1 小时写出第一个插件

- [ ] **P103: Getting Started Guide**
  - 5 分钟快速开始：javaagent 挂载 + YAML 配置
  - 10 分钟第一个自定义拦截器
  - 30 分钟第一个插件

- [ ] **P104: Configuration Reference**
  - 完整 YAML 配置项文档
  - 每个配置项的说明、默认值、示例
  - PointcutExpression 语法参考

- [ ] **P105: Plugin Developer Guide**
  - 插件开发步骤
  - PluginContext API 详解
  - 测试插件的推荐方式
  - 插件发布流程

- [ ] **P106: Architecture Deep-Dive**
  - 5 层架构详解
  - 字节码转换流程图
  - 类加载模型
  - 线程模型

- [ ] **P107: API Javadoc 补全**
  - 所有 public API 100% Javadoc 覆盖
  - 每个方法的使用示例
  - `@since` 版本标注

---

## Phase 6: 生态集成 (P108-P112)

> 目标：融入 Java 可观测性生态，降低接入成本

- [ ] **P108: Spring Boot Starter**
  - `weaver-girl-spring-boot-starter` 模块
  - 自动配置：`@EnableWeaverGirl`
  - Spring Boot Actuator 集成
  - application.yml 配置支持

- [ ] **P109: Micrometer 桥接**
  - Metric 数据导出到 Micrometer Registry
  - 复用 Spring Boot 的 Micrometer 生态
  - 支持 Prometheus/Datadog/InfluxDB 等

- [ ] **P110: Log Correlation**
  - MDC 自动注入 traceId/spanId
  - 日志与 Trace 自动关联
  - 支持 Logback/Log4j2/Jul

- [ ] **P111: GraalVM Native Image 支持**
  - Native Image 兼容性配置
  - 提前注册被拦截的类
  - 替代字节码增强的编译时方案

- [ ] **P112: 示例应用完善**
  - Spring Boot 完整示例（REST API + DB + Cache）
  - 分布式示例（多服务 + Trace 传播）
  - 性能测试示例

---

## Phase 7: 发布与社区 (P113-P116)

> 目标：可发布的 1.0 版本，Maven Central 可用

- [ ] **P113: Maven Central 发布**
  - Sonatype OSSRH 账号
  - GPG 签名
  - POM 元数据完善（SCM、开发者、许可证）
  - 发布流程自动化

- [ ] **P114: CI/CD 流水线**
  - GitHub Actions: build + test + coverage
  - 自动 snapshot 发布
  - Release 自动发布到 Maven Central
  - 兼容性矩阵测试

- [ ] **P115: API 稳定性保证**
  - 公开 API 标注 `@stable`/`@experimental`/`@internal`
  - 二进制兼容性检查（japicmp）
  - 废弃 API 的移除策略文档

- [ ] **P116: 1.0 Release**
  - 所有 Phase 1-5 完成
  - 文档齐全
  - 性能基准达标
  - Maven Central 发布
  - Release Notes

---

## 进度统计

| Phase | 名称 | 总项 | 完成 | 进度 |
|-------|------|------|------|------|
| 1 | Core AOP 通用性 | 6 | 6 | ██████ 100% |
| 2 | Span 导出与链路追踪 | 6 | 6 | ██████ 100% |
| 3 | 可观测性增强 | 6 | 0 | ░░░░░░ 0% |
| 4 | 生产加固 | 6 | 0 | ░░░░░░ 0% |
| 5 | 文档与开发者体验 | 5 | 0 | ░░░░░░ 0% |
| 6 | 生态集成 | 5 | 0 | ░░░░░░ 0% |
| 7 | 发布与社区 | 4 | 0 | ░░░░░░ 0% |
| **Total** | | **38** | **12** | **███░░░ 32%** |

---

## 已完成功能 (P1-P78)

<details>
<summary>点击展开已完成功能列表</summary>

- P1-P10: 基础框架搭建（5 层架构、模块结构）
- P11-P20: 核心拦截能力（ByteBuddy 集成、Advice 模式）
- P21-P30: 插件系统（SPI、ClassLoader 隔离、依赖解析）
- P31-P40: 内置插件（Servlet、JDBC、Redis、Kafka、MongoDB 等 16 个）
- P41-P50: 配置系统（YAML 加载、动态配置、热重载）
- P51-P55: 可靠性（熔断器、采样、限流）
- P56-P60: 追踪能力（Tracer、SpanContext、W3C Trace Context）
- P61-P65: 企业特性（多租户、安全审计、i18n）
- P66-P70: 更多插件（OkHttp、RabbitMQ、Elasticsearch、E2E 测试）
- P71-P75: 插件兼容性、对象池、健康检查、发布完整性
- P76-P78: 指标报告器、自适应限流、Span 导出

</details>
