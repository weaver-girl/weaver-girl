# Weaver-Girl 产品能力完善 TODO

> 目标：向业内成熟产品（SkyWalking / OpenTelemetry Java Agent）看齐，补齐核心能力差距
> 更新时间：2026-06-01

## ✅ P0-P7 已完成

详见 git history。

## 🔧 P8 最终打磨（当前批次）

### Java 8 兼容性（已修复）

- [x] **PluginClassLoader Set.of()** — 替换为 HashSet static init
- [x] **PluginClassLoader getClassLoadingLock()** — 替换为 synchronized(this)
- [x] **WeaverGirlTest List.of()** — 替换为 Arrays.asList()

### POM 质量（Agent 运行中）

- [ ] **根 POM 添加项目元数据** — url / licenses / scm / developers
- [ ] **maven-source-plugin + maven-javadoc-plugin** — 发布质量制品

### 代码清理（Agent 运行中）

- [ ] **移除文件路径注释** — 8 个 core 文件首行 IDE 噪声
- [ ] **InterceptorDefinition 空值校验** — name/pointcut/interceptor 不能为 null

### 错误消息（Agent 运行中）

- [ ] **null 注册日志添加堆栈** — 多插件环境下可追踪
- [ ] **YAML 校验跳过时标注类名** — 多条目时可定位

### 边缘测试（Agent 运行中）

- [ ] **MethodInvocation 边缘用例** — null/空 methodName、防御性拷贝
- [ ] **InterceptorDefinition 空值校验测试** — null name/pointcut/interceptor
- [ ] **熔断器测试稳定性** — Thread.sleep 替换为更可靠等待

## 📊 统计

- **源文件**: 46 个 Java 文件
- **测试文件**: 30+ 个
- **测试总数**: 350+ 个，全部通过
- **提交总数**: 58+ 个
- **模块**: 5 个 Maven 模块 (api, core, annotation, agent, sample)
