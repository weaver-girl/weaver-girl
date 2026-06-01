# Weaver-Girl 产品能力完善 TODO

> 目标：向业内成熟产品（SkyWalking / OpenTelemetry Java Agent）看齐
> 更新时间：2026-06-01

## ✅ P0-P22 已完成

核心引擎 + 12 插件 + CI + Docker + 安全 + 测试 + 文档 + 插件匹配修复。详见 git history。

## ✅ P23 剩余插件匹配修复 + 结构化事件 + 发布流程（已完成）

**同类 bug 修复 (Redis/gRPC/HttpClient)：**
- RedisPlugin: Lettuce `StatefulRedisConnection` 是接口 → `interceptImplementing()`
- HttpClientPlugin: `CloseableHttpClient` 是抽象类 → `interceptSubclassOf()`
- GrpcPlugin: `ServerCalls.UnaryMethod` 是抽象类 → `interceptSubclassOf()`

**结构化事件系统：**
- `InterceptorEvent` — 结构化事件 (type, plugin, class, method, duration, attributes)
- `InterceptorEventListener` — 事件消费者函数式接口
- `InterceptorEventPublisher` — 事件总线 (CopyOnWriteArrayList, 异常隔离)
- `JsonEventListener` — 内置 JSON 输出监听器 (零依赖手动 JSON)
- Agent flag: `jsonEvents=true` 启用 JSON 事件输出

**发布流程：**
- `.github/workflows/release.yml` — Tag 触发 GitHub Release + SHA-256 校验

## 📊 统计

- **源代码**: 8,274 行 (70 文件)
- **测试代码**: 7,896 行 (55 文件)
- **测试总数**: 824 个，全部通过
- **提交总数**: 95 个
- **模块**: 6 个 Maven 模块
- **内置插件**: 12 个
- **文档**: 7 个文件
