# Weaver-Girl 产品能力完善 TODO

> 目标：向业内成熟产品（SkyWalking / OpenTelemetry Java Agent）看齐
> 更新时间：2026-06-01

## ✅ P0-P12 已完成

核心引擎 + 运营能力 + 文档打磨。详见 git history。

## ✅ P13 内置插件生态（已完成）

7 个插件: Servlet, JDBC, MethodTiming, TraceCorrelation, Spring, ExceptionMonitor, Redis

## ✅ P14 扩展插件生态（已完成）

5 个插件: HttpClient, gRPC, Kafka, MongoDB, Logging

## ✅ P15 产品打包与测试稳定性（已完成）

- Agent JAR 打包 12 个内置插件 (4.7MB fat JAR)
- SPI 服务文件合并正确
- PremainLifecycleTest 环境兼容性修复
- `mvn clean install` 全通过

## 📊 统计

- **源代码**: 7,279 行 (62 文件)
- **测试代码**: 6,583 行 (48 文件)
- **测试总数**: 748 个，全部通过
- **提交总数**: 76 个
- **模块**: 6 个 Maven 模块
- **内置插件**: 12 个 (全部打包进 agent JAR)
