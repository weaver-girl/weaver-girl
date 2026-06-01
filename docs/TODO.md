# Weaver-Girl 产品能力完善 TODO

> 目标：向业内成熟产品（SkyWalking / OpenTelemetry Java Agent）看齐，补齐核心能力差距
> 更新时间：2026-06-01

## ✅ P0-P7 已完成

详见 git history。

## ✅ P8 最终打磨（已完成）

### Java 8 兼容性

- [x] PluginClassLoader Set.of() → HashSet static init
- [x] PluginClassLoader getClassLoadingLock() → synchronized(this)
- [x] WeaverGirlTest List.of() → Arrays.asList()

### POM 质量

- [x] 根 POM 添加项目元数据 — url / licenses / scm / developers
- [x] maven-source-plugin + maven-javadoc-plugin

### 代码清理

- [x] 移除 12 个 core 文件首行文件路径注释
- [x] InterceptorDefinition 空值校验 — name/pointcut/interceptor
- [x] MethodInvocation.getArguments() 防御性拷贝

### 错误消息

- [x] null 注册日志添加堆栈 — 多插件环境下可追踪
- [x] YAML 校验跳过时标注类名 — 多条目时可定位

### 边缘测试

- [x] MethodInvocation 边缘用例 — null/空 methodName、防御性拷贝、null return
- [x] InterceptorDefinition 空值校验测试 — 6 个测试
- [x] 熔断器测试稳定性 — 10ms cooldown + 100ms sleep

## 📊 统计

- **源文件**: 46 个 Java 文件
- **测试文件**: 31 个
- **测试总数**: 372 个，全部通过
- **提交总数**: 61 个
- **模块**: 5 个 Maven 模块 (api, core, annotation, agent, sample)

## 🏆 产品成熟度评估

| 维度 | 状态 | 说明 |
|------|------|------|
| 字节码增强 | ✅ | ByteBuddy Advice, skip/return/suppress, bridge/synthetic/native/abstract 排除 |
| 插件体系 | ✅ | SPI + ClassLoader 隔离 + 依赖解析 + lib/ 依赖 + 安全沙箱 |
| 配置系统 | ✅ | YAML + 动态重载 + regex 校验 + 排除配置 + 代理级参数 |
| 自保护 | ✅ | ignore matcher + JDK 排除 + 自身包排除 + excludedClasses |
| 韧性 | ✅ | 熔断器 + 自适应采样 + catch(Throwable) + skipMethod 回滚 |
| 可观测性 | ✅ | JMX MBean + AgentStatus + SLF4J logging + 可操作错误消息 |
| 打包 | ✅ | Shade + SPI 重写 + 版本对齐 + source/javadoc JARs |
| 文档 | ✅ | README + 插件指南 + CHANGELOG + Javadoc |
| 测试 | ✅ | 372 tests + 集成测试 + JMH 基准 + 并发测试 + 边缘用例 |
| Java 8 兼容 | ✅ | 无 Java 9+ API 在 src/main 中 |
| POM 质量 | ✅ | url/licenses/scm/developers + source/javadoc plugins |

**项目已达到 v1.0.0 发布标准。**
