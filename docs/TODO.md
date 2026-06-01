# Weaver-Girl 产品能力完善 TODO

> 目标：向业内成熟产品（SkyWalking / OpenTelemetry Java Agent）看齐
> 更新时间：2026-06-01

## ✅ P0-P21 已完成

核心引擎 + 12 插件 + CI + Docker + 安全 + 测试 + 文档。详见 git history。

## ✅ P22 关键插件匹配修复（已完成）

**发现：Servlet/JDBC 插件在生产中完全不工作！**

- ServletPlugin: `byName(HttpServlet)` → `bySuperClass` (匹配 FrameworkServlet 等子类)
- ServletPlugin: `byName(Filter)` → `byInterface` (匹配所有 Filter 实现)
- JdbcPlugin: `byName(Statement)` → `byInterface` (匹配 PgStatement, HikariProxyStatement 等)
- SpringPlugin: 重写为双策略 — @Controller × @RequestMapping handler 方法 + @Service 非 Object 方法
- MethodInvocation: 新增 `setArgument(int, Object)` 修复 OkHttp trace header 注入 (之前修改 clone 被丢弃)
- HttpClientPlugin: 使用 `getArgument(int)` 替代 `getArguments()` 减少数组克隆开销

## 📊 统计

- **源代码**: 7,642 行 (64 文件)
- **测试代码**: 7,274 行 (52 文件)
- **测试总数**: 802 个，全部通过
- **提交总数**: 91 个
- **模块**: 6 个 Maven 模块
- **内置插件**: 12 个
- **文档**: 7 个文件
