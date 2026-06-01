# Weaver-Girl 产品能力完善 TODO

> 目标：向业内成熟产品（SkyWalking / OpenTelemetry Java Agent）看齐，补齐核心能力差距
> 更新时间：2026-06-01

## ✅ P0-P5 已完成

详见 git history。

## ✅ P6 增强正确性与生态完善（已完成）

### 字节码增强正确性

- [x] 方法匹配器排除 bridge/synthetic/native/abstract 方法 — 防止泛型桥接双重拦截
- [x] Registry 索引对 NAME_PATTERN/ANNOTATION/SUPER_CLASS/INTERFACE 匹配器生效 — 扫描所有定义
- [x] 静态方法增强 — @Advice.This(optional=true) + target=null 测试
- [x] 构造器/静态方法边界 — 静态方法拦截完整测试覆盖

### 插件生态

- [x] 插件依赖库支持 — PluginClassLoader 加载 lib/ 目录下 JAR
- [x] PluginClassLoader 安全沙箱 — 限制 child-first 仅非 API 包，防类覆盖
- [x] 插件开发者指南 — docs/plugin-developer-guide.md

### 配置正确性

- [x] YAML regex 模式校验 — 捕获 PatternSyntaxException
- [x] ConfigWatcher 热重载后触发 retransform — afterReloadCallback
- [x] WeaverConfig 代理级配置 — 采样阈值/熔断参数/排除类/日志级别

### 集成测试深度

- [x] Premain 生命周期集成测试 — WeaverGirlAgentTest
- [x] 静态方法拦截测试 — target=null + before/after 回调

### 文档与发布

- [x] README.md — 项目简介、快速开始、架构图
- [x] CHANGELOG.md — 版本变更记录
- [x] 插件开发者指南 — 完整的插件创建步骤和 API 参考

## 📊 统计

- **源文件**: 46 个 Java 文件
- **测试文件**: 30 个
- **测试总数**: 350 个，全部通过
- **提交总数**: 52 个
- **模块**: 5 个 Maven 模块 (api, core, annotation, agent, sample)
