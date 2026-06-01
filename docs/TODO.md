# Weaver-Girl 产品能力完善 TODO

> 目标：向业内成熟产品（SkyWalking / OpenTelemetry Java Agent）看齐，补齐核心能力差距
> 更新时间：2026-06-01

## ✅ P0-P4 已完成

详见 git history。43 项增强全部落地。

## ✅ P5 生产缺陷修复（已完成）

### 错误韧性

- [x] InterceptAdvice 异常日志 — InterceptorHolder.logInterceptorError() 委托
- [x] YamlConfigLoader 缓存 advice 实例 — ConcurrentHashMap.computeIfAbsent
- [x] PluginLoader catch Throwable — 防止 NoClassDefFoundError 崩溃 agent
- [x] skipMethod 异常回滚 — setSkipMethod(false) + setSkipMethod(boolean) 新 API
- [x] BootstrapInjection JarFile 关闭 — close() after appendToBootstrapClassLoaderSearch

### 资源管理

- [x] DefaultInterceptorRegistry 原子重建 — volatile Map swap 替代 clear+putAll
- [x] register() 替换同名定义 — removeIf + add 保证不重复
- [x] ThreadContext ThreadLocal 清理 — remove() 替代 clear()
- [x] ConfigWatcher 防抖 — 2 秒 debounce 防重复加载

### Agent 打包

- [x] Agent 捆绑 SLF4J 实现 — slf4j-simple 2.0.9
- [x] Shade 重定位 SLF4J — org.slf4j → shade.org.slf4j

### API 缺陷

- [x] MethodInvocation 暴露 Method 对象 — getMethod() + getParameterTypes() + getReturnType()
- [x] suppressException 机制 — 熔断/降级模式支持
- [x] DefaultPluginContext 命名空间解析 — weavergirl.plugin.<name>. 前缀
- [x] WeaverGirlAgent 传递 config — parseAgentArgs → bootstrap(instrumentation, args)

### 拦截器韧性

- [x] 拦截器熔断 — InterceptorCircuitBreaker (5次失败→60秒冷却)

### 测试覆盖

- [x] WeaverGirlAgent 参数解析测试 — 7 个测试
- [x] DefaultInterceptorRegistry 并发测试 — 4 个测试
- [x] InterceptorCircuitBreaker 测试 — 6 个测试

## 📊 统计

- **源文件**: 46 个 Java 文件
- **测试文件**: 28 个
- **测试总数**: 322 个，全部通过
- **提交总数**: 47 个
- **模块**: 5 个 Maven 模块 (api, core, annotation, agent, sample)
