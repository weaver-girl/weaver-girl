# Weaver-Girl 产品能力完善 TODO

> 目标：向业内成熟产品（SkyWalking / OpenTelemetry Java Agent）看齐，补齐核心能力差距
> 更新时间：2026-06-01

## ✅ P0 核心缺陷修复（已完成）

- [x] InterceptAdvice skipMethod / 返回值修改不生效
- [x] YamlConfigLoader advice 只调 before()
- [x] AnnotationPluginLoader @Around 语义不完整
- [x] WeaverGirl.create() 未接通 ByteBuddy
- [x] MethodInvocation arguments 非防御性拷贝
- [x] DefaultInterceptorRegistry O(N) 全表扫描
- [x] 废弃 Class.newInstance() 调用
- [x] @Advice.Return(readOnly=false) 返回值写回
- [x] onMethodExit 非跳过场景创建 MethodInvocation 上下文

## ✅ P1 关键能力补全（已完成）

- [x] 集成测试 AgentIntegrationTest — before/after/onException 回调验证
- [x] Agent 日志框架 — SLF4J 替换 System.out.println
- [x] Fluent Builder 扩展 — byNamePattern/byAnnotation/bySuperClass/byInterface + methodPattern/methodAnnotated + priority + after/onException
- [x] PluginContext 接口 + DefaultPluginContext 实现
- [x] WeaverPlugin 生命周期 — init(PluginContext) / destroy()
- [x] InterceptorRegistry.unregister(name)
- [x] .gitignore

## ✅ P2 生产加固（已完成）

- [x] MethodMatcher 测试 — 12 个测试覆盖 byName/any/byNamePattern/byAnnotation
- [x] MethodInvocation 测试 — 11 个测试覆盖防御性拷贝/skipMethod/返回值/边界
- [x] Agent shutdown hook — Runtime.addShutdownHook 调用 WeaverGirl.shutdown()
- [x] Agent 初始化容错 — try-catch(Throwable) 防止目标 JVM 崩溃
- [x] YAML 配置校验 — 结构性校验（className + advice 必填），advice 类存在性延迟校验
- [x] InterceptorRegistry.unregister() — 按名称移除定义
- [x] WeaverGirl/InterceptBuilder 测试 — 8 个测试覆盖 programmatic API
- [x] AnnotationPluginLoader 测试 — 10 个测试覆盖注解扫描 + 反射拦截器创建
- [x] 方法签名匹配 — MethodMatcher.bySignature + matches(String, Class<?>[])
- [x] Pointcut 组合 — Pointcut.and() / .or() + matches(String, String)
- [x] equals/hashCode — ClassMatcher/MethodMatcher/Pointcut/InterceptorDefinition

## ✅ P3 生产就绪（已完成）

- [x] 已加载类重转换 — agentmain attach 时 retransformClasses 已加载类
- [x] 值对象不可变 — 所有字段 private final，无 setter
- [x] ClassLoader 隔离 — PluginClassLoader child-first + PluginJarScanner + loadPluginsFromDirectory
- [x] 配置动态重载 — ConfigWatcher + watch=true agent 参数
- [x] 跨线程上下文传播 — ThreadContext + ContextRunnable + ContextCallable
- [x] Bootstrap 类注入 — BootstrapInjection + InjectionStrategy.UsingInstrumentation
- [x] API Javadoc — 13 个 public API 类完整 Javadoc + @since 标签

## 🔧 P4 企业级能力（当前批次）

- [x] **条件化增强** — TypeExistenceChecker 跳过不存在类的拦截器
- [x] **健康检查 / 状态报告** — AgentStatus 单例 + 转换/调用/错误计数 + getReport()
- [x] **插件依赖解析** — WeaverPlugin.depends() + PluginDependencyResolver 拓扑排序
- [ ] **Agent 自诊断** — JMX MBean 暴露 agent 状态
- [ ] **自适应采样** — 负载过高时降低拦截开销
- [ ] **性能基准测试** — JMH 微基准测试拦截开销
- [ ] **多 Agent 共存测试** — 与 SkyWalking/OpenTelemetry 共存无冲突

## 📊 统计

- **源文件**: 36 个 Java 文件
- **测试文件**: 17+ 个
- **测试总数**: 80+ 个，全部通过
- **提交总数**: 30+ 个
- **模块**: 5 个 Maven 模块 (api, core, annotation, agent, sample)
