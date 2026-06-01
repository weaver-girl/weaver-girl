# Weaver-Girl 产品能力完善 TODO

> 目标：向业内成熟产品（SkyWalking / OpenTelemetry Java Agent）看齐
> 更新时间：2026-06-01

## ✅ P0-P12 已完成

核心引擎 + 运营能力 + 文档打磨。详见 git history。

## ✅ P13 内置插件生态（已完成）

7 个插件: Servlet, JDBC, MethodTiming, TraceCorrelation, Spring, ExceptionMonitor, Redis

## ✅ P14 扩展插件生态（已完成）

5 个插件: HttpClient, gRPC, Kafka, MongoDB, Logging

## ✅ P15 产品打包（已完成）

Agent JAR 打包 12 个内置插件 (4.7MB fat JAR), SPI 合并, 测试稳定性修复

## ✅ P16 CI 与部署（已完成）

- GitHub Actions CI: JDK 8/11/17/21 矩阵测试
- Dockerfile + docker-compose.yml
- CONTRIBUTING.md
- 修复 License 歧义 (MIT)

## ✅ P17 错误处理与可观测性（已完成）

- AgentStatus: 按 interceptor 追踪调用次数和错误
- AgentStatus: 按 plugin 追踪加载状态 (LOADED/FAILED)
- PluginLoader: 汇总报告失败插件名
- YamlConfigLoader: 在实例化前验证 advice 类实现 Interceptor 接口

## 📊 统计

- **源代码**: 7,394 行 (64 文件)
- **测试代码**: 6,583 行 (48 文件)
- **测试总数**: 748 个，全部通过
- **提交总数**: 79 个
- **模块**: 6 个 Maven 模块
- **内置插件**: 12 个
