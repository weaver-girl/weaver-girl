# 第一个版本能不能发

审查日期：2026-10-29。父版本仍是 `1.0.0-SNAPSHOT`。没有打 tag，没有 push，没有 deploy。

## 结论

**现在不能发布第一个版本。**

`scripts/release-check.sh` 在 JDK 17 上退出码 1，日志里没有 `RELEASE CHECK PASSED`。第一个失败的模块是 `weaver-girl-api`，门禁是 Checkstyle。`scripts/verify-benchmarks.sh` 退出码 2，没有产出任何基准分数，因此也不是“全部在 1.5× 预算内”。

`RELEASE_READINESS.md`（写明 verified 2026-06-15）和 workflow 注释只当作待复核的说法。下面的结论只跟这次的日志走。

## 两件不同的事

**(a) 客户能不能挂上当前的 `1.0.0-SNAPSHOT` agent。** 这不是发布。`docs/jdk8-compatibility-review.md` 记录过：在这次 `clean` 之前打出的 agent，根 class 主版本不超过 52，并且本机 Zulu `1.8.0_492` 的 bare-attach 通过了 `/health`、`/ready`、`/stats`、`/metrics`。这次 `release-check.sh` 一开始就 `clean`，在 `weaver-girl-api` 失败后把 agent 标成 `SKIPPED`，没有再打出新的 agent jar。能挂上昨天的 SNAPSHOT，不等于可以发 `v1.0.0`。

**(b) `v1.0.0` 能不能由 `.github/workflows/release.yml` 发到 Maven Central staging 和 GitHub Release。** 不能。workflow 在 `v*` tag 上执行的是 `./mvnw -B -Pquality-gate,release clean deploy`。同一次质量门禁已经在本地失败。deploy 排在创建 GitHub Release 之前，门禁失败就不会产生 Release。

## 发布门禁这次看到的失败

命令（`JAVA_HOME=/usr/lib/jvm/java-17-openjdk-amd64`，没有改脚本，没有 `-DskipTests`，没有拿掉 `-Pquality-gate`）：

```bash
scripts/release-check.sh
```

脚本自己的 JDK 检查通过了：它没有打印 `FAIL: release builds require JDK 17`，而是进入了 `./mvnw -B -Pquality-gate clean verify`。这次打出的 `weaver-girl-api-1.0.0-SNAPSHOT.jar` 的 manifest 是 `Build-Jdk-Spec: 17`。日志：`/tmp/grok-goal-02b1e7ba0726/implementer/release-check.log`。`BUILD FAILURE`，`Finished at: 2026-10-29T23:55:38+08:00`，`EXIT:1`。

脚本是 `set -euo pipefail`，第一个失败就停。后面的模块全部 `SKIPPED`，所以这次没有检查 core 及之后的 JaCoCo、SpotBugs、Checkstyle，也没有检查到各模块的 `jar` / `sources.jar` / `javadoc.jar`，更没有检查 agent manifest 的 `Premain-Class` 和 `Agent-Class`。

在它停下之前，`Weaver Girl`（父 POM）和 `Weaver Girl - Annotation` 是 `SUCCESS`。`weaver-girl-api` 的单测是 `Tests run: 734, Failures: 0, Errors: 0, Skipped: 0`。JaCoCo 打印了 `All coverage checks have been met.` SpotBugs 打印了 `BugInstance size is 0` 和 `No errors/warnings found`。然后 Checkstyle 失败：

```
[ERROR] .../weaver-girl-api/src/main/java/com/github/cc11001100/weavergirl/api/event/LifecycleEvents.java:3:1: 额外导入当前包下内容：com.github.cc11001100.weavergirl.api.event.InterceptorEvent 。 [RedundantImport]
[ERROR] .../weaver-girl-api/src/main/java/com/github/cc11001100/weavergirl/api/context/ContextExecutor.java:5:8: 无用导入 - java.util.concurrent.ExecutorService 。 [UnusedImports]
[ERROR] .../weaver-girl-api/src/main/java/com/github/cc11001100/weavergirl/api/context/ContextExecutor.java:6:8: 无用导入 - java.util.concurrent.ScheduledExecutorService 。 [UnusedImports]
[ERROR] .../weaver-girl-api/src/main/java/com/github/cc11001100/weavergirl/api/context/ContextCompletableFuture.java:8:8: 无用导入 - java.util.function.Function 。 [UnusedImports]
[ERROR] .../weaver-girl-api/src/main/java/com/github/cc11001100/weavergirl/api/introduction/IntroductionRegistry.java:3:8: 无用导入 - com.github.cc11001100.weavergirl.api.matcher.ClassMatcher 。 [UnusedImports]
[ERROR] .../weaver-girl-api/src/main/java/com/github/cc11001100/weavergirl/api/security/SecurityAuditLog.java:3:8: 无用导入 - com.github.cc11001100.weavergirl.api.event.InterceptorEvent 。 [UnusedImports]
[ERROR] Failed to execute goal org.apache.maven.plugins:maven-checkstyle-plugin:3.6.0:check (checkstyle-check) on project weaver-girl-api: Failed during checkstyle execution: There are 6 errors reported by Checkstyle 9.3 with .../config/checkstyle.xml ruleset.
```

这 6 个文件不在当前未提交改动里，是 `main` 上已经提交的源码。给当前 `HEAD` 打 `v1.0.0` 也会编到它们。

`RELEASE_READINESS.md` 写 api 的 JaCoCo 大约是 51% 指令 / 40% 分支、Checkstyle 大约 3200 条。这次 api 的覆盖率检查通过了，Checkstyle 是 6 条。那份 2026-06-15 的表不能当作现在的门禁结果。后面的模块这次没跑到，不能据此说它们已经绿了。

## 基准脚本

命令是 `scripts/verify-benchmarks.sh`，没有设置 `SKIP_BENCH`。`BENCH_OUT` 指到本次审查目录里的 `bench-out.txt`，避免写脚本默认的共享 `/tmp/bench-out.txt`；文件是脚本自己跑 Maven 时写下的，不是事先填好的分数。

`benchmarks.log` 全文：

```
==> Running JMH bench (wi=2 i=3 forks=0)...
ERROR: benchmark run failed; see /tmp/grok-goal-02b1e7ba0726/implementer/bench-out.txt
EXIT:2
```

退出码 2 是脚本定义的“没能产出或解析基准输出”，不是“某个基准超过 1.5×”。日志里没有 `RESULT: within budget`，也没有任何 `BENCHMARK` 比值表。

`bench-out.txt` 里 Maven 停在 `weaver-girl-core` 的 `testCompile`（`-DskipTests` 仍会编译测试）。摘录：

```
[ERROR] .../weaver-girl-core/src/test/java/com/github/cc11001100/weavergirl/core/plugin/ObservabilityPluginExtensionTest.java:[131,20] 找不到符号
  符号:   方法 registerEventListener(com.github.cc11001100.weavergirl.api.event.InterceptorEventListener)
  位置: 类型为com.github.cc11001100.weavergirl.api.plugin.PluginContext的变量 context
[ERROR] .../ObservabilityPluginExtensionTest.java:[132,20] 找不到符号
  符号:   方法 registerEventListener(com.github.cc11001100.weavergirl.api.event.InterceptorEventListener)
[ERROR] .../ObservabilityPluginExtensionTest.java:[133,20] 找不到符号
  符号:   方法 registerDataExporter(<匿名com.github.cc11001100.weavergirl.api.exporter.DataExporter>)
[INFO] BUILD FAILURE
[INFO] Finished at: 2026-10-29T23:56:23+08:00
[ERROR] Failed to execute goal org.apache.maven.plugins:maven-compiler-plugin:3.11.0:testCompile (default-testCompile) on project weaver-girl-core: Compilation failure
```

未跟踪文件 `ObservabilityPluginExtensionTest.java` 第 131–133 行调用这两个方法。工作区源码里的 `PluginContext` 有这两个 default 方法（这次 `release-check` 编出的 class 上也有）。`-pl weaver-girl-core` 没有 `-am`，测试编译用的是本地仓库里 2026-10-26 的 `weaver-girl-api-1.0.0-SNAPSHOT.jar`，那个 jar 的 `PluginContext` 没有这两个方法。所以基准比较根本没有开始。

## 对外发布还差什么，以及什么不能当成已经差完了

| 事实 | 这次看到的 |
| --- | --- |
| 父版本 | `1.0.0-SNAPSHOT` |
| `git tag -l 'v*'` | 空，退出码 0 |
| `gh` | 已登录 `github.com` 账号 `CC11001100`，scope 含 `repo` |
| `gh release list` | 空输出 |
| 本机 `CENTRAL_USERNAME`、`CENTRAL_TOKEN`、`GPG_PRIVATE_KEY`、`GPG_PASSPHRASE` | 都未设置 |

`existing-releases.txt` 原文：

```
=== git tag -l v* ===
=== tag exit 0 ===
=== gh auth ===
github.com
  ✓ Logged in to github.com account CC11001100
  - Token scopes: 'gist', 'read:org', 'repo'
=== gh release list ===
=== local secrets presence only ===
CENTRAL_USERNAME=unset
CENTRAL_TOKEN=unset
GPG_PRIVATE_KEY=unset
GPG_PASSPHRASE=unset
```

`git tag -l 'v*'` 空、退出码 0。`gh release list` 空输出。本机这四个环境变量都是 `unset`。这只说明本机 shell 里没有它们，不能证明 GitHub Actions 的同名 secret 也没配。secret 未知不是代码缺陷，也不是可以推 tag 的理由。Central 门户上的 Publish 按钮这次同样没有操作。

`RELEASE_PROCEDURE.md` 写的是：

```
Runs `clean deploy -Prelease`
```

以及：

```
`quality-gate` is disabled during `deploy` (it runs the full test suite, just
without re-enforcing the coverage floor — that is already enforced on every PR).
```

`release.yml` 的 deploy 步骤实际是（注释写着 release 必须过和 PR 一样的质量门禁）：

```yaml
env:
  CENTRAL_USERNAME: ${{ secrets.CENTRAL_USERNAME }}
  CENTRAL_TOKEN: ${{ secrets.CENTRAL_TOKEN }}
  GPG_PASSPHRASE: ${{ secrets.GPG_PASSPHRASE }}
run: ./mvnw -B -Pquality-gate,release clean deploy
```

以 `release.yml` 和 `release-check.sh` 为准。过程文档把质量门禁说成在 deploy 时关掉，和 workflow 不一致。结论跟 workflow 走，不跟 `RELEASE_PROCEDURE.md` 走。

工作区相对 `main...origin/main` 还有一批未提交修改和未跟踪文件。即便 Checkstyle 修掉了，打 tag 也只会发布被提交的那个 commit，不会带上这些未提交改动。

## 证据

- `/tmp/grok-goal-02b1e7ba0726/implementer/release-bar.md`
- `/tmp/grok-goal-02b1e7ba0726/implementer/release-check.log`
- `/tmp/grok-goal-02b1e7ba0726/implementer/benchmarks.log`
- `/tmp/grok-goal-02b1e7ba0726/implementer/bench-out.txt`
- `/tmp/grok-goal-02b1e7ba0726/implementer/existing-releases.txt`

同一批文件抄在 `goal-evidence/first-release-review-2026-10-29/`（该目录被 `.gitignore` 忽略，只留在这台机器上）。
