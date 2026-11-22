# 对外发布就绪度评估（2026-11-19）

> 本文件记录 2026-11-19 在本机实测 + 四路只读审计的结论。每条结论都给了可复跑的命令或
> `file:line` 证据。此前 `RELEASE_READINESS.md`（2026-11-11）说的"代码与打包已就绪"仍然
> 成立——它证明的是**构建、测试、打包链路**没问题。这份评估回答的是另一个问题：
> **作为一个要交给第三方做 APM/IAST/RASP 产品的切面底座，能力面是否够。**

## 结论

**评估当天的状态：不能按 1.0.0 GA 对外发布**（当天的 P0 与大部分 P1 已当场修掉，见下表
与 §8；当天之后剩下的阻断项是性能尾项和 API 契约治理）。引擎能跑、126 个插件齐、153 模块 **7,390** 个测试
全绿（2026-11-19 最终门禁，8 分 15 秒）、dist 包 140 个能力 jar 可 `-javaagent` 挂载——这些是
真实达标的。评估当时底座的三条硬
承诺（**低开销**、**可编程**、**可运营**）各有一条断在关键处：

| 承诺 | 现状 | 硬证据 |
|---|---|---|
| 低开销 | 每次被拦截调用固定多约 **0.67 µs**（评估时发现并已从 2.1 µs 修到此处），仍高于 OTel 同量级 | 本机实测，见 §1 |
| 可编程 | 运行时新切点与 `Instrumentation` 逃生舱已补；构造/字段/catch 的限制已写进 Javadoc 与指南 | §2 探针 fired=0→1；§3.1 `getInstrumentation()`；§3.3 文档化 |
| 可运营 | 端点默认改回 loopback 并有界执行器/token；发布链与面板/镜像已修 | §4 全部条目附修复位置 |

因此建议：**1.0.0 GA 之前先清掉 §1 剩余的性能项与 §5 的契约治理；或者首发按
`1.0.0-rc.1` 并把尚未清掉的项写成明确限制。**

### 本次评估同时落地的修复（均已带回归测试/探针验证）

| 编号 | 修复 |
|---|---|
| §1 | caller 捕获改为按定义显式开启（默认关）；MDC 可用性探针结果缓存。拦截开销 2.1 µs → 0.67 µs |
| §2 | 注册表代数变化时重建 matcher chain，再 retransform：运行时新增切点真正生效（fired=0 → 1） |
| §3.1 | `PluginContext.getInstrumentation()` / `registerTransformer()`：受控的 `ClassFileTransformer` 逃生舱，生命周期随插件 |
| §3.2 | `Tracer.endSpan` 恢复父 span，嵌套 span 的父子关系不再断裂 |
| §3.4 | `minimumAgentVersion` 单独声明时也会被检查 |
| §4.1 | 四个端点默认绑 `127.0.0.1`、有界执行器、可选 Bearer token（Helm 的 `config.endpointAuthToken` 也已接线） |
| §4.2 | Central 自动 publish；SNAPSHOT/版本守卫；两个资产都签名；rc 标为 prerelease；sample/tests 不 deploy；Grafana 指标名；Dockerfile 默认开端点 + HEALTHCHECK + 非 root；Helm 真正渲染 `config.*` |
| §5 | 46 处 `@since 2.x` 回改为 `1.0.0`；13 个公开类型补 `@since`；`api.observability` 补进 JPMS 导出 |
| §6 | README 代码片段、`run-sample.sh`、插件名、`method: "*"`、死配置键文档、runbook 挂载命令 |
| §3.3 | 构造/字段/catch 的 JVM 限制写进 `Interceptor` Javadoc、`AdviceMode` 枚举与插件指南（不改实现） |
| §4.3 | 卸载路径关闭 `PluginClassLoader` 并清空四处强引用，WeakReference 测试证明 loader 可回收 |
| §4.3 | 自适应采样监控、熔断阈值/冷却、状态持久化三条死链路真正接线（8 个回归测试） |

未动的项（性能尾项、`japicmp`/API 分级/SBOM、`PerInstanceStore` 弱引用、`introduction` 织入、
JPMS 拆分包）见 §8。

---

## §1 性能：拦截热路径固定开销曾约 2 µs/次（P0 已修一半，仍需继续压）

### 实测方法

`weaver-girl-testkit` 的 `PluginTestHarness` 对一个空 `before` 拦截器做端到端计时，
JDK 21、先预热 500 万次、再跑 4000 万次：

```
修复前（2026-11-19 上午）
  未织入:                   0.8 ns/op
  织入后（1 个空 before）: 1,951–2,141 ns/op

修复后（2026-11-19 下午，同一探针、同一机器）
  未织入:                   0.5 ns/op
  织入后（1 个空 before）: 663–691 ns/op（三次运行稳定）
```

分摊（同一 JVM，单独测各组件）：

```
new Throwable().getStackTrace():      561 ns
InterceptorHolder.captureCaller():   1032 ns   ← 修复前每次调用无条件执行
MdcInjector.isMdcAvailable():         156 ns   ← 修复前每次调用都探测一次 MDC
Class.forName(可解析类名, TCCL):        150 ns
MethodInvocationPool.acquire/release:   47 ns
AgentStatus.recordInterceptorInvocation: 8 ns
cflow push/pop (ThreadLocal+拼接):      10 ns
```

### 根因（两处，均已在本次评估中修复）

1. `InterceptAdvice.java:81` 与 `ConstructorAdvice.java:69` **无条件**调用
   `InterceptorHolder.captureCaller()`，后者 `InterceptorHolder.java:301` 每个被拦截调用
   都执行 `new Throwable().getStackTrace()` 并按帧名过滤，最后还做一次
   `Class.forName(callerClassName, false, TCCL)`。而 **没有任何内置插件、注解运行时或扩展点
   消费 caller 信息**（全仓 grep `getCallerClass()/getCallerLineNumber()/hasCaller` 的调用方
   只有测试和一个委托透传）。
2. `MdcInjector.isMdcAvailable()` 每次调用都 `Class.forName("org.slf4j.MDC")` 并写入/读出/
   删除一个探针键（约 156 ns），只为回答一个启动后不会再变的问题。

### 已做的修复（本次提交）

- `InterceptorDefinition` 新增 `isCallSiteTracking()` / `withCallSiteTracking(boolean)`，
   默认 **false**；`AbstractPlugin` 的 builder 新增 `trackCallSite(boolean)`；
   `InterceptAdvice` / `ConstructorAdvice` 只在 `InterceptorHolder.tracksCallSite(className)`
   为真时才捕获调用方。需要 caller 的产品显式开启。
- `MdcInjector` 把可用性探针结果缓存进一个 volatile 字段（`resetMdcProbeForTest()` 供测试用）。
- `CallSiteTrackingIntegrationTest` 的四个用例改为显式 `.withCallSiteTracking(true)`，
  它们现在同时是"开启后仍能拿到 caller"的回归测试。

### 仍然存在、需要继续压的开销（P1）

约 0.67 µs/次里余下的部分主要是：`@Advice` 入口/出口各自 `getInterceptorsForClassSnapshot`
（每次约 20–23 ns，但会分配 list/iterator）、`onMethodExit` 里再 `acquire` 一次对象池、
`recordOutcome` 的熔断器 `computeIfAbsent` + `synchronized`、`System.nanoTime` ×2、
以及 `PointcutExpression.enterCflow` 的 `className + "." + methodName` 拼接 + ThreadLocal
（约 45 ns）。对 APM 场景这仍然偏高（OTel agent 的空拦截在几十 ns 量级）。

建议下一步：
- `recordOutcome` 改无锁（每定义一个 `LongAdder`/累加器，慢调用判定按窗口而非每次 `synchronized`）。
- cflow 栈改为**只有存在 cflow 切点时才入栈**（与 caller 一样按定义开启）。
- `onMethodExit` 复用 enter 阶段的 invocation，不必重新 acquire。
- 给 `InterceptAdvice` 加一条真实织入的 JMH 基准，并接入 `verify-benchmarks.sh`。

### 为什么文档没暴露

`docs/benchmarks/baseline-v1.md` 的 16 个基准测的是 matcher/registry 查找
（`baseline_noRegistry` 5.6 ns），**没有任何一条测 advice 端到端路径**；
`scripts/verify-benchmarks.sh` 因此看不到这个回归。

---

## §2 可编程性：运行时新增切点对已加载类不生效（P0，已修复）

### 实测

探针在 transformer 安装后注册一个新定义，再 `retransform(LateTarget.class)`——这正是
config-watch 热重载走的路径：

```
修复前（2026-11-19 上午）
early definition fired=1
late definition (registered after install) fired=0

修复后（2026-11-19 下午，同一探针）
early definition fired=1
late definition (registered after install) fired=1
```

回归测试 `weaver-girl-testkit/.../PluginTestHarnessLateDefinitionTest` 在修复前是红的
（`expected: <1> but was: <0>`），修复后转绿；它是这条路径的长期防线。

### 根因

`WeaverTransformer.install()` 在 `WeaverTransformer.java:335` 把
`registry.getAllDefinitions()` **取快照后**逐个 `builder.visit(...)` 注册 type/method
matcher（`:461`）。之后 `registry.register()` 只让 `InterceptorHolder` 的快照失效
（`WeaverGirl.java:118`、`InterceptorHolder.java:148`），`retransformLoadedClasses()`
只遍历**已注册 matcher** 能命中的类（`WeaverTransformer.java:550-561`）。

结果是：新注册的定义能对**之后新加载**的类生效，但**已有的类永不重新织入**。

### 影响

- `docs/plugin-developer-guide.md:529` 明文承诺"a plugin added, replaced or removed at
  runtime applies to them too"——与实现相反。
- `runtime-dynamic-refresh` 插件的对外说明同样不成立。
- 对产品场景是致命的：RASP 规则热下发、IAST 热增 sink、灰度开关切点——全部不可用。
  一个"切面底座"如果不能运行时改切点，就只是启动期一次性织入器。

### 已做的修复（本次提交）

- `WeaverTransformer` 记住安装时的注册表代数（`installedGeneration`），新增 `isStale()`
  与 `reinstallAndRetransform()`：代数变了就先摘掉旧 chain、重新 `install`，再 retransform。
- `WeaverGirl.reloadAndRetransform()` 成为所有 reload 路径的统一入口；`refresh()`、
  `reloadPluginDirectory()`、agent 通过 `InterceptorHolder.setRetransformHook` 安装的回调
  全部改走它（config-watch 与 dynamic-refresh 插件因此自动受益）。
- `PluginTestHarness.retransform()` 也走同一条路径，让"安装后注册"在单测里可复现、可回归。

---

## §3 插件可用性：三条"一用就撞墙"的能力缺口（P0/P1，两条已修）

### 3.1 API 层没有 `Instrumentation` 逃生舱（P0，已修复）

`weaver-girl-api` 内 `Instrumentation` 命中 **0** 次；`AgentInstrumentation` 只在
`core` 且文档禁止产品编 core（`docs/versioning-and-compatibility.md:37-40`）。

- IAST 想织 `java.lang.String` / `ProcessBuilder`（引导类）——只能靠 core 内置的 taint。
- RASP 想在任意 call site 阻断——只能走已注册切点。
- APM 想处理 indy/lambda、自定义 retransform——没有入口。

修法：在 `PluginContext` 加 `getInstrumentation()`（或在 api 暴露一个受控的
`TransformerRegistrar`），并写清引导类/不可修改类的边界。

### 3.2 嵌套 span 父子关系断裂（P0，已修复）

`Tracer.endSpan()` 原先直接 `CURRENT_SPAN.remove()`，**不恢复父 span**；测试注释自己承认
"clears rather than restoring a stack"（`SpanSinksTest`）。一个 APM 插件与 servlet/jdbc 插件
同时开 span 时，span 树必然错。

**已做的修复（本次提交）**：`startChildSpan()` 把父 span 压入一个每线程栈，`endSpan()`
结束后弹栈恢复父 span（栈空则清空线程状态）；`clearCurrentSpan()` 同时清栈，避免残留。
新测试 `TracerTest.endSpan_restoresTheParentSpan` 固定了这段行为，`SpanSinksTest` 的旧注释
已改。绝对根 span 结束后的语义不变（线程无 current span），所以现有 16 个 servlet 传播测试
无需改动就通过。

### 3.3 构造/字段/catch 的语义与文档不符（P1，已修——改为文档化）

- 构造函数：跳过与 `onException` 被禁用（`ConstructorAdvice.java:24-31,110-114`），
  `getMethod()` 返回 null、`getParameterTypes()` 空数组——API 未披露。
- 字段：只能看见 `getX/isX/setX` 这类 accessor，方法体内直读直写不可见；写 null
  无法触发 `onFieldSet`（`FieldAdvice.java:77-89`）——API 与 DSL 都没写这个限制。
- catch：文档说"进入 catch 块时触发"，实际是**方法异常退出时**（`CatchAdvice.java:13-18`）。

**已做的处理（本次提交）**：不补实现（这三条是 JVM 层面的限制，不是缺功能），而是把边界
写进产品真正会读的地方：

- `CatchInterceptor` 类注释新增「What "catch" means here」：JVM 没有 catch 块钩子，实际是在
  方法异常退出时分发。
- `FieldInterceptor` 类注释新增"Only accessor methods are seen"：只能看见 accessor，直读直写
  不可见，写 null 与读无法区分。
- `InterceptorDefinition.AdviceMode` 每个枚举值补上能力与限制。
- `docs/plugin-developer-guide.md` 新增 "What each join point can and cannot do" 对照表。
- **字段钩子两处契约缺陷已修**（写案例时实测发现）：`onFieldGet(inv, originalValue)` 原先恒传
  `null`，与 Javadoc「值从字段读出」矛盾——现在传入真实值（`FieldAdvice.readFieldValue`）；
  `skipWrite()` 原先把正在写入的值写回（等于没拒绝），现在写回字段当前值，写入真的被挡下。
  回归测试：`FieldAdviceOriginalValueTest`。案例：`scripts/run-field-catch-demo.sh`。

### 3.4 其他死 API（P2）

- ~~`api/introduction` 公开且标了版本，但 `IntroductionAdvice` **从未被 `Advice.to(...)`
  织入**，`PluginContext` 也没有注册入口 → mixin 能力不可用。~~ **已修**：`WeaverTransformer`
  增加独立的引介链（`implement(interface).intercept(MethodDelegation)`，不复用
  `disableClassFormatChanges()` 的拦截器链）；`IntroductionRegistrarHolder` +
  `IntroductionRegistrar` 给插件一个跨加载器注册入口；委托支持"首参接收目标实例"。
  已知边界：接口只能在目标类**首次定义**时加上，已加载的类 JVM 不允许再加接口。
  案例：`scripts/run-introduction-demo.sh`。
- ~~`minimumAgentVersion` 只在同时声明 `plugin.apiVersion` 时才检查~~ **已修**
  （`PluginLoader`）：只要 jar 声明了任一受检字段就检查；补了
  `aJarThatOnlyDeclaresAMinimumAgentVersionIsStillChecked` 回归测试。

---

## §4 可运营性：安全默认值与发布物（P0，已修）

### 4.1 管理/健康/指标端点默认 `0.0.0.0`、无认证、无界线程（P0，已修复）

原状：四处均 `HttpServer.create(new InetSocketAddress(port), 0)` + `setExecutor(null)`——
`AgentApiServer`（`/events`、`/traces`、`/audit`、`/security/policy`）、`HealthPlugin`、
`DiagnosticsPlugin`、`PrometheusExporter`。其中 `POST /agent/interception` 是**全局
kill-switch**，网络可达即可被关闭；JDK 默认 executor = 每请求一线程，可被打爆。
`SECURITY.md` 只把"绑 localhost"写成运维建议，与代码默认相反。

**已做的修复（本次提交）**

- 新增 `weaver-girl-api` 的 `api.http.EndpointBinding`（放在 api 而不是 core，因为
  prometheus 模块刻意不依赖 core）：
  `address()` 默认 `127.0.0.1`，`boundedExecutor()` 有界 + daemon 线程，
  `authorized()/challenge()` 提供 Bearer token 闸门。
- 四类端点全部改走 `EndpointBinding.create(...)`，默认回 loopback、执行器有界。
- `apiPort` 的 `AgentApiServer` 支持 `endpointAuthToken`（逐个 handler 包装，因为
  `HttpServer` 是长前缀匹配，根路径兜不住具体端点）。
- 新增 `endpointBindAddress` / `endpointMaxThreads` / `endpointAuthToken` 三个配置键。
- `EndpointBindingTest`（6 个）固定了默认绑定、有界执行器、token 语义与 401 响应。
- 镜像与 Helm 里显式选择 `0.0.0.0`（容器内 loopback 端点连 kubelet 都摸不到），并在
  chart README 说明"要么用 Service/网络策略收口，要么设 token"。
- `/ready` 的 503-无拦截器语义保留，但 chart README 写明"没有 hook 的部署改探 `/health`"。

### 4.2 发布链会在"看似成功"时交付不可用产物（P0）

| 项 | 原后果 | 本次处理 |
|---|---|---|
| Central 不自动 publish | GitHub Release 指向还没 Publish 的坐标（404） | **已修**：`release` profile 加 `autoPublish=true` + `waitUntil=VALIDATED`（`pom.xml`） |
| 无 SNAPSHOT 版本守卫 | 错 tag 一路到 Central staging | **已修**：`release.yml` 拒绝 SNAPSHOT、非 semver、tag 与版本不一致 |
| 面板指标名全错 | 两个 Grafana 面板全空 | **已修**：`deploy/grafana/weaver-girl-interceptors.json` 改用导出器真实的 `weavergirl_*` 名 |
| 镜像默认不可观测 | 跑起来没有任何端点 | **已修**：`Dockerfile` 默认开 metrics/health/diagnostics、加 `HEALTHCHECK`、非 root、`APP_JAR` 未设时明确报错 |
| Helm `--set config.metricsPort` 无效 | quick start 装完什么都不发生 | **已修**：`_helpers.tpl` 的 `weaver-girl.agentArgs` 把 `config.*` 渲染成 agent 参数字符串，写入 ConfigMap 的 `agent-args`；init-container 示例改为读取它 |
| 裸 agent jar 无签名却列为资产 | 用户按 SECURITY.md 验不了裸 jar | **已修**：签名步骤对 dist zip 与 agent jar 都签 |
| `release-check.sh` 不在任何 workflow | 本地门禁可与 CI 漂移 | **已修**：新增 `.github/workflows/release-gate.yml`，在改 pom/agent/脚本的 PR 上独占跑该脚本 |
| sample / plugins-tests 会被发到 Central | 未审的 demo/测试坐标外泄 | **已修**：两个模块加 `maven-deploy-plugin` 的 `skip` |
| 无 `project.build.outputTimestamp` | 无可复现构建验证 | **未修**（1.x 供应链项） |
| CI 兼容矩阵是假绿 | JDK 8/11 织入回归无断言 | **未修**（真断言在 `agent-smoke.yml`；矩阵本身仍只打印） |
| `prerelease: false` 固定 | rc 版本被当成正式版 | **已修**：`prerelease` 改为按版本号是否含 `-` 判断 |

### 4.3 卸载与内存生命周期（P1）

- ~~`PluginLoader.unloadOne()` **不 `close()` PluginClassLoader**；`DefaultPluginManager
  .pluginEntries` 只改状态、从不 `remove` → 每次热替换泄漏一个 loader + jar 句柄 + 插件全部
  单例。Windows 上旧 jar 被钉住无法覆盖。~~ **已修（本次提交）**：`PluginClassLoader.close()`
  清空跨插件导出映射；`unloadJar` 关闭 loader 并清 `scannedJars` 里的记录；
  `PluginOwnership.forgetPlugin` 移除以插件实例为 key 的条目；`untrack` 在 loader 列表空时
  连 key 一起删；manager 卸载时 `pluginEntries.remove`。回归测试
  `unloadingAJarClosesItsClassLoaderSoTheJvmCanCollectIt` 用 WeakReference + GC 断言 loader
  真的可回收（修复前该测试是红的）。
- `PerInstanceStore` 是 `ConcurrentHashMap<Object,Object>` 强引用，任何 `isInstanceBound()`
  切点对每个 target 永久持有 → 请求级对象 OOM 风险。**已把这条限制写进
  `PerInstanceStore` 与 `Pointcut.isInstanceBound()` 的 Javadoc**（调用方必须在对象生命周期
  结束时 `PerInstanceStore.remove(target)`）；实现仍未改成弱引用。
- ~~自适应采样/降级/持久化宣称的链路是死代码~~ **已修（本次提交）**：
  - `SamplingMonitor` 现在由 `WeaverGirl.bootstrap` 启动（`adaptiveSampling=false` 可关），
    使 `SamplingController.adaptRate` 第一次真正有调用方；间隔由
    `samplingCheckIntervalSeconds` 控制。
  - `circuitBreakerThreshold` / `circuitBreakerCooldownMs` 不再只打日志：
    `InterceptorCircuitBreaker.configure(...)` 改成可变字段，bootstrap 与动态配置都会写入。
  - `AgentStatePersister` 由 `stateFile` 开启，shutdown 时写最后一份快照；
    `CoreConfigWiringTest`（8 个用例）断言了这三条真的生效（含"快照文件真的落盘"）。
  - `enterEmergencyMode` 补上了 `exitEmergencyMode`：`POST /agent/interception {"enabled":true}`
    现在会一并退出应急模式并恢复采样，不必重启进程。
  - 仍无生产调用方：`AdaptiveRateLimiter`、`LockFreeObjectPool`（两者都已被现有代码路径
    取代，保留是因为有测试与文档引用）。
- 无 OOM 保护（全仓 `OutOfMemory` 只在测试与 todo）。
- 插件自建线程无登记/回收，卸载后可能阻止 JVM 退出。

---

## §5 API 契约治理：底座的"承诺"没有机器在执行（P1，部分已修）

| 缺口 | 证据 |
|---|---|
| 无 `japicmp`/`revapi`/`clirr`，无 API 基线 | 全仓 0 命中；`todo.md` P115 未做 |
| 无 `@stable/@experimental/@internal` 分级 | 全仓 0 命中 |
| ~~13 个公开类型无 `@since`，46 处 `@since 2.x` 与 `API_VERSION="1.0.0"` 冲突~~ | **已修（本次提交）**：api/annotation/annotation-runtime 的 `@since 2.x` 全部回改为 `1.0.0`，13 个缺标签的公开类型补上 |
| Javadoc 门禁形同虚设 | `doclint=none`（`pom.xml:1113-1116`）、checkstyle 无 Javadoc 规则 |
| ~~JPMS 描述符漏 `exports api.observability`~~ | **已修**：`weaver-girl-api/src/main/java9/module-info.java` 补上导出；`annotation-runtime` 与 `core` 仍导出同一 `core.plugin` 包（拆分包），未处理 |

产品方的实际后果：1.x 内部重构随时可能给插件一个 `NoSuchMethodError`，而文档承诺 SemVer。

**本次处理后仍缺的**：`japicmp`/`revapi` 基线、`@stable/@experimental/@internal` 分级、
`doclint` 仍关闭、`annotation-runtime` 与 `core` 的拆分包（都导出 `core.plugin`）。这些是
第二步（P1）的工作，不影响 1.0.0 首发，但决定"承诺是否可信"。

---

## §6 文档与示例的"复制即失败"（P0，已修）

| 问题 | 证据 | 实测 |
|---|---|---|
| README quick start 一键脚本跑不起来 | `run-sample.sh` 用 `find … | head -1` 不排除 `-javadoc.jar` | 修复前 `./run-sample.sh` → `Failed to find Premain-Class manifest attribute in …-javadoc.jar`；**已修复**：现在只挑主 jar、补 `dependency:copy-dependencies`、支持 `PORT=` |
| README 两段代码片段编译不过 | `.register(registry)`（builder 只有 `build()`）；注解写 `className=`/`methodName=`，实际是 `target()`/`value()` | **已修复** |
| `method: "*"` 是静默空匹配 | `YamlConfigLoader.java:271-273` → `MethodMatcher.byName("*")`（EXACT_NAME） | **已修复**：参考文档、`weaver-example.yml` 改为 `methodPattern: ".*"` 或省略，并在参考里点明 |
| 4 个 agent 级 YAML 键是死配置 | `samplingThreshold/circuitBreakerFailures/circuitBreakerCooldown/logLevel` 只被 `YamlConfigLoader.java:182-195` 填默认值，无生产消费者；真正生效的是另一套名字 `samplingRate/circuitBreakerThreshold`（`WeaverGirl.java:903,932`），且熔断器是静态单例、阈值只打日志（`InterceptorHolder.java:28`） | **文档已改**：`yaml-config-reference.md` 明确这些 YAML 拼写无效并给出真正生效的 agent 参数表；**代码未改**（接线或删除仍是 TODO） |
| 内置插件名不一致 | README 写 `timing`/`exception`，实际 `method-timing`/`exception-monitor` | **已修复**（README + `troubleshooting.md`） |
| 版本号硬编码 SNAPSHOT | README、`plugin-developer-guide.md`、`CONTRIBUTING.md`、Dockerfile、`docker-compose.yml` | **未修**：发布时仍需在 `release.yml` 里同步改写，或改成占位符 |
| `ConfigValidator` 不在启动路径 | 全仓 main 无引用；`versioning-and-compatibility.md:95` 承诺未知键会报 | **未修** |
| ~~docs/examples 未纳入编译~~ | 三个游离 `.java`，无 pom 引用 | **已修**：`weaver-girl-sample` 用 `build-helper` 把 `docs/examples` 加为 test source，`mvn test` 会编译它们；`HookModeExamplesTest` 再钉住三份示例与三种接入方式的实现 |
| runbook 的动态挂载命令会失败 | `java -jar weaver-girl-agent.jar <pid>`，但 jar 没有 `Main-Class` | **已修复**：改为 `VirtualMachine.attach` / `ByteBuddyAgent.attach`，并写明重复 attach 与无 detach 的限制 |

---

## §7 已经真实达标的（不要返工）

这些是实测确认可用的，修上面那批问题时不要动它们：

1. **构建与质量门禁**：JDK 17 下 `-Pquality-gate clean verify` 153 模块、**7,390** 个测试、
   0 失败 0 跳过，8 分 15 秒（2026-11-19 最后一次实测，含本次全部修复）。
2. **打包与分发**：`weaver-girl-agent-*-dist.zip` 含 146 个条目 / 140 个能力 jar；
   `-javaagent` 真挂载路径在 `agent-smoke.yml` 的 JDK 8/11/17/21 上有断言。
3. **插件隔离模型**：child-first + `api`/advice-bridge 父优先不变量（`AgentClassLoader.java:79-140`），
   `lib/<plugin>/` 私有依赖隔离，`requires/exports`、`apiVersion`、jar 内容摘要校验、
   卸载时撤回服务与扩展点都有实现与测试。
4. **故障隔离**：单插件 init/register/destroy 抛异常被逐个捕获；拦截器回调异常逐拦截器
   try/catch；熔断器阈值/冷却/慢调用三路触发；premain 失败 fail-open
   （`WeaverGirlAgent.java:216-225`）。
5. **插件版图**：126 个插桩插件 + 14 个运行时能力 jar，覆盖面已对齐 Elastic APM / OTel
   的缺口清单（Dubbo/MyBatis/RocketMQ/Druid/Feign 等国内刚需在内的）。
6. **product testkit**：`PluginTestHarness` 可在普通单测里驱动真实织入，不启 agent，
   `close()` 会还原全局状态——这是底座对产品方最有价值的一件工具，P0 修完请保留并补示例。
7. **定位与边界文档**：`docs/positioning.md`、`docs/ant-aspect-base-decision.md` 把
   "底座 / 应用 / 管控"的边界写得清楚，是这次评估里少数不需要改的文档。

---

## §8 建议的发布动作顺序

**第一步（P0，1.0.0 GA 之前必须清）**

1. ~~caller 信息改显式开关~~ **已完成**；剩余：给 `InterceptAdvice` 加真实织入的 JMH 基准并接入
   `verify-benchmarks.sh`，继续把 0.67 µs 压到 OTel 量级（见 §1 末尾）。
2. `retransformLoadedClasses()` 前重建 matcher；补"install 后 register + retransform 命中"回归测试。
3. `PluginContext` 暴露受控 `Instrumentation`/transformer 注册入口；Javadoc 写清引导类与不可修改类边界。
4. ~~`Tracer.endSpan` 恢复父 span；补嵌套 span 父子关系测试~~ **已完成**。
5. ~~四个 HTTP 端点默认 `127.0.0.1` + 有界执行器；kill-switch 端点加 token~~ **已完成**；
   剩余：把 `endpointAuthToken` 写进 SECURITY.md 的加固清单。
6. ~~发布链：`autoPublish`、SNAPSHOT 守卫、两个资产都签名、sample/tests `deploy.skip`、
   `release-check.sh` 进 CI~~ **已完成**。
7. ~~修 README 两段代码 + `run-sample.sh`；`method: "*"`；插件名~~ **已完成**；
   剩余：agent 级死配置键接线、发布时改写文档里的 SNAPSHOT 版本号。

**第二步（P1，首个星期的补丁）**

8. ~~卸载补 `close()` + 从 `pluginEntries`/loader 键清理~~ **已完成**；
   剩余：`PerInstanceStore` 改弱引用、接上自适应采样/降级或从 README 能力清单删掉。
9. ~~面板指标名；Dockerfile 端点/HEALTHCHECK/非 root；Helm 渲染 `config.*`~~ **已完成**；
   剩余：给 chart 加 `helm lint/template` CI 步骤。
10. `japicmp` 基线 + `@stable/@experimental` 分级（`@since` 与
    `minimumAgentVersion` 已修）。
11. ~~构造/字段/catch 的能力边界写进 Javadoc 与插件指南~~ **已完成**；
    剩余：`introduction` 要么织入要么标 `@experimental`。

**第三步（P2，1.x）**

12. SBOM（CycloneDX）+ provenance + `outputTimestamp` + wrapper 校验和；
    把 CI 兼容矩阵从"打印"改成"断言"；`ConfigValidator` 进启动路径；
    `docs/` 加索引并把过程文档移到 `docs/internal/`；修正 LICENSE 版权方。

---

## 复跑命令

```bash
# 全量门禁（本机实测 8.5 分钟）
JAVA_HOME=/usr/lib/jvm/java-17-openjdk-amd64 ./mvnw -B -Pquality-gate clean verify

# README 的一键示例（已修复；端口被占时用 PORT=18099 ./run-sample.sh）
./run-sample.sh

# 拦截热路径（临时探针，本文件 §1 的数字来源；源码未入库）
#   修复前：未织入 0.8 ns/op，织入 1 个空 before 1,951–2,141 ns/op
#   修复后：未织入 0.5 ns/op，织入后 663–691 ns/op

# 运行时新增切点：回归测试已入库
JAVA_HOME=/usr/lib/jvm/java-17-openjdk-amd64 ./mvnw -B -pl weaver-girl-testkit \
  -Dtest=PluginTestHarnessLateDefinitionTest -Djacoco.skip=true test
```

> 数据环境：AMD64 Linux，JDK 21.0.12 / JDK 17 构建，`weaver-girl-testkit` 自 attach。
> 绝对数字会随机器与 GC 变化，但修复前后 **2.1 µs → 0.67 µs** 的差距远超噪声；
> 仍需继续压到 OTel 量级（见 §1 末尾）。
