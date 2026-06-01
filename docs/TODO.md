# Weaver-Girl 产品能力完善 TODO

> 目标：向业内成熟产品（SkyWalking / OpenTelemetry Java Agent）看齐，补齐核心能力差距
> 更新时间：2026-06-01

## ✅ P0-P5 已完成

详见 git history。

## 🔧 P6 增强正确性与生态完善（当前批次）

### 字节码增强正确性

- [ ] **方法匹配器排除 bridge/synthetic/native/abstract 方法** — 防止泛型桥接方法双重拦截、native 方法增强失败
- [ ] **Registry 索引对 NAME_PATTERN/ANNOTATION/SUPER_CLASS/INTERFACE 匹配器失效** — getInterceptorsForClass() 只能查 EXACT_NAME，模式匹配类拦截器在运行时查找丢失
- [ ] **构造器/静态方法增强边界** — 构造器不能用 @Advice.Origin Method，静态方法 @Advice.This 为 null

### 插件生态

- [ ] **插件依赖库支持** — PluginClassLoader 加载 lib/ 目录下的第三方 JAR
- [ ] **PluginClassLoader 安全沙箱** — 限制 child-first 仅适用于插件自身包，防止 API 类覆盖
- [ ] **插件开发者指南** — docs/plugin-developer-guide.md

### 配置正确性

- [ ] **YAML regex 模式校验** — 捕获 PatternSyntaxException 防止无效正则崩溃 agent
- [ ] **ConfigWatcher 热重载后触发 retransform** — 新注册拦截器对已加载类生效
- [ ] **WeaverConfig 增加代理级配置** — 采样率、熔断阈值、日志级别等可在 YAML 中配置

### 集成测试深度

- [ ] **-javaagent 端到端集成测试** — 启动子进程验证完整 premain 生命周期
- [ ] **ConfigWatcher 热重载测试** — 文件变更→拦截器更新→已加载类重转换
- [ ] **静态方法拦截测试** — 验证 target=null 场景

### 文档与发布

- [ ] **README.md** — 项目简介、快速开始、架构图
- [ ] **CHANGELOG.md** — 版本变更记录
- [ ] **根 pom.xml 版本管理** — 统一版本号 + maven-release-plugin

## 📊 统计

- **源文件**: 46 个 Java 文件
- **测试文件**: 28 个
- **测试总数**: 322 个，全部通过
- **提交总数**: 47+ 个
- **模块**: 5 个 Maven 模块 (api, core, annotation, agent, sample)
