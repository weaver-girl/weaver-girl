# Weaver-Girl 产品能力完善 TODO

> 目标：向业内成熟产品（SkyWalking / OpenTelemetry Java Agent）看齐，补齐核心能力差距
> 更新时间：2026-06-01

## ✅ P0-P4 已完成

详见 git history。43 项增强全部落地。

## 🔧 P5 生产缺陷修复（当前批次）

### 错误韧性

- [ ] **InterceptAdvice 异常静默吞没** — onMethodEnter/onMethodExit 的 catch 块无任何日志，生产环境无法诊断失败拦截器
- [ ] **YamlConfigLoader 每次调用反射创建 advice 实例** — 性能灾难 + 异常放大器，需缓存实例
- [ ] **PluginLoader 只 catch Exception 不 catch Throwable** — OutOfMemoryError/NoClassDefFoundError 会崩溃 agent
- [ ] **skipMethod 异常不回滚** — 拦截器调用 skipMethod() 后抛异常，方法仍被跳过，应重置 skip 状态
- [ ] **BootstrapInjection JarFile 泄漏** — new JarFile() 后未关闭

### 资源管理

- [ ] **DefaultInterceptorRegistry rebuildIndex 竞态** — clear+putAll 非原子，并发注册时查找可能返回空列表
- [ ] **register() 不替换同名定义** — Javadoc 承诺替换但实际是 add，导致重复拦截
- [ ] **ThreadContext ThreadLocal 泄漏** — clear() 不调用 remove()，线程池环境跨请求污染
- [ ] **ConfigWatcher 无防抖** — 编辑器保存触发多次事件，中间态可能丢失拦截器

### Agent 打包

- [ ] **Agent 无 SLF4J 实现** — 生产环境所有日志静默丢弃，等于盲跑
- [ ] **Shade 不重定位 SLF4J** — 与目标应用的 SLF4J 版本冲突

### API 缺陷

- [ ] **MethodInvocation 不暴露 Method 对象** — 插件开发者无法获取完整方法签名/返回类型/注解
- [ ] **无 suppressException 机制** — onException 无法阻止异常传播，破坏熔断/降级模式
- [ ] **DefaultPluginContext 不做命名空间解析** — 文档承诺的 weavergirl.plugin.<name>. 前缀不生效
- [ ] **WeaverGirlAgent 不传递 config 给 bootstrap** — PluginContext.getConfig() 永远返回 null

### 拦截器韧性

- [ ] **无拦截器超时/熔断机制** — 慢拦截器拖垮整个应用

### 测试覆盖

- [ ] **WeaverTransformer 无单元测试** — 核心转换引擎零直接覆盖
- [ ] **ConfigWatcher 无测试** — 热重载功能零覆盖
- [ ] **WeaverGirlAgent 无测试** — 入口参数解析零覆盖

## 📊 统计

- **源文件**: 45 个 Java 文件
- **测试文件**: 25 个
- **测试总数**: 288 个，全部通过
- **提交总数**: 43+ 个
- **模块**: 5 个 Maven 模块 (api, core, annotation, agent, sample)
