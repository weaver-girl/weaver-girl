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

Agent JAR 打包 12 个内置插件, SPI 合并, 测试稳定性修复

## ✅ P16 CI 与部署（已完成）

GitHub Actions CI (JDK 8/11/17/21), Docker, CONTRIBUTING.md, License 修复

## ✅ P17 错误处理与可观测性（已完成）

Per-interceptor metrics, plugin load status, YAML security validation

## ✅ P18 用户体验（已完成）

- 版本横幅: 启动时显示 agent 版本号
- disabledPlugins: 在 YAML 或 agent args 中按名称禁用插件
- 修复参数解析 bug: config=foo.yml,watch=true 现在正确解析
- 配置加载确认日志: 用户可看到配置是否加载成功
- weaver-example.yml 完整文档化: 所有字段、插件名列表、advice 类要求

## 📊 统计

- **源代码**: 7,481 行 (64 文件)
- **测试代码**: 6,583 行 (48 文件)
- **测试总数**: 748 个，全部通过
- **提交总数**: 81 个
- **模块**: 6 个 Maven 模块
- **内置插件**: 12 个
