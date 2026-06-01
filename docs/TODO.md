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

- **CRITICAL**: InterceptAdvice 捕获 Throwable 防止 OOM/StackOverflow 穿透到目标应用
- **HIGH**: BootstrapInjection 保持 JarFile 打开（修复 ZipFile closed 错误）
- **HIGH**: SamplingController.shouldSample() 接入 InterceptAdvice（采样之前是死代码）
- **HIGH**: InterceptorCircuitBreaker 同步化状态转换（修复竞态条件）
- **HIGH**: MethodInvocationPool.release() 清除对象引用（修复 ClassLoader 内存泄漏）
- **MEDIUM**: AgentStatus.InterceptorMetrics 使用 AtomicLong（修复计数丢失）

## 📊 统计

- **源代码**: 7,523 行 (64 文件)
- **测试代码**: 6,593 行 (48 文件)
- **测试总数**: 748 个，全部通过
- **提交总数**: 84 个
- **模块**: 6 个 Maven 模块
- **内置插件**: 12 个
