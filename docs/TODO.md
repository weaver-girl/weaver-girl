# Weaver-Girl 产品能力完善 TODO

> 目标：向业内成熟产品看齐，补齐核心能力差距

## P0 核心缺陷修复 ✅ 已完成

- [x] InterceptAdvice skipMethod / 返回值修改不生效
- [x] YamlConfigLoader advice 只调 before()
- [x] AnnotationPluginLoader @Around 语义不完整
- [x] WeaverGirl.create() 未接通 ByteBuddy
- [x] MethodInvocation arguments 非防御性拷贝
- [x] DefaultInterceptorRegistry O(N) 全表扫描
- [x] 废弃 Class.newInstance() 调用

## P1 关键能力补全（当前批次）

- [x] **返回值修改真正生效** — @Advice.Return(readOnly=false) + isReturnOverridden() 回写机制
- [x] **skipMethod 时返回值写回** — skipOn 触发后 onMethodExit 写回 invocation.getReturnValue()
- [ ] **集成测试** — 创建端到端测试验证字节码增强实际生效（用 ByteBuddy AgentBuilder 在测试中 install + 验证拦截器触发）
- [x] **Agent 日志框架** — 替换 System.out.println 为 SLF4J
- [x] **Fluent Builder 扩展** — AbstractPlugin.InterceptorDefinitionBuilder 支持 byNamePattern/byAnnotation/bySuperClass/byInterface + methodPattern/methodAnnotated + priority + after/onException

## P2 功能增强

- [ ] **多 ClassLoader 支持** — PluginLoader 使用多 ClassLoader 策略发现插件
- [ ] **插件生命周期** — WeaverPlugin 增加 shutdown() / onError() 回调
- [ ] **InterceptorRegistry.unregister()** — 支持移除已注册的定义
- [ ] **方法签名匹配** — MethodMatcher 支持按参数类型匹配（不只是方法名）
- [ ] **Pointcut 组合** — 支持 AND/OR/NOT 逻辑组合
- [ ] **equals/hashCode** — 值对象（InterceptorDefinition, Pointcut, ClassMatcher, MethodMatcher）实现 equals/hashCode

## P3 生产就绪

- [ ] **错误恢复** — Agent 初始化失败不影响目标 JVM 启动
- [ ] **配置验证** — YAML 配置启动时校验必填字段
- [ ] **性能基准测试** — JMH 微基准测试拦截开销
- [ ] **文档** — README 更新 + 使用指南 + API Javadoc
