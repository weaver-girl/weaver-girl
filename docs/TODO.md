# Weaver-Girl 产品能力完善 TODO

> 目标：向业内成熟产品（SkyWalking / OpenTelemetry Java Agent）看齐
> 更新时间：2026-06-01

## ✅ P0-P24 已完成

核心引擎 + 12 插件 + CI + Docker + 安全 + 测试 + 文档 + 插件匹配修复 + 结构化事件 + 发布流程 + MatchType 回归测试。详见 git history。

**P22-P24 关键修复:**
- 6 个插件的 byName→byInterface/bySuperClass 修复（生产零拦截 bug）
- 12 个插件全部接入 InterceptorEventPublisher（jsonEvents=true 真正生效）
- MatchType 回归测试（12 个测试，防止同类 bug 再发）
- Release workflow 修复（SNAPSHOT→正式版本号）
- InterceptorEventPublisher 从 core 移至 api（插件可访问）

## 📊 当前统计

- **源代码**: ~9,500 行 (75+ 文件)
- **测试代码**: ~8,500 行 (58+ 文件)
- **测试总数**: 848 个，全部通过
- **提交总数**: 98 个
- **模块**: 6 个 Maven 模块
- **内置插件**: 12 个（全部接入事件系统）
- **文档**: 7+ 个文件

## 🔮 待完成 — 向生产级产品看齐

### P25: Sample App 实战化 [HIGH]
**现状:** Sample app 只是纯 POJO，无法展示任何真实框架插件的输出。
**目标:** 添加嵌入式 Jetty + H2 数据库的示例，让 Servlet/JDBC 插件真正工作。
- [ ] 添加 weaver-girl-sample 对 Jetty + H2 的依赖
- [ ] 创建嵌入式 Jetty 启动类 + HttpServlet
- [ ] 创建 JDBC 查询示例（使用 H2 内存数据库）
- [ ] 启动时输出演示：哪些插件生效、拦截了什么

### P26: Prometheus Metrics Export [HIGH]
**现状:** 只有 SLF4J 日志和 JSON 事件输出。无法接入 Prometheus/Grafana。
**目标:** 内置 Prometheus `/metrics` HTTP 端点。
- [ ] 添加 Prometheus exporter listener（实现 InterceptorEventListener）
- [ ] 在 agent 参数中启用：`metricsEnabled=true`
- [ ] 启动轻量 HTTP Server 暴露 `/metrics` 端点
- [ ] 慢查询/慢请求计数器、响应时间直方图

### P27: 插件配置验证 [MEDIUM]
**现状:** 插件配置错误（如 slowThreshold=abc）静默使用默认值，用户不知道配置没生效。
**目标:** 启动时验证所有插件配置，输出警告。
- [ ] 添加 PluginContext.validateConfig() 方法
- [ ] 每个插件声明支持的配置项和类型
- [ ] 启动时输出配置摘要

### P28: Agent 自身性能监控 [MEDIUM]
**现状:** Agent 自身的性能影响不可观测。
**目标:** Agent 暴露自身开销指标。
- [ ] 拦截耗时统计（每个插件的平均耗时）
- [ ] 字节码转换耗时统计
- [ ] 通过 JMX 暴露 Agent 内部指标

### P29: 社区就绪 [MEDIUM]
**现状:** 缺少贡献者友好的项目基础设施。
**目标:** 让外部开发者能快速上手。
- [ ] CONTRIBUTING.md 贡献指南
- [ ] 插件开发教程文档
- [ ] 架构设计文档（ARCHITECTURE.md）
- [ ] CHANGELOG.md 版本变更日志

### P30: 高级插件能力 [LOW]
**现状:** 插件只能做 before/after/exception 回调。
**目标:** 支持更高级的拦截模式。
- [ ] Around advice（可以跳过原方法执行）
- [ ] 异步拦截支持（CompletableFuture/Reactor 返回值）
- [ ] 方法返回值修改
