# Weaver-Girl 产品能力完善 TODO

> 目标：向业内成熟产品（SkyWalking / OpenTelemetry Java Agent）看齐，补齐核心能力差距
> 更新时间：2026-06-01

## ✅ P0-P6 已完成

详见 git history。

## 🔧 P7 发布阻塞修复（当前批次）

### 关键阻塞

- [ ] **SLF4J 版本不匹配** — slf4j-api 1.7.36 + slf4j-simple 2.0.9 不兼容，Agent 运行时全部日志静默
- [ ] **Shade ServicesResourceTransformer 缺失** — SPI 服务文件未重写，SLF4J 绑定失败
- [ ] **Agent 无自我保护** — 会增强自身类导致 ClassCircularityError
- [ ] **excludedClasses 配置是死代码** — YAML 排除类配置不生效
- [ ] **无 JDK 内部类排除** — 用户可能意外增强 sun.*/jdk.internal.*

### 改进项

- [ ] **版本一致性** — slf4j-simple / JMH 版本统一到 root dependencyManagement
- [ ] **Sample 可运行** — exec-maven-plugin + run-sample.sh 脚本

## 📊 统计

- **源文件**: 46 个 Java 文件
- **测试文件**: 30 个
- **测试总数**: 350 个，全部通过
- **提交总数**: 52+ 个
- **模块**: 5 个 Maven 模块 (api, core, annotation, agent, sample)
