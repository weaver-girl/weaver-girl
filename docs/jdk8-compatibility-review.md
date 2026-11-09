# JDK 1.8 兼容性审查

审查日期：2026-10-29。范围是 JDK 8 **运行时**会加载的表面：反应器打出来的主 jar、shade 后的 `weaver-girl-agent` jar，以及 `weaver-girl-sample` 作为 attach 证明所解析的 Jetty / H2 运行库。结论依据是本机扫描和一次 JDK 8 进程，不是 README、`CONTRIBUTING.md` 或 workflow 注释。

## 结论

**兼容。** 上述运行时表面上，没有 JDK 8 JVM 加载不了的根 class，`src/main/java` 里也没有 Java 9+ 语法或 API。

不兼容清单为空。jar 根 class 主版本全部 ≤ 52（Java 8 是 52）。本机用已安装的 Zulu `1.8.0_492` 把打好的 agent 挂到 `scripts/bare-target/MinimalApp` 上，`/health`、`/ready`、`/stats`、`/metrics` 均通过。

用 JDK 8 的 `javac` 编不过整个反应器，是构建工具链的事实：父 pom 的 `maven-compiler-plugin` 写的是 `<release>8</release>`，JDK 8 的 javac 不接受 `--release`。这不表示打出来的 jar 在 JDK 8 JVM 上跑不了。

## 证据

| 文件 | 内容 |
| --- | --- |
| `/tmp/grok-goal-d54e2e17c3d1/implementer/config-notes.md` | 父 pom 的 `<release>`、两个 workflow 的 JDK 矩阵、本机 JDK 列表 |
| `/tmp/grok-goal-d54e2e17c3d1/implementer/source-scan.txt` | `src/main/java` 的原始命中，446 个文件，37 行，全部是误报 |
| `/tmp/grok-goal-d54e2e17c3d1/implementer/package.log` | `./mvnw -B package -DskipTests -Djacoco.skip=true -Dcheckstyle.skip=true -Dspotbugs.skip=true`，`BUILD SUCCESS`，`Finished at: 2026-10-29T23:34:14+08:00`，`EXIT:0` |
| `/tmp/grok-goal-d54e2e17c3d1/implementer/bytecode-majors.txt` | 每个 jar 根 `.class` 一行：`jar`、entry、major。15044 行，major > 52 的行数是 0 |
| `/tmp/grok-goal-d54e2e17c3d1/implementer/bytecode-versioned.txt` | `META-INF/versions/` 下的 class，169 行 |
| `/tmp/grok-goal-d54e2e17c3d1/implementer/bytecode-summary.txt` | 每个 jar 的根 major 直方图、versioned 计数、`Multi-Release` |
| `/tmp/grok-goal-d54e2e17c3d1/implementer/jdk8-attach.log` | Zulu 8 上的 bare-attach |
| `/tmp/grok-goal-d54e2e17c3d1/implementer/sample-dependency-list.log` | `weaver-girl-sample` 的 `dependency:list` |

同一份摘要也抄在 `goal-evidence/jdk8-runtime-review-2026-10-29/`，避免审查会话目录清掉以后只剩这篇文档。

`package.log` 里 `javac [debug release 8]` 出现 295 次（含 `target/test-classes`），`javac [debug release 9 module-path]` 出现 131 次。打包用的是 Maven 3.9.16 / Java 21.0.12（Amazon Corretto）。agent jar 的 `META-INF/MANIFEST.MF` 含 `Build-Jdk-Spec: 21` 和 `Multi-Release: true`。

## 桶 (a)：JDK 8 进程会加载的代码和依赖

这是“能不能在 JDK 1.8 上跑”的那一桶。

### 主源码

扫了 446 个 `src/main/java/**/*.java`，路径里含 `/java9/` 的不算。计划里的模式在 `source-scan.txt` 中的原始结果：

| 模式 | 原始命中 |
| --- | --- |
| `\bvar\b`、`"""`、switch 箭头（`case … ->` / `default ->`）、pattern `instanceof` | 0 |
| `List.of(`、`Set.of(`、`Map.of(`、`Map.copyOf(`、`Files.readString`、`java.net.http` | 0 |
| `String.isBlank`、`.strip()`、`.repeat(`、`.isBlank()`、`Optional.isEmpty`、`Collectors.toUnmodifiable` | 0 |
| `record ` | 27，全部误报 |
| `sealed ` | 1，误报 |
| `permits ` | 6，全部误报 |
| `yield ` | 1，误报 |
| `ProcessHandle` | 1，误报 |
| `StackWalker` | 1，误报 |

`record ` 的 27 行都是英文 “record”、Javadoc，或 Java 8 合法标识符，不是 `record` 类型声明：

- 注释或 Javadoc：`AnnotationPluginLoader.java:2423`、`AnnotationPluginLoader.java:2722`、`Counted.java:39`、`Metric.java:36`、`Trace.java:42`、`ReturnVersion.java:10`、`SpanRecord.java:20`、`SpanSink.java:15`、`AuditRecord.java:8`、`SecurityAuditLog.java:25`、`SecurityAuditLog.java:132`、`InterceptorCircuitBreaker.java:158`、`DefaultPluginManager.java:52`、`DefaultPluginManager.java:381`、`DefaultPluginManager.java:470`、`PluginLoader.java:215`、`PluginLoader.java:480`、`KafkaPlugin.java:75`、`KafkaPlugin.java:254`、`KafkaPlugin.java:288`、`MyBatisPlugin.java:25`、`OtlpEventListener.java:99`
- 标识符：`SpanSinks.java:127`（`record = SpanRecord.from(...)`）、`SecurityAuditLog.java:28`（`if (record != null)`）、`AgentDiagnostics.java:149`（`FaultRecord record = new FaultRecord(...)`）、`KafkaPlugin.java:269`（`Object record = nextMethod.invoke(iterator)`）、`KafkaPlugin.java:270`（`if (record != null)`）

其余五条：

- `sealed `：`RuntimeCompatibility.java:13` 是 Javadoc 里的 “sealed classes”，不是 `sealed` 类声明。
- `permits `：`RateLimiter.java:33`、`AdaptiveRateLimiter.java:57`、`AdaptiveRateLimiter.java:65`、`AdaptiveRateLimiter.java:120` 是注释或 Javadoc；`AdaptiveRateLimiter.java:93` 与 `:122` 是标识符 `if (permits <= 0)`。
- `yield `：`PluginJarSelector.java:177` 是注释 “yield "jdbc"”。
- `ProcessHandle`：`TraceCorrelationPlugin.java:142` 是注释 “Java 9+ has ProcessHandle, but we target Java 8”。
- `StackWalker`：`ClassContext.java:9` 是 Javadoc，说明这条路径不能用 `StackWalker`。

真正调用 `StackWalker` 的是 `src/main/java9` 里的 `CallStack.java`，见桶 (b)。JDK 8 路径是 `ClassContext`（继承 `SecurityManager`，调用 `getClassContext()`）。`DefaultInterceptorRegistry.invokeClasses` 对字符串 `"com.github.cc11001100.weavergirl.core.registry.CallStack"` 做 `Class.forName`，并吞掉 `UnsupportedClassVersionError` 后再退回 `ClassContext`。根 class 的常量池因此不引用 `CallStack`。

### 打包字节码

`bytecode-majors.txt` 覆盖 153 个反应器 jar（排除 `*-sources.jar`、`*-javadoc.jar`、shade 留下的 `original-*.jar`、`module-path-placeholder.jar`）和 sample 运行库 9 个 jar。`weaver-girl-agent/target/plugins/` 里的 jar 是这些模块 jar 的拷贝，没有再扫第二遍。

根 class 共 15044 个，major 直方图：

| major | 含义 | 个数 |
| --- | --- | --- |
| 47 | Java 1.3 | 9 |
| 49 | Java 5 | 2871 |
| 50 | Java 6 | 31 |
| 51 | Java 7 | 79 |
| 52 | Java 8 | 12054 |

major > 52 的根 class：0。jar 根上也没有 `module-info.class`。

低于 52 的根 class 都能被 JDK 8 加载。它们出现在这几处：

- agent jar 里的 `org/aopalliance/aop/*.class` 共 9 个，major 47（`aopalliance` 1.0，随 `weaver-girl-bridge-aopalliance` shade 进去）。
- agent jar 里重定位后的 Byte Buddy：`shaded/net/bytebuddy/` 有 2871 个 major 49、31 个 major 50。坐标 `net.bytebuddy:byte-buddy:1.14.18`。
- `javax.servlet:javax.servlet-api:3.1.0` 共 79 个 class，全部 major 51。

shade 后的 `weaver-girl-agent-1.0.0-SNAPSHOT.jar` 根 class 4389 个：`{47: 9, 49: 2871, 50: 31, 52: 1478}`。根上的包名是 `shaded/net/bytebuddy/`（2880 个 class）、`shaded/org/yaml/snakeyaml/`（230 个，`org.yaml:snakeyaml:2.7`）、`com/github/cc11001100/weavergirl/shade/org/slf4j/`（61 个，`slf4j-api` 与 `slf4j-simple` `2.0.19`）、`io/micrometer/`（624 个，`micrometer-core` `1.14.5` 及其传递依赖的根 class）。根上没有未重定位的 `net/bytebuddy/`、`org/yaml/snakeyaml/`、`org/slf4j/`。jar 里没有 `org/junit/`、`org/mockito/`、`ch/qos/logback/`、`org/openjdk/jmh/`。

我们自己的模块（api、core、annotation、annotation-runtime、两个 bridge、插件、runtime plugin、sample、testkit，以及 sample 的 `audit-plugin` / `redaction-plugin` / `resilience-plugin` classifier）根 class 全部是 major 52。

### Sample 运行库

`dependency:list -DincludeScope=compile` 解出来的 Jetty、Servlet API、H2：

| 坐标 | 根 class | 根 major |
| --- | --- | --- |
| `org.eclipse.jetty:jetty-server:9.4.53.v20231009` | 357 | 全是 52 |
| `jetty-http`、`jetty-io`、`jetty-util`、`jetty-util-ajax`、`jetty-servlet`、`jetty-security`（同一版本，server/servlet 的传递依赖） | 119 + 111 + 331 + 33 + 70 + 62 | 全是 52 |
| `javax.servlet:javax.servlet-api:3.1.0` | 79 | 全是 51 |
| `com.h2database:h2:2.2.224` | 1049 | 全是 52 |

H2 的 `org/h2/util/Bits.class`、`Utils10.class`、`Utils21.class` 在根上还有一份 major 52 的副本。更高 major 的实现只在 `META-INF/versions/`，见桶 (b)。JDK 8 加载的是根上那一份。

这次没有在 JDK 8 上启动 `SampleApplication`。`agent-smoke.yml` 的 `sample-attach` 本身也只声明 JDK 17。上面这张表只说明这些 jar 的根 class 能被 JDK 8 加载。

### 本机 JDK 8 attach

`/usr/lib/jvm` 只有 11.0.32.1、17.0.20.1、21.0.12.1。SDKMAN 里已有的二进制是 `/home/cc11001100/.sdkman/candidates/java/8.0.492-zulu/bin/java`：

```
openjdk version "1.8.0_492"
OpenJDK Runtime Environment (Zulu 8.94.0.17-CA-linux64) (build 1.8.0_492-b09)
```

用这个 `javac 1.8.0_492` 执行 `javac -source 8 -target 8 scripts/bare-target/MinimalApp.java`，`javac_exit=0`，生成的 `MinimalApp.class` major 是 52。然后：

```
java -javaagent:weaver-girl-agent/target/weaver-girl-agent-1.0.0-SNAPSHOT.jar=metricsPort=9500,healthPort=9501,diagnosticsPort=9502 \
     -cp scripts/bare-target MinimalApp
```

`jdk8-attach.log` 第 193 行：`MinimalApp started on JDK 1.8.0_492`。约 2 秒后 `/ready` 就绪。断言结果：

- `HEALTH_UP=yes`（`/health` 含 `"status":"UP"`）
- `READY=yes`
- `STATS_COUNTER=yes`（`/stats` 含 `interceptorInvocationCount`）
- `BB_LOADER=yes`（`"byteBuddyClassLoader":"AgentClassLoader"`）
- `METRICS=yes`（`/metrics` 含 `weavergirl`）

日志里没有 `UnsupportedClassVersionError`。进程在探针之后被杀掉，shutdown 钩子打完了 “WeaverGirl agent shutdown complete”。这只证明 bare-attach 这条路径；它不覆盖 H2 SQL 或 Micrometer registry 的调用。

## 桶 (b)：多版本 JAR 里 JDK 8 必须忽略的 class

这些 class 的 major 可以大于 52，但只出现在 `META-INF/versions/9` 或更高版本目录下，根上没有同 major 的副本。JDK 8 不读这个目录。它们不是 “应用不能在 JDK 1.8 上跑”。

`bytecode-versioned.txt` 共 169 个 class：major 53 有 153 个，major 52 有 14 个，major 54 有 1 个，major 65 有 1 个。

我们自己的 Java 9 源码：

- 132 个 `src/main/java9/module-info.java`。打进各模块 jar 的位置是 `META-INF/versions/9/module-info.class`（major 53）。agent shade jar 里只剩一份 `META-INF/versions/9/module-info.class`。
- `weaver-girl-core/src/main/java9/com/github/cc11001100/weavergirl/core/registry/CallStack.java` 是唯一一份非 `module-info` 的 `java9` 源码。它调用 `StackWalker.getInstance(StackWalker.Option.RETAIN_CLASS_REFERENCE)`，由 `java9-module-info` execution 以 `<release>9</release>` 编译。class 在 core jar 和 agent jar 里都只在 `META-INF/versions/9/com/github/cc11001100/weavergirl/core/registry/CallStack.class`（major 53）。

打进 agent jar、仍留在版本目录里的依赖 class：

- `META-INF/versions/9/org/yaml/snakeyaml/internal/Logger.class` 与 `Logger$Level.class`（major 53）。shade 没有把这两份的路径重定位成 `shaded.org.yaml.snakeyaml`。JDK 8 不会加载它们；根上 SnakeYAML 的 230 个 class 已经在 `shaded/org/yaml/snakeyaml/` 且 major ≤ 52。
- `META-INF/versions/11/io/micrometer/core/instrument/binder/jdk/MicrometerHttpClient*.class` 等 14 个 class（major 52，但是 Java 11 的 `java.net.http` 绑定）。它们不在 jar 根上。

H2 2.2.224：

- `META-INF/versions/9/org/h2/util/Bits.class`，major 53
- `META-INF/versions/10/org/h2/util/Utils10.class`，major 54
- `META-INF/versions/21/org/h2/util/Utils21.class`，major 65

上游 `byte-buddy` 1.14.18 和 `slf4j-api` / `slf4j-simple` 2.0.19 各自的 jar 里也有 `META-INF/versions/9/module-info.class`。shade 之后的 agent jar 在这个路径上只保留一份 `META-INF/versions/9/module-info.class`（major 53），没有把 Byte Buddy 或 SLF4J 的描述符另存一份。JDK 8 忽略 `module-info`。

## 桶 (c)：测试、构建插件、网站、CI 的构建 JDK

这些可以要求更新的 JDK。它们不在 JDK 8 运行时 classpath 上，所以不算 “应用不能在 JDK 1.8 上跑”。

- **构建 JDK。** `ci.yml` 的 `build` job 矩阵是 `java: [ 11, 17, 21 ]`，不在 JDK 8 上编译。`quality-gate` 和 `package` job 用 JDK 17。本机这次 package 用的是 Java 21.0.12。父 pom 的 `default-compile` 与 `default-testCompile` 都是 `<release>8</release>`；JDK 8 javac 拒绝 `--release`，所以反应器不能用 JDK 8 当编译器。`java.version` 上面的注释写测试源码不受限，`default-testCompile` 的执行配置仍然是 `<release>8</release>`，`package.log` 里测试源码也是 `javac [debug release 8] ... target/test-classes`。
- **CI 里声称的 JDK 8 运行时。** `agent-smoke.yml` 的 `bare-attach` 矩阵是 `"8", "11", "17", "21"`，构建步是 JDK 17，运行步才是矩阵 JDK。本机用 Zulu 8 复现了这一形状，见桶 (a)。`ci.yml` 的 `compatibility-matrix` 也列出 JDK 8，但只是对已打包 agent 做 `java -javaagent`，而且这次没有跑 GitHub Actions，那个 job 的结果不是本机证据。`sample-attach` 只声明 JDK 17。
- **测试依赖。** `junit-jupiter` `5.10.2`、`mockito-core` `5.11.0`、`byte-buddy-agent`（test scope）、`logback-classic` `1.4.14`（test scope）、JMH `1.37`（test scope）不在 shade 后的 agent jar 里。Mockito 5 运行测试需要比 JDK 8 新的 JVM。这不影响 agent 的 JDK 8 运行时 classpath。
- **质量插件与网站。** JaCoCo `0.8.15`、SpotBugs `4.8.3.0`、Checkstyle `3.6.0` 绑在构建上。`website/` 是 Node。都没有放进 JDK 8 进程的 classpath。
- **文档漂移。** `CONTRIBUTING.md` 仍写 `maven.compiler.source=1.8` / `maven.compiler.target=1.8`。父 pom 实际用的是 `<release>8</release>`，没有 `maven.compiler.source` / `target`。这是文档和构建配置不一致，不是运行时故障。
- **镜像。** `Dockerfile` 是 `FROM eclipse-temurin:8-jre`，只拷 agent jar。这次没有构建或运行该镜像。`agent-smoke.yml` 头注释里的 “JDK 8 container run of SampleApplication” 在该 workflow 里没有对应 job。

## 边界

这次审查没有改源码、pom 或依赖。没有用 JDK 8 的 javac 去跑 `mvn`（`--release 8` 对那个 javac 不成立）。没有跑 `website/`，没有重跑 GitHub Actions，也没有改 `scripts/cross-jdk-smoke.sh`。
