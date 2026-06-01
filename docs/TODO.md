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

## 🔧 P2 生产加固（当前批次）

- [ ] **MethodMatcher 测试** — ANNOTATION 和 NAME_PATTERN 匹配类型无测试覆盖
- [ ] **WeaverGirl/InterceptBuilder 测试** — programmatic API 零测试
- [ ] **MethodInvocation 测试** — 防御性拷贝、setReturnValue/isReturnOverridden、skipMethod、getArgument 边界
- [ ] **AnnotationPluginLoader 测试** — 注解扫描 + 反射拦截器创建无测试
- [ ] **方法签名匹配** — MethodMatcher 支持按参数类型匹配（不只是方法名）
- [ ] **Pointcut 组合** — 支持 AND/OR/NOT 逻辑组合
- [ ] **equals/hashCode** — 值对象实现 equals/hashCode
- [ ] **Agent shutdown hook** — 注册 Runtime.getRuntime().addShutdownHook() 调用 WeaverGirl.shutdown()
- [ ] **Agent 初始化容错** — Agent 初始化失败不影响目标 JVM 启动
- [ ] **YAML 配置校验** — 启动时校验必填字段，报错而非静默失败

## 🏗️ P3 生产就绪（下一批次）

- [ ] **ClassLoader 隔离** — 每个插件使用独立的 child-first ClassLoader
- [ ] **Bootstrap 类注入** — 支持拦截 java.* / javax.* 类
- [ ] **Agent JAR 打包验证** — maven-shade-plugin + MANIFEST 正确性集成测试
- [ ] **已加载类重转换** — agentmain attach 时 retransformClasses 已加载类
- [ ] **配置动态重载** — 监听 YAML 文件变更，热更新拦截器配置
- [ ] **跨线程上下文传播** — Runnable/Callable 包装器携带上下文
- [ ] **值对象不可变** — InterceptorDefinition, Pointcut 改为不可变类
- [ ] **API Javadoc** — 所有 public API 完整 Javadoc

## 🚀 P4 企业级能力（远期）

- [ ] **性能基准测试** — JMH 微基准测试拦截开销
- [ ] **健康检查 / 状态报告** — 活跃插件数、转换次数、错误计数
- [ ] **自适应采样** — 负载过高时降低拦截开销
- [ ] **多 Agent 共存测试** — 与 SkyWalking/OpenTelemetry 共存无冲突
- [ ] **插件依赖解析** — 插件间依赖的有序加载
- [ ] **条件化增强** — 仅在目标类/方法实际存在时增强（避免 NoClassDefFoundError）
- [ ] **Agent 自诊断** — JMX MBean 或 HTTP 端点暴露 agent 状态
