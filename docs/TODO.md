# Weaver-Girl 产品能力完善 TODO

> 目标：向业内成熟产品（SkyWalking / OpenTelemetry Java Agent）看齐
> 更新时间：2026-06-01

## ✅ P0-P12 已完成

核心引擎 + 运营能力 + 文档打磨。详见 git history。

## ✅ P13 内置插件生态（已完成）

### 7 个内置 Instrumentation 插件

| 插件 | 拦截目标 | 核心能力 |
|------|---------|---------|
| **ServletPlugin** | HttpServlet.service / Filter.doFilter | HTTP 请求计时、慢请求检测、traceId 传播 |
| **JdbcPlugin** | Statement/PreparedStatement execute | SQL 计时、慢查询检测、SQL 日志 |
| **MethodTimingPlugin** | 可配置 class/method pattern | 方法计时、慢方法检测、可配置日志级别 |
| **TraceCorrelationPlugin** | Servlet/Controller/Filter 入口 | traceId 生成/传播、MDC 注入、跨线程 |
| **SpringPlugin** | @Controller/@Service/@Repository/@Component | Spring Bean 方法计时、参数日志 |
| **ExceptionMonitorPlugin** | 可配置 class pattern | 异常频率追踪、新异常告警、堆栈摘要 |
| **RedisPlugin** | Jedis / Lettuce 命令 | Redis 命令计时、慢命令检测、key 日志 |

### 设计特点
- 无编译依赖 — 类名全部用字符串引用
- 条件激活 — 目标类不在 classpath 则 no-op
- PluginContext 配置 — 阈值、开关均可配置
- SLF4J 输出 — 不用 System.out
- ThreadContext 集成 — 跨插件 traceId 传播

## 📊 统计

- **源代码**: 6,261 行 (55 文件)
- **测试代码**: 5,236 行 (42 文件)
- **测试总数**: 584 个，全部通过
- **提交总数**: 72 个
- **模块**: 6 个 Maven 模块 (api, core, annotation, plugins, agent, sample)
