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

版本横幅, disabledPlugins, 参数解析修复, 配置加载确认, YAML 文档化

## ✅ P19 生产安全修复（已完成）

Catch Throwable, JarFile 保持打开, 采样接入, 断路器同步化, MethodInvocation clear, AtomicLong

## ✅ P20 关键测试覆盖（已完成）

新增 56 个测试 (748→804):
- ConfigWatcherTest: 6 tests (热重载、去抖、卸载后重载)
- WeaverTransformerScopeTest: 7 tests (排除、限制、范围控制)
- SamplingIntegrationTest: 6 tests (采样率、自适应调节)
- CircuitBreakerIntegrationTest: 5 tests (触发、重置、并发、Throwable)
- PluginLoaderTest: +3 tests (disabledPlugins)

## ✅ P21 文档完善（已完成）

- docs/yaml-config-reference.md: 完整配置参考 (agent 设置、拦截器定义、类/方法匹配、插件配置)
- docs/troubleshooting.md: 故障排除指南 (8 个常见问题及解决方案)
- README.md: 重写 — 30 秒快速开始、内置插件表、三种模式、文档链接

## 📊 统计

- **源代码**: 7,523 行 (64 文件)
- **测试代码**: 7,215 行 (52 文件)
- **测试总数**: 804 个，全部通过
- **提交总数**: 88 个
- **模块**: 6 个 Maven 模块
- **内置插件**: 12 个
- **文档**: 7 个文件 (README, architecture, plugin-dev-guide, yaml-config-reference, troubleshooting, CONTRIBUTING, CHANGELOG)
