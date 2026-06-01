# Weaver-Girl 产品能力完善 TODO

> 目标：向业内成熟产品（SkyWalking / OpenTelemetry Java Agent）看齐，补齐核心能力差距
> 更新时间：2026-06-01

## ✅ P0-P6 已完成

详见 git history。

## ✅ P7 发布阻塞修复（已完成）

### 关键阻塞修复

- [x] **SLF4J 版本对齐** — slf4j-api + slf4j-simple 统一为 2.0.9
- [x] **Shade ServicesResourceTransformer** — SPI 服务文件重写，SLF4J 绑定生效
- [x] **Agent 自我保护** — AgentBuilder.ignore() 排除代理实现包 + shaded deps + JDK 内部类
- [x] **excludedClasses 配置生效** — YAML 排除类配置 → WeaverTransformer ignore matcher
- [x] **JDK 内部类排除** — sun.* / jdk.internal.* / com.sun.*

### 改进项

- [x] **版本一致性** — slf4j-simple + JMH 版本统一到 root dependencyManagement
- [x] **Sample 可运行** — exec-maven-plugin + run-sample.sh 脚本 + README 运行说明

## 📊 统计

- **源文件**: 46 个 Java 文件
- **测试文件**: 30 个
- **测试总数**: 350 个，全部通过
- **提交总数**: 56 个
- **模块**: 5 个 Maven 模块 (api, core, annotation, agent, sample)

## 🎯 产品成熟度评估

| 维度 | 状态 | 说明 |
|------|------|------|
| 字节码增强 | ✅ 完成 | ByteBuddy Advice inlined, skipMethod/returnValue/suppressException |
| 插件体系 | ✅ 完成 | SPI发现 + ClassLoader隔离 + 依赖解析 + lib/支持 |
| 配置系统 | ✅ 完成 | YAML + 动态重载 + 校验 + 排除配置 |
| 自保护 | ✅ 完成 | ignore matcher + JDK排除 + 自身包排除 |
| 韧性 | ✅ 完成 | 熔断 + 自适应采样 + catch(Throwable) + skipMethod回滚 |
| 可观测性 | ✅ 完成 | JMX MBean + AgentStatus + SLF4J logging |
| 打包 | ✅ 完成 | Shade + SPI重写 + 版本对齐 + MANIFEST |
| 文档 | ✅ 完成 | README + 插件指南 + CHANGELOG |
| 测试 | ✅ 完成 | 350 tests + 集成测试 + JMH基准 + 并发测试 |