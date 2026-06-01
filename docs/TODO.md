# Weaver-Girl 产品能力完善 TODO

> 目标：向业内成熟产品（SkyWalking / OpenTelemetry Java Agent）看齐，补齐核心能力差距
> 更新时间：2026-06-01

## ✅ P0-P8 已完成

详见 git history。

## ✅ P9 运营级能力（已完成）

### 诊断工具

- [x] **Agent 诊断模式** — `debug=true` 参数开启详细类匹配日志 + onIgnored 回调
- [x] **列出已转换类** — AgentStatus.transformedClasses (capped at 10000) + WeaverGirl.getTransformedClasses()
- [x] **拦截器匹配日志** — debug 模式输出每个类的匹配/不匹配原因

### 增强范围控制

- [x] **包允许列表** — `onlyInterceptPackages` YAML 配置，只增强指定包下的类
- [x] **转换数限制** — `maxTransformations` 配置 (默认 10000)，防止意外增强过多类

### 关闭完整性

- [x] **ConfigWatcher 停止** — shutdown 时停止文件监听线程
- [x] **WeaverGirl.setConfigWatcher()** — 代理入口点设置 watcher 引用

### 热路径优化

- [x] **MethodInvocation 对象池** — ThreadLocal 池减少 GC 压力
- [x] **MethodInvocation.reset()** — 池化重用方法
- [x] **InterceptAdvice 使用池** — acquire/release 替代 new MethodInvocation

## 📊 统计

- **源文件**: 47 个 Java 文件
- **测试文件**: 32 个
- **测试总数**: 384 个，全部通过
- **提交总数**: 65 个
- **模块**: 5 个 Maven 模块 (api, core, annotation, agent, sample)
