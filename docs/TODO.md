# Weaver-Girl 产品能力完善 TODO

> 目标：向业内成熟产品（SkyWalking / OpenTelemetry Java Agent）看齐，补齐核心能力差距
> 更新时间：2026-06-01

## ✅ P0-P8 已完成

详见 git history。85 项增强，372 测试，62 提交。

## 🔧 P9 运营级能力（当前批次）

### 诊断工具

- [ ] **Agent 诊断模式** — `agent=diagnostic` 参数开启详细日志，列出每个类的匹配/不匹配原因
- [ ] **列出已转换类** — JMX/CLI 输出所有被增强的类名+拦截器名
- [ ] **拦截器匹配解释** — 给定类名，输出哪些拦截器匹配+原因（类似 OTel 的 --explain）

### 增强范围控制

- [ ] **包允许列表** — `onlyInterceptPackages` 配置，只增强指定包下的类
- [ ] **转换数限制** — `maxTransformations` 配置，防止意外增强过多类

### 关闭完整性

- [ ] **ConfigWatcher 停止** — shutdown 时停止文件监听线程
- [ ] **AgentBuilder 注销** — shutdown 时移除 ClassFileTransformer

### 热路径优化

- [ ] **MethodInvocation 对象池** — 减少 onMethodEnter/onMethodExit 的 GC 压力

## 📊 统计

- **源文件**: 46 个 Java 文件
- **测试文件**: 31 个
- **测试总数**: 372 个，全部通过
- **提交总数**: 62 个
- **模块**: 5 个 Maven 模块
