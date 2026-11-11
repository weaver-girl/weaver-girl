# 第一个版本能不能发（2026-10-29 审查，已被取代）

> **状态：这份 2026-10-29 的审查结论已过时。** 当前门禁结果见根目录
> `RELEASE_READINESS.md`（2026-11-11 实测）。本文件只保留当时的发现，作为修复记录。

## 当时的结论

**2026-10-29 当时不能发布。** `scripts/release-check.sh` 在 `weaver-girl-api` 的
Checkstyle 处退出码 1；基准守卫 `scripts/verify-benchmarks.sh` 因测试编译失败退出码 2。
两个问题都不是发布环境的偶发故障，而是仓库源码本身的问题。

## 当时查到的阻断项

1. **Checkstyle**：`weaver-girl-api` 有 6 处多余/未使用导入，构建在 verify 阶段失败。
   （这次只跑到 api，后面的模块全部 SKIPPED，core 及之后的模块当时并未验证。）
2. **基准脚本**：`-pl weaver-girl-core` 没有 `-am`，测试编译解析到本地仓库里的旧
   `weaver-girl-api`，缺少 `PluginContext` 的新方法，导致 JMH 没有开始跑。
3. **过程文档与 workflow 不一致**：`RELEASE_PROCEDURE.md` 声称 deploy 时关闭
   `quality-gate`，而 `release.yml` 实际是 `-Pquality-gate,release`。
4. **对外发布条件**：父版本仍是 `1.0.0-SNAPSHOT`，没有 tag、没有 Release，本机也没有
   `CENTRAL_*` / `GPG_*` 凭据。

## 这些项现在怎么样了

- Checkstyle：全仓 153 个模块 0 违规（含 core 的 21 处导入问题）。
- SpotBugs：修复 2 个真实并发缺陷（volatile 自增改 `AtomicLong`），其余为 ByteBuddy
  参数写回误报，已记录在 `spotbugs-exclude.xml`。
- 基准：基准方法改为按 iteration 设置采样率，避免把日志开销算进热路径；守卫重新跑通。
- 文档：`RELEASE_PROCEDURE.md` 已改为与 workflow 一致的 `-Pquality-gate,release`。
- 打包：新增 `weaver-girl-agent-<version>-dist.zip`（agent + 140 个能力 jar），
  `release-check.sh` 与 `release.yml` 都会校验。
- 发布凭据仍是外部条件，未知、也未在本机配置；没有推 tag、没有点 Portal 的 Publish。

当时的原始证据留在 `goal-evidence/first-release-review-2026-10-29/`（本机目录，被
`.gitignore` 忽略）。
