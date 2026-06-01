# Weaver-Girl 底层支撑库 Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: `superpowers:subagent-driven-development`
> Steps use checkbox (`- [ ]`) syntax.

**Goal:** 构建一个**底层支撑库（Underlying Library）**级别的 Java Agent 字节码增强框架。其他项目/工具可以依赖 weaver-girl 作为基础设施来构建自己的 Agent 应用（APM、诊断工具、安全检测等）。支持声明式（注解）、配置式（YAML）、编程式（Fluent API）三种方式定义 Hook。

**Architecture:**

weaver-girl 定位为**底层库**而非上层应用。参照 SkyWalking / OpenTelemetry / JVM Sandbox / Elastic APM 的模块分层模式，采用 5 层架构：

```
┌─────────────────────────────────────────────────────────┐
│  weaver-girl-sample (示例应用，不发布)                      │
│  演示如何使用 weaver-girl 构建自定义 Agent                   │
├─────────────────────────────────────────────────────────┤
│  weaver-girl-agent (Agent Assembly，打包发布)              │
│  premain/agentmain 入口，shade + uber-jar 打包             │
├─────────────────────────────────────────────────────────┤
│  weaver-girl-core (Core Engine，内部实现)                  │
│  ByteBuddy Transformer、InterceptorRegistry、配置解析       │
│  插件加载、WeaverGirl 引导逻辑                              │
├─────────────────────────────────────────────────────────┤
│  weaver-girl-api (Plugin SDK，对外契约)                    │
│  Interceptor、Pointcut、ClassMatcher、MethodMatcher        │
│  MethodInvocation、WeaverPlugin、AbstractPlugin            │
│  插件开发者只需依赖此模块                                    │
├─────────────────────────────────────────────────────────┤
│  weaver-girl-annotation (Public API，零依赖)               │
│  @WeaveClass、@Before、@After、@Around 注解定义            │
│  任何 Java 项目都可以安全依赖                                │
└─────────────────────────────────────────────────────────┘
```

**数据流：** 用户定义 Hook（注解 / YAML / 编程式）→ API 层提供类型抽象 → Core 引擎通过 ByteBuddy AgentBuilder 在类加载时匹配 Pointcut 并织入 Advice → 运行时 MethodInvocation 触发 before/after/onException 回调 → Interceptor 错误吞没保证不影响目标应用。

**Tech Stack:** Java 8+, Maven 3.6+, ByteBuddy 1.14.x (shaded in agent), ASM 9.x (via ByteBuddy), SnakeYAML 2.x, SLF4J 1.7.x, JUnit 5, Mockito

**Risks:**
- ByteBuddy 需要 shade 进 agent JAR 以避免类冲突 → 缓解：使用 maven-shade-plugin 的 relocation 机制
- Interceptor 代码在目标 JVM 中运行，ClassLoader 隔离至关重要 → 缓解：使用独立 ClassLoader 加载插件，通过接口桥接
- YAML 配置错误可能导致 agent 启动失败 → 缓解：启动时校验配置，错误只 log 不抛异常，降级为空操作
- `weaver-girl-api` 作为对外契约，一旦发布不可随意改接口 → 缓解：v1.x 阶段标记为 @Experimental，预留演进空间

---

## 参考开源项目分析（底层库视角）

以下分析聚焦于各项目的**底层支撑库**部分，而非上层应用。

### 底层库 vs 上层应用对照表

| 项目 | 上层应用（我们不做） | 底层支撑库（我们学习） | 库的角色 |
|------|--------------------|--------------------|---------|
| **SkyWalking** | apm-sdk-plugin/*（50+ 框架插件）| apm-agent-core, apm-application-toolkit | Agent Core + 用户 API |
| **OpenTelemetry** | instrumentation/*/*（200+ 插件）| instrumentation-api, javaagent-extension-api | 插件开发 SDK |
| **JVM Sandbox** | sandbox-mgr-module, Repeater | sandbox-api, sandbox-spy, sandbox-provider-api | 模块开发 API |
| **Elastic APM** | apm-agent-plugins/*（50+ 插件）| apm-agent-plugin-sdk, apm-agent-api | 插件 SDK + 用户 API |
| **ByteKit** | Arthas（诊断工具）| bytekit-api, bytekit-core, bytekit-instrument-api | 纯字节码操作库 |
| **TTL** | 无上层 | transmittable-thread-local 整个项目 | 跨线程传递库 |

### weaver-girl 定位

```
weaver-girl = JVM Sandbox 的通用性 + ByteKit 的注解丰富度 + SkyWalking 的插件 SPI
             但作为纯粹的底层库，不包含任何特定领域的上层插件
```

### 关键借鉴点

| weaver-girl 设计 | 借鉴来源 | 借鉴内容 |
|-----------------|---------|---------|
| 5 层模块分离 | SkyWalking/OTel/Elastic APM | API 层独立于 Core，Plugin 只依赖 API |
| `weaver-girl-api` 作为 Plugin SDK | Elastic `apm-agent-plugin-sdk`, OTel `javaagent-extension-api` | 插件编译时只依赖薄 SDK |
| `weaver-girl-annotation` 零依赖 | SkyWalking `apm-toolkit-trace`, OTel `instrumentation-annotations` | 任何项目安全依赖 |
| `@WeaveClass`/`@Before`/`@After` 注解 | ByteKit `@AtEnter`/`@AtExit`, New Relic `@Weave` | 声明式拦截定义 |
| YAML 配置式 | New Relic YAML PointCut, OpenRASP JS 插件 | 配置化 Hook 定义 |
| Fluent API 编程式 | JVM Sandbox `EventWatchBuilder`, SkyWalking 插件基类 | 编程式 API |
| SPI 插件发现 | SkyWalking `skywalking-plugin.def`, OTel ServiceLoader | SPI 热插拔 |
| InterceptorRegistry 全局注册 | SkyWalking 插件注册, Elastic ApmAgent | 中央注册表 |
| Interceptor before/after/onException | SkyWalking `InstanceMethodsAroundInterceptor`, JVM Sandbox `AdviceListener` | Around-advice 行业标准 |
| agentmain 热附加 | Arthas, JVM Sandbox, OTel | 运行时附加到已启动 JVM |
| InterceptorHolder 全局静态引用 | ByteBuddy Advice 的静态方法约束 | Advice 必须是静态方法 |
| Maven Shade + Relocation | SkyWalking, OTel 的 shade 方案 | 避免类冲突 |
| Interceptor 错误吞没 | OTel, Elastic APM 的安全策略 | 拦截器异常不影响目标应用 |

---

### Task 1: Project Scaffold — Maven 多模块重构

**Depends on:** None
**Files:**
- Modify: `pom.xml` (父 POM，添加 weaver-girl-api 模块)
- Modify: `weaver-girl-core/pom.xml` (改为依赖 weaver-girl-api)
- Create: `weaver-girl-api/pom.xml`
- Create: `weaver-girl-api/src/main/java/` 目录结构
- Modify: `README.md`

- [ ] **Step 1: 创建 weaver-girl-api 子模块 POM — 定义 Plugin SDK 层**

```xml
<!-- weaver-girl-api/pom.xml -->
<?xml version="1.0" encoding="UTF-8"?>
<project xmlns="http://maven.apache.org/POM/4.0.0"
         xmlns:xsi="http://www.w3.org/2001/XMLSchema-instance"
         xsi:schemaLocation="http://maven.apache.org/POM/4.0.0 http://maven.apache.org/xsd/maven-4.0.0.xsd">
    <modelVersion>4.0.0</modelVersion>

    <parent>
        <groupId>com.github.cc11001100</groupId>
        <artifactId>weaver-girl</artifactId>
        <version>1.0.0-SNAPSHOT</version>
    </parent>

    <artifactId>weaver-girl-api</artifactId>
    <packaging>jar</packaging>

    <name>Weaver Girl - API</name>
    <description>Plugin SDK: interfaces and types for building weaver-girl plugins. External developers depend on this.</description>

    <dependencies>
        <!-- api 层只依赖 annotation，不依赖 ByteBuddy / SnakeYAML 等实现库 -->
        <dependency>
            <groupId>com.github.cc11001100</groupId>
            <artifactId>weaver-girl-annotation</artifactId>
        </dependency>

        <dependency>
            <groupId>org.junit.jupiter</groupId>
            <artifactId>junit-jupiter</artifactId>
            <scope>test</scope>
        </dependency>
    </dependencies>
</project>
```

- [ ] **Step 2: 修改父 POM — 添加 weaver-girl-api 模块声明和依赖管理**
文件: `pom.xml:15-20`（modules 列表）和 `pom.xml:59-70`（dependencyManagement）

```xml
<!-- 替换 pom.xml:15-20 的 modules 列表 -->
    <modules>
        <module>weaver-girl-annotation</module>
        <module>weaver-girl-api</module>
        <module>weaver-girl-core</module>
        <module>weaver-girl-agent</module>
        <module>weaver-girl-sample</module>
    </modules>
```

```xml
<!-- 在 pom.xml 的 dependencyManagement 中添加 weaver-girl-api（在现有 weaver-girl-annotation 之后） -->
            <dependency>
                <groupId>com.github.cc11001100</groupId>
                <artifactId>weaver-girl-api</artifactId>
                <version>${project.version}</version>
            </dependency>
```

- [ ] **Step 3: 修改 weaver-girl-core/pom.xml — 改为依赖 weaver-girl-api 而非直接依赖 annotation**
文件: `weaver-girl-core/pom.xml:38-42`（Internal 依赖区块）

```xml
<!-- 替换 weaver-girl-core/pom.xml 的 dependencies 中的 Internal 区块 -->
        <!-- Internal — core 依赖 api（api 已经传递依赖 annotation） -->
        <dependency>
            <groupId>com.github.cc11001100</groupId>
            <artifactId>weaver-girl-api</artifactId>
        </dependency>
```

- [ ] **Step 4: 修改 weaver-girl-sample/pom.xml — 样例只需依赖 api 层**
文件: `weaver-girl-sample/pom.xml:19-28`（dependencies 区块）

```xml
<!-- 替换 weaver-girl-sample/pom.xml 的 dependencies -->
    <dependencies>
        <!-- 样例插件只需要依赖 api（Plugin SDK），模拟真实的插件开发体验 -->
        <dependency>
            <groupId>com.github.cc11001100</groupId>
            <artifactId>weaver-girl-api</artifactId>
        </dependency>
        <!-- 运行时需要 agent，在集成测试中使用 -->
        <dependency>
            <groupId>com.github.cc11001100</groupId>
            <artifactId>weaver-girl-core</artifactId>
            <scope>test</scope>
        </dependency>

        <dependency>
            <groupId>org.junit.jupiter</groupId>
            <artifactId>junit-jupiter</artifactId>
            <scope>test</scope>
        </dependency>
    </dependencies>
```

- [ ] **Step 5: 更新 README.md — 反映新的 5 模块架构**

```markdown
<!-- README.md -->
# Weaver Girl

A **底层支撑库** (underlying library) for building Java Agent bytecode instrumentation tools.

**Weaver Girl is NOT an APM tool, diagnostic tool, or monitoring system.** It is the foundational library that tools like those can be built upon — similar to how ByteKit powers Arthas, or how `apm-agent-core` powers SkyWalking.

## Features

- **Declarative** — Define hooks via annotations (`@WeaveClass`, `@Before`, `@After`, `@Around`)
- **Configuration** — Define hooks via YAML configuration files
- **Programmatic** — Define hooks via fluent Java API

## Module Structure

| Module | Type | Description |
|--------|------|-------------|
| `weaver-girl-annotation` | Public API (zero-dep) | Annotation definitions for declarative hooks |
| `weaver-girl-api` | Plugin SDK | Interfaces and types for building plugins — **this is what plugin developers depend on** |
| `weaver-girl-core` | Core Engine (internal) | ByteBuddy-based implementation: transformer, registry, config parsing |
| `weaver-girl-agent` | Agent Assembly | premain/agentmain entry point, shaded uber-jar |
| `weaver-girl-sample` | Sample (not published) | Demonstrates how to build a custom agent using weaver-girl |

## Quick Start

### For Plugin Developers

Add `weaver-girl-api` as your only dependency:

```xml
<dependency>
    <groupId>com.github.cc11001100</groupId>
    <artifactId>weaver-girl-api</artifactId>
    <version>1.0.0-SNAPSHOT</version>
</dependency>
```

### Build

```bash
mvn clean package
```

### Run

```bash
java -javaagent:weaver-girl-agent/target/weaver-girl-agent-1.0.0-SNAPSHOT.jar -jar your-app.jar
```

## Requirements

- Java 1.8+
- Maven 3.6+
```

- [ ] **Step 6: 创建 weaver-girl-api 目录结构**

```bash
mkdir -p weaver-girl-api/src/main/java/com/github/cc11001100/weavergirl/api/matcher
mkdir -p weaver-girl-api/src/main/java/com/github/cc11001100/weavergirl/api/pointcut
mkdir -p weaver-girl-api/src/main/java/com/github/cc11001100/weavergirl/api/interceptor
mkdir -p weaver-girl-api/src/main/java/com/github/cc11001100/weavergirl/api/plugin
mkdir -p weaver-girl-api/src/main/java/com/github/cc11001100/weavergirl/api/registry
mkdir -p weaver-girl-api/src/test/java/com/github/cc11001100/weavergirl/api
```

- [ ] **Step 7: 验证项目构建**
Run: `cd /home/cc11001100/github/weaver-girl/weaver-girl && mvn validate -q`
Expected:
  - Exit code: 0
  - Output does NOT contain: "ERROR" or "FATAL"

- [ ] **Step 8: 提交**
Run: `git add pom.xml weaver-girl-api/ weaver-girl-core/pom.xml weaver-girl-sample/pom.xml README.md && git commit -m "refactor(scaffold): restructure to 5-module layered architecture — add weaver-girl-api as Plugin SDK layer"`

---

### Task 2: API Module — Plugin SDK 接口和类型定义

**Depends on:** Task 1
**Files:**
- Create: `weaver-girl-api/src/main/java/com/github/cc11001100/weavergirl/api/matcher/ClassMatcher.java`
- Create: `weaver-girl-api/src/main/java/com/github/cc11001100/weavergirl/api/matcher/MethodMatcher.java`
- Create: `weaver-girl-api/src/main/java/com/github/cc11001100/weavergirl/api/interceptor/MethodInvocation.java`
- Create: `weaver-girl-api/src/main/java/com/github/cc11001100/weavergirl/api/interceptor/Interceptor.java`
- Create: `weaver-girl-api/src/main/java/com/github/cc11001100/weavergirl/api/pointcut/Pointcut.java`
- Create: `weaver-girl-api/src/main/java/com/github/cc11001100/weavergirl/api/interceptor/InterceptorDefinition.java`
- Create: `weaver-girl-api/src/main/java/com/github/cc11001100/weavergirl/api/registry/InterceptorRegistry.java`
- Create: `weaver-girl-api/src/main/java/com/github/cc11001100/weavergirl/api/plugin/WeaverPlugin.java`
- Create: `weaver-girl-api/src/main/java/com/github/cc11001100/weavergirl/api/plugin/AbstractPlugin.java`
- Test: `weaver-girl-api/src/test/java/com/github/cc11001100/weavergirl/api/matcher/ClassMatcherTest.java`
- Test: `weaver-girl-api/src/test/java/com/github/cc11001100/weavergirl/api/pointcut/PointcutTest.java`

**设计原则：** api 模块是 Plugin SDK，只包含接口、抽象类和纯类型定义。不依赖 ByteBuddy、SnakeYAML 等任何实现库。插件开发者只需依赖此模块即可编写插件。

- [ ] **Step 1: 创建 ClassMatcher — 类匹配器，支持按名称、注解、父类匹配**

```java
// weaver-girl-api/src/main/java/com/github/cc11001100/weavergirl/api/matcher/ClassMatcher.java
package com.github.cc11001100.weavergirl.api.matcher;

import java.util.regex.Pattern;

/**
 * Matcher for selecting target classes to intercept.
 * Supports matching by exact name, name pattern, annotation, or parent class.
 *
 * <p>This class lives in the API module so plugin developers can construct
 * matchers without depending on the core implementation.</p>
 */
public class ClassMatcher {

    public enum MatchType {
        EXACT_NAME,
        NAME_PATTERN,
        ANNOTATION,
        SUPER_CLASS,
        INTERFACE
    }

    private final MatchType matchType;
    private final String pattern;
    private final Pattern compiledRegex;

    private ClassMatcher(MatchType matchType, String pattern) {
        this.matchType = matchType;
        this.pattern = pattern;
        this.compiledRegex = (matchType == MatchType.NAME_PATTERN)
                ? Pattern.compile(pattern) : null;
    }

    public static ClassMatcher byName(String className) {
        return new ClassMatcher(MatchType.EXACT_NAME, className);
    }

    public static ClassMatcher byNamePattern(String regex) {
        return new ClassMatcher(MatchType.NAME_PATTERN, regex);
    }

    public static ClassMatcher byAnnotation(String annotationClassName) {
        return new ClassMatcher(MatchType.ANNOTATION, annotationClassName);
    }

    public static ClassMatcher bySuperClass(String superClassName) {
        return new ClassMatcher(MatchType.SUPER_CLASS, superClassName);
    }

    public static ClassMatcher byInterface(String interfaceName) {
        return new ClassMatcher(MatchType.INTERFACE, interfaceName);
    }

    public MatchType getMatchType() {
        return matchType;
    }

    public String getPattern() {
        return pattern;
    }

    public boolean matches(String className) {
        switch (matchType) {
            case EXACT_NAME:
                return pattern.equals(className);
            case NAME_PATTERN:
                return compiledRegex != null && compiledRegex.matcher(className).matches();
            default:
                return false;
        }
    }

    @Override
    public String toString() {
        return "ClassMatcher{" + matchType + ": " + pattern + "}";
    }
}
```

- [ ] **Step 2: 创建 MethodMatcher — 方法匹配器，支持按名称、签名、注解匹配**

```java
// weaver-girl-api/src/main/java/com/github/cc11001100/weavergirl/api/matcher/MethodMatcher.java
package com.github.cc11001100.weavergirl.api.matcher;

import java.util.regex.Pattern;

/**
 * Matcher for selecting target methods to intercept within a matched class.
 * Supports matching by exact name, name pattern, annotation, or any method.
 */
public class MethodMatcher {

    public enum MatchType {
        EXACT_NAME,
        NAME_PATTERN,
        ANNOTATION,
        ANY
    }

    private final MatchType matchType;
    private final String pattern;
    private final Pattern compiledRegex;

    private MethodMatcher(MatchType matchType, String pattern) {
        this.matchType = matchType;
        this.pattern = pattern;
        this.compiledRegex = (matchType == MatchType.NAME_PATTERN)
                ? Pattern.compile(pattern) : null;
    }

    public static MethodMatcher byName(String methodName) {
        return new MethodMatcher(MatchType.EXACT_NAME, methodName);
    }

    public static MethodMatcher byNamePattern(String regex) {
        return new MethodMatcher(MatchType.NAME_PATTERN, regex);
    }

    public static MethodMatcher byAnnotation(String annotationClassName) {
        return new MethodMatcher(MatchType.ANNOTATION, annotationClassName);
    }

    public static MethodMatcher any() {
        return new MethodMatcher(MatchType.ANY, "*");
    }

    public MatchType getMatchType() {
        return matchType;
    }

    public String getPattern() {
        return pattern;
    }

    public boolean matches(String methodName) {
        switch (matchType) {
            case EXACT_NAME:
                return pattern.equals(methodName);
            case NAME_PATTERN:
                return compiledRegex != null && compiledRegex.matcher(methodName).matches();
            case ANY:
                return true;
            default:
                return false;
        }
    }

    @Override
    public String toString() {
        return "MethodMatcher{" + matchType + ": " + pattern + "}";
    }
}
```

- [ ] **Step 3: 创建 MethodInvocation — 方法调用上下文对象，封装运行时拦截信息**

```java
// weaver-girl-api/src/main/java/com/github/cc11001100/weavergirl/api/interceptor/MethodInvocation.java
package com.github.cc11001100.weavergirl.api.interceptor;

/**
 * Context object passed to interceptors at runtime.
 * Encapsulates all information about the intercepted method call.
 *
 * <p>Interceptors can:</p>
 * <ul>
 *   <li>Read target class, method name, arguments</li>
 *   <li>Set return value (to override original return)</li>
 *   <li>Call {@link #skipMethod()} to prevent original method execution</li>
 *   <li>Access thrown exception in onException callback</li>
 * </ul>
 */
public class MethodInvocation {

    private final Class<?> targetClass;
    private final String methodName;
    private final Object target;
    private final Object[] arguments;
    private Object returnValue;
    private Throwable throwable;
    private boolean isSkipped;

    public MethodInvocation(Class<?> targetClass, String methodName,
                            Object target, Object[] arguments) {
        this.targetClass = targetClass;
        this.methodName = methodName;
        this.target = target;
        this.arguments = arguments != null ? arguments : new Object[0];
        this.isSkipped = false;
    }

    public Class<?> getTargetClass() {
        return targetClass;
    }

    public String getMethodName() {
        return methodName;
    }

    public Object getTarget() {
        return target;
    }

    public Object[] getArguments() {
        return arguments;
    }

    public Object getArgument(int index) {
        if (index < 0 || index >= arguments.length) {
            throw new IndexOutOfBoundsException(
                    "Argument index " + index + " out of bounds for " + arguments.length + " arguments");
        }
        return arguments[index];
    }

    public Object getReturnValue() {
        return returnValue;
    }

    public void setReturnValue(Object returnValue) {
        this.returnValue = returnValue;
    }

    public Throwable getThrowable() {
        return throwable;
    }

    public void setThrowable(Throwable throwable) {
        this.throwable = throwable;
    }

    public boolean hasException() {
        return throwable != null;
    }

    public boolean isSkipped() {
        return isSkipped;
    }

    public void skipMethod() {
        this.isSkipped = true;
    }
}
```

- [ ] **Step 4: 创建 Interceptor 接口 — 定义拦截回调**

```java
// weaver-girl-api/src/main/java/com/github/cc11001100/weavergirl/api/interceptor/Interceptor.java
package com.github.cc11001100.weavergirl.api.interceptor;

/**
 * Core interceptor interface for method-level around-advice.
 * Implementations provide before/after/exception hooks.
 *
 * <p>All methods have default no-op implementations so implementors
 * only need to override the hooks they care about.</p>
 *
 * <p><strong>Safety contract:</strong> Implementations MUST NOT throw
 * exceptions that escape these methods. If an exception occurs, catch
 * it internally. The framework will also catch exceptions as a safety net,
 * but implementations should handle their own errors gracefully.</p>
 */
public interface Interceptor {

    /**
     * Called before the target method executes.
     * Use invocation.skipMethod() to skip the original method execution.
     */
    default void before(MethodInvocation invocation) {
    }

    /**
     * Called after the target method executes successfully.
     */
    default void after(MethodInvocation invocation) {
    }

    /**
     * Called when the target method throws an exception.
     */
    default void onException(MethodInvocation invocation) {
    }
}
```

- [ ] **Step 5: 创建 Pointcut + InterceptorDefinition — 切点定义和拦截器定义**

Pointcut:

```java
// weaver-girl-api/src/main/java/com/github/cc11001100/weavergirl/api/pointcut/Pointcut.java
package com.github.cc11001100.weavergirl.api.pointcut;

import com.github.cc11001100.weavergirl.api.matcher.ClassMatcher;
import com.github.cc11001100.weavergirl.api.matcher.MethodMatcher;

/**
 * Pointcut combines a ClassMatcher and MethodMatcher to define
 * which classes and methods should be intercepted.
 */
public class Pointcut {

    private final ClassMatcher classMatcher;
    private final MethodMatcher methodMatcher;

    public Pointcut(ClassMatcher classMatcher, MethodMatcher methodMatcher) {
        this.classMatcher = classMatcher;
        this.methodMatcher = methodMatcher;
    }

    public ClassMatcher getClassMatcher() {
        return classMatcher;
    }

    public MethodMatcher getMethodMatcher() {
        return methodMatcher;
    }

    @Override
    public String toString() {
        return "Pointcut{class=" + classMatcher + ", method=" + methodMatcher + "}";
    }
}
```

InterceptorDefinition:

```java
// weaver-girl-api/src/main/java/com/github/cc11001100/weavergirl/api/interceptor/InterceptorDefinition.java
package com.github.cc11001100.weavergirl.api.interceptor;

import com.github.cc11001100.weavergirl.api.pointcut.Pointcut;

/**
 * A complete interceptor definition that binds a Pointcut to an Interceptor.
 * This is the fundamental unit registered with the InterceptorRegistry.
 */
public class InterceptorDefinition {

    private final String name;
    private final Pointcut pointcut;
    private final Interceptor interceptor;
    private final int priority;

    public InterceptorDefinition(String name, Pointcut pointcut, Interceptor interceptor) {
        this(name, pointcut, interceptor, 0);
    }

    public InterceptorDefinition(String name, Pointcut pointcut, Interceptor interceptor, int priority) {
        this.name = name;
        this.pointcut = pointcut;
        this.interceptor = interceptor;
        this.priority = priority;
    }

    public String getName() { return name; }
    public Pointcut getPointcut() { return pointcut; }
    public Interceptor getInterceptor() { return interceptor; }
    public int getPriority() { return priority; }

    @Override
    public String toString() {
        return "InterceptorDefinition{name='" + name + "', pointcut=" + pointcut + ", priority=" + priority + "}";
    }
}
```

- [ ] **Step 6: 创建 InterceptorRegistry 接口 — 拦截器注册中心**

```java
// weaver-girl-api/src/main/java/com/github/cc11001100/weavergirl/api/registry/InterceptorRegistry.java
package com.github.cc11001100.weavergirl.api.registry;

import com.github.cc11001100.weavergirl.api.interceptor.InterceptorDefinition;

import java.util.List;

/**
 * Registry for interceptor definitions.
 *
 * <p>API module defines the interface; Core module provides the implementation.
 * This separation allows plugin developers to code against the interface
 * without depending on the core engine.</p>
 */
public interface InterceptorRegistry {

    /**
     * Register an interceptor definition.
     */
    void register(InterceptorDefinition definition);

    /**
     * Get all interceptor definitions that match the given class name.
     */
    List<InterceptorDefinition> getInterceptorsForClass(String className);

    /**
     * Get all registered definitions.
     */
    List<InterceptorDefinition> getAllDefinitions();
}
```

- [ ] **Step 7: 创建 WeaverPlugin 接口 + AbstractPlugin 基类 — 插件 SPI 契约**

WeaverPlugin:

```java
// weaver-girl-api/src/main/java/com/github/cc11001100/weavergirl/api/plugin/WeaverPlugin.java
package com.github.cc11001100.weavergirl.api.plugin;

import com.github.cc11001100.weavergirl.api.registry.InterceptorRegistry;

/**
 * SPI interface for weaver-girl plugins.
 * Implementations are discovered via Java ServiceLoader (META-INF/services).
 *
 * <p>Plugin developers implement this interface and declare it in
 * {@code META-INF/services/com.github.cc11001100.weavergirl.api.plugin.WeaverPlugin}.</p>
 */
public interface WeaverPlugin {

    /**
     * Unique plugin name.
     */
    String name();

    /**
     * Register interceptor definitions with the registry.
     * Called once during agent startup.
     */
    void registerInterceptors(InterceptorRegistry registry);
}
```

AbstractPlugin:

```java
// weaver-girl-api/src/main/java/com/github/cc11001100/weavergirl/api/plugin/AbstractPlugin.java
package com.github.cc11001100.weavergirl.api.plugin;

import com.github.cc11001100.weavergirl.api.interceptor.Interceptor;
import com.github.cc11001100.weavergirl.api.interceptor.InterceptorDefinition;
import com.github.cc11001100.weavergirl.api.interceptor.MethodInvocation;
import com.github.cc11001100.weavergirl.api.matcher.ClassMatcher;
import com.github.cc11001100.weavergirl.api.matcher.MethodMatcher;
import com.github.cc11001100.weavergirl.api.pointcut.Pointcut;
import com.github.cc11001100.weavergirl.api.registry.InterceptorRegistry;

/**
 * Base plugin class providing a convenient fluent API for registering interceptors.
 *
 * <p>Example usage:</p>
 * <pre>
 * public class MyPlugin extends AbstractPlugin {
 *     public String name() { return "my-plugin"; }
 *     public void registerInterceptors(InterceptorRegistry registry) {
 *         registry.register(
 *             intercept("com.example.Service")
 *                 .method("process")
 *                 .before(inv -> System.out.println("before: " + inv.getMethodName()))
 *                 .build()
 *         );
 *     }
 * }
 * </pre>
 */
public abstract class AbstractPlugin implements WeaverPlugin {

    protected InterceptorDefinitionBuilder intercept(String className) {
        return new InterceptorDefinitionBuilder(this, className);
    }

    /**
     * Helper class for building interceptor definitions fluently.
     */
    protected static class InterceptorDefinitionBuilder {
        private final AbstractPlugin plugin;
        private final String className;
        private String methodName = "*";
        private Interceptor interceptor;

        InterceptorDefinitionBuilder(AbstractPlugin plugin, String className) {
            this.plugin = plugin;
            this.className = className;
        }

        public InterceptorDefinitionBuilder method(String methodName) {
            this.methodName = methodName;
            return this;
        }

        public InterceptorDefinitionBuilder before(final BeforeCallback callback) {
            this.interceptor = new Interceptor() {
                @Override
                public void before(MethodInvocation invocation) {
                    callback.before(invocation);
                }
            };
            return this;
        }

        public InterceptorDefinitionBuilder around(final BeforeCallback beforeCallback, final AfterCallback afterCallback) {
            this.interceptor = new Interceptor() {
                @Override
                public void before(MethodInvocation invocation) {
                    beforeCallback.before(invocation);
                }

                @Override
                public void after(MethodInvocation invocation) {
                    afterCallback.after(invocation);
                }

                @Override
                public void onException(MethodInvocation invocation) {
                    afterCallback.after(invocation);
                }
            };
            return this;
        }

        public InterceptorDefinition build() {
            ClassMatcher classMatcher = ClassMatcher.byName(className);
            MethodMatcher methodMatcher = "*".equals(methodName)
                    ? MethodMatcher.any() : MethodMatcher.byName(methodName);
            Pointcut pointcut = new Pointcut(classMatcher, methodMatcher);
            if (interceptor == null) {
                interceptor = new Interceptor() {};
            }
            return new InterceptorDefinition(plugin.name() + "-" + className + "-" + methodName,
                    pointcut, interceptor);
        }
    }

    @FunctionalInterface
    protected interface BeforeCallback {
        void before(MethodInvocation invocation);
    }

    @FunctionalInterface
    protected interface AfterCallback {
        void after(MethodInvocation invocation);
    }
}
```

- [ ] **Step 8: 创建 API 模块单元测试**

```java
// weaver-girl-api/src/test/java/com/github/cc11001100/weavergirl/api/matcher/ClassMatcherTest.java
package com.github.cc11001100.weavergirl.api.matcher;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class ClassMatcherTest {

    @Test
    void byName_exactMatch_returnsTrue() {
        ClassMatcher matcher = ClassMatcher.byName("com.example.TargetService");
        assertTrue(matcher.matches("com.example.TargetService"));
    }

    @Test
    void byName_noMatch_returnsFalse() {
        ClassMatcher matcher = ClassMatcher.byName("com.example.TargetService");
        assertFalse(matcher.matches("com.example.OtherService"));
    }

    @Test
    void byNamePattern_matchingPattern_returnsTrue() {
        ClassMatcher matcher = ClassMatcher.byNamePattern("com\\.example\\..*Service");
        assertTrue(matcher.matches("com.example.UserService"));
        assertTrue(matcher.matches("com.example.OrderService"));
    }

    @Test
    void byNamePattern_nonMatchingPattern_returnsFalse() {
        ClassMatcher matcher = ClassMatcher.byNamePattern("com\\.example\\..*Service");
        assertFalse(matcher.matches("com.example.Util"));
    }

    @Test
    void matchType_preservedCorrectly() {
        assertEquals(ClassMatcher.MatchType.EXACT_NAME, ClassMatcher.byName("x").getMatchType());
        assertEquals(ClassMatcher.MatchType.NAME_PATTERN, ClassMatcher.byNamePattern("x").getMatchType());
        assertEquals(ClassMatcher.MatchType.ANNOTATION, ClassMatcher.byAnnotation("x").getMatchType());
        assertEquals(ClassMatcher.MatchType.SUPER_CLASS, ClassMatcher.bySuperClass("x").getMatchType());
        assertEquals(ClassMatcher.MatchType.INTERFACE, ClassMatcher.byInterface("x").getMatchType());
    }
}
```

```java
// weaver-girl-api/src/test/java/com/github/cc11001100/weavergirl/api/pointcut/PointcutTest.java
package com.github.cc11001100.weavergirl.api.pointcut;

import com.github.cc11001100.weavergirl.api.interceptor.Interceptor;
import com.github.cc11001100.weavergirl.api.interceptor.InterceptorDefinition;
import com.github.cc11001100.weavergirl.api.matcher.ClassMatcher;
import com.github.cc11001100.weavergirl.api.matcher.MethodMatcher;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class PointcutTest {

    @Test
    void pointcut_combinesClassAndMethodMatchers() {
        Pointcut pointcut = new Pointcut(
                ClassMatcher.byName("com.example.Service"),
                MethodMatcher.byName("process")
        );
        assertEquals("com.example.Service", pointcut.getClassMatcher().getPattern());
        assertEquals("process", pointcut.getMethodMatcher().getPattern());
    }

    @Test
    void interceptorDefinition_holdsAllFields() {
        Pointcut pointcut = new Pointcut(ClassMatcher.byName("svc"), MethodMatcher.any());
        Interceptor interceptor = new Interceptor() {};
        InterceptorDefinition def = new InterceptorDefinition("test", pointcut, interceptor, 5);
        assertEquals("test", def.getName());
        assertEquals(5, def.getPriority());
        assertNotNull(def.toString());
    }
}
```

- [ ] **Step 9: 验证 API 模块编译和测试**
Run: `cd /home/cc11001100/github/weaver-girl/weaver-girl && mvn test -pl weaver-girl-api -q`
Expected:
  - Exit code: 0
  - Output contains: "BUILD SUCCESS"

- [ ] **Step 10: 提交**
Run: `git add weaver-girl-api/ && git commit -m "feat(api): add Plugin SDK module — ClassMatcher, MethodMatcher, Interceptor, Pointcut, WeaverPlugin, AbstractPlugin"`

---

### Task 3: Annotation Module — 声明式注解定义

**Depends on:** Task 1
**Files:**
- Create: `weaver-girl-annotation/src/main/java/com/github/cc11001100/weavergirl/annotation/WeaveClass.java`
- Create: `weaver-girl-annotation/src/main/java/com/github/cc11001100/weavergirl/annotation/Before.java`
- Create: `weaver-girl-annotation/src/main/java/com/github/cc11001100/weavergirl/annotation/After.java`
- Create: `weaver-girl-annotation/src/main/java/com/github/cc11001100/weavergirl/annotation/Around.java`

**设计原则：** annotation 模块是零依赖的 Public API。任何 Java 项目都可以安全依赖，类似 SkyWalking 的 `apm-toolkit-trace` 和 OTel 的 `instrumentation-annotations`。

- [ ] **Step 1: 创建 WeaveClass 注解 — 标注在类上声明要拦截的目标类**

```java
// weaver-girl-annotation/src/main/java/com/github/cc11001100/weavergirl/annotation/WeaveClass.java
package com.github.cc11001100.weavergirl.annotation;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Marks a class as an interceptor for a target class.
 * The annotated class should contain methods annotated with @Before, @After, or @Around.
 *
 * <p>Example:</p>
 * <pre>
 *   {@code @WeaveClass(target = "com.example.UserService")}
 *   public class UserServiceInterceptor {
 *       {@code @Before("createUser")}
 *       public void beforeCreate(MethodInvocation invocation) { ... }
 *   }
 * </pre>
 */
@Target(ElementType.TYPE)
@Retention(RetentionPolicy.RUNTIME)
public @interface WeaveClass {

    /**
     * Fully qualified name of the target class to intercept.
     */
    String target();

    /**
     * Optional name pattern (regex) as alternative to exact target name.
     */
    String targetPattern() default "";
}
```

- [ ] **Step 2: 创建 @Before 注解 — 标注在方法上声明前置拦截**

```java
// weaver-girl-annotation/src/main/java/com/github/cc11001100/weavergirl/annotation/Before.java
package com.github.cc11001100.weavergirl.annotation;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Marks a method as a before-advice for a specific target method.
 * Must be used within a class annotated with @WeaveClass.
 *
 * <p>The annotated method must accept a single MethodInvocation parameter.</p>
 */
@Target(ElementType.METHOD)
@Retention(RetentionPolicy.RUNTIME)
public @interface Before {

    /**
     * Name of the target method to intercept.
     */
    String value();
}
```

- [ ] **Step 3: 创建 @After 注解 — 标注在方法上声明后置拦截**

```java
// weaver-girl-annotation/src/main/java/com/github/cc11001100/weavergirl/annotation/After.java
package com.github.cc11001100.weavergirl.annotation;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Marks a method as an after-advice for a specific target method.
 * Called after the target method returns successfully.
 *
 * <p>The annotated method must accept a single MethodInvocation parameter.</p>
 */
@Target(ElementType.METHOD)
@Retention(RetentionPolicy.RUNTIME)
public @interface After {

    /**
     * Name of the target method to intercept.
     */
    String value();
}
```

- [ ] **Step 4: 创建 @Around 注解 — 标注在方法上声明环绕拦截**

```java
// weaver-girl-annotation/src/main/java/com/github/cc11001100/weavergirl/annotation/Around.java
package com.github.cc11001100.weavergirl.annotation;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Marks a method as an around-advice for a specific target method.
 * The advice method receives the MethodInvocation and can call
 * before/after/exception logic in a single method.
 */
@Target(ElementType.METHOD)
@Retention(RetentionPolicy.RUNTIME)
public @interface Around {

    /**
     * Name of the target method to intercept.
     */
    String value();
}
```

- [ ] **Step 5: 验证 Annotation 模块编译**
Run: `cd /home/cc11001100/github/weaver-girl/weaver-girl && mvn compile -pl weaver-girl-annotation -q`
Expected:
  - Exit code: 0
  - Output does NOT contain: "ERROR" or "compilation failure"

- [ ] **Step 6: 提交**
Run: `git add weaver-girl-annotation/src && git commit -m "feat(annotation): add declarative annotations — @WeaveClass, @Before, @After, @Around (zero-dependency)"`

---

### Task 4: Core Engine — ByteBuddy 集成和核心实现

**Depends on:** Task 2, Task 3
**Files:**
- Create: `weaver-girl-core/src/main/java/com/github/cc11001100/weavergirl/core/registry/DefaultInterceptorRegistry.java`
- Create: `weaver-girl-core/src/main/java/com/github/cc11001100/weavergirl/core/InterceptAdvice.java`
- Create: `weaver-girl-core/src/main/java/com/github/cc11001100/weavergirl/core/InterceptorHolder.java`
- Create: `weaver-girl-core/src/main/java/com/github/cc11001100/weavergirl/core/transformer/WeaverTransformer.java`
- Create: `weaver-girl-core/src/main/java/com/github/cc11001100/weavergirl/core/plugin/PluginLoader.java`
- Create: `weaver-girl-core/src/main/java/com/github/cc11001100/weavergirl/core/plugin/AnnotationPluginLoader.java`
- Create: `weaver-girl-core/src/main/java/com/github/cc11001100/weavergirl/core/config/WeaverConfig.java`
- Create: `weaver-girl-core/src/main/java/com/github/cc11001100/weavergirl/core/config/YamlConfigLoader.java`
- Create: `weaver-girl-core/src/main/java/com/github/cc11001100/weavergirl/core/WeaverGirl.java`
- Test: `weaver-girl-core/src/test/java/com/github/cc11001100/weavergirl/core/registry/DefaultInterceptorRegistryTest.java`
- Test: `weaver-girl-core/src/test/java/com/github/cc11001100/weavergirl/core/plugin/PluginLoaderTest.java`
- Test: `weaver-girl-core/src/test/java/com/github/cc11001100/weavergirl/core/config/YamlConfigLoaderTest.java`

**设计原则：** core 模块是实现层，依赖 ByteBuddy、SnakeYAML。它实现 api 模块定义的接口（如 `InterceptorRegistry` → `DefaultInterceptorRegistry`。

- [ ] **Step 1: 创建 DefaultInterceptorRegistry — 实现 api 模块的 InterceptorRegistry 接口**

```java
// weaver-girl-core/src/main/java/com/github/cc11001100/weavergirl/core/registry/DefaultInterceptorRegistry.java
package com.github.cc11001100.weavergirl.core.registry;

import com.github.cc11001100.weavergirl.api.interceptor.InterceptorDefinition;
import com.github.cc11001100.weavergirl.api.registry.InterceptorRegistry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.*;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * Default thread-safe implementation of InterceptorRegistry.
 * Manages all registered interceptor definitions.
 */
public class DefaultInterceptorRegistry implements InterceptorRegistry {

    private static final Logger log = LoggerFactory.getLogger(DefaultInterceptorRegistry.class);

    private final List<InterceptorDefinition> definitions = new CopyOnWriteArrayList<>();

    @Override
    public void register(InterceptorDefinition definition) {
        if (definition == null) {
            log.warn("Attempted to register null InterceptorDefinition, ignoring");
            return;
        }
        definitions.add(definition);
        log.info("Registered interceptor: {}", definition.getName());
    }

    @Override
    public List<InterceptorDefinition> getInterceptorsForClass(String className) {
        List<InterceptorDefinition> matched = new ArrayList<>();
        for (InterceptorDefinition def : definitions) {
            if (def.getPointcut().getClassMatcher().matches(className)) {
                matched.add(def);
            }
        }
        matched.sort(Comparator.comparingInt(InterceptorDefinition::getPriority));
        return matched;
    }

    @Override
    public List<InterceptorDefinition> getAllDefinitions() {
        return Collections.unmodifiableList(definitions);
    }

    /**
     * Clear all registered definitions. For testing purposes.
     */
    public void clear() {
        definitions.clear();
    }
}
```

- [ ] **Step 2: 创建 InterceptAdvice — ByteBuddy Advice 类**

```java
// weaver-girl-core/src/main/java/com/github/cc11001100/weavergirl/core/InterceptAdvice.java
package com.github.cc11001100.weavergirl.core;

import com.github.cc11001100.weavergirl.api.interceptor.Interceptor;
import com.github.cc11001100.weavergirl.api.interceptor.InterceptorDefinition;
import com.github.cc11001100.weavergirl.api.interceptor.MethodInvocation;
import com.github.cc11001100.weavergirl.api.registry.InterceptorRegistry;
import net.bytebuddy.asm.Advice;

import java.lang.reflect.Method;
import java.util.List;

/**
 * ByteBuddy Advice class that gets inlined into target methods.
 * Delegates to InterceptorRegistry for interceptor lookup and invocation.
 */
public class InterceptAdvice {

    @Advice.OnMethodEnter(skipOn = MethodInvocation.class)
    public static MethodInvocation onMethodEnter(
            @Advice.Origin Class<?> targetClass,
            @Advice.Origin Method method,
            @Advice.This Object target,
            @Advice.AllArguments Object[] arguments) {
        try {
            String className = targetClass.getName();
            String methodName = method.getName();
            MethodInvocation invocation = new MethodInvocation(targetClass, methodName, target, arguments);

            InterceptorRegistry registry = InterceptorHolder.getRegistry();
            if (registry == null) {
                return invocation;
            }

            List<InterceptorDefinition> defs = registry.getInterceptorsForClass(className);
            for (InterceptorDefinition def : defs) {
                if (def.getPointcut().getMethodMatcher().matches(methodName)) {
                    try {
                        def.getInterceptor().before(invocation);
                    } catch (Exception e) {
                        // Swallow interceptor errors to avoid crashing target app
                    }
                }
            }
            return invocation;
        } catch (Exception e) {
            return null;
        }
    }

    @Advice.OnMethodExit(onThrowable = Throwable.class)
    public static void onMethodExit(
            @Advice.Enter MethodInvocation invocation,
            @Advice.Origin Class<?> targetClass,
            @Advice.Origin Method method,
            @Advice.Thrown Throwable throwable,
            @Advice.Return(typing = net.bytebuddy.implementation.bytecode.assign.Assigner.Typing.DYNAMIC) Object returnValue) {
        if (invocation == null) {
            return;
        }
        try {
            if (throwable != null) {
                invocation.setThrowable(throwable);
            } else {
                invocation.setReturnValue(returnValue);
            }

            String className = targetClass.getName();
            String methodName = method.getName();

            InterceptorRegistry registry = InterceptorHolder.getRegistry();
            if (registry == null) {
                return;
            }

            List<InterceptorDefinition> defs = registry.getInterceptorsForClass(className);
            for (InterceptorDefinition def : defs) {
                if (def.getPointcut().getMethodMatcher().matches(methodName)) {
                    try {
                        Interceptor interceptor = def.getInterceptor();
                        if (throwable != null) {
                            interceptor.onException(invocation);
                        } else {
                            interceptor.after(invocation);
                        }
                    } catch (Exception e) {
                        // Swallow interceptor errors
                    }
                }
            }
        } catch (Exception e) {
            // Never crash the target application
        }
    }
}
```

- [ ] **Step 3: 创建 InterceptorHolder + WeaverTransformer — 全局引用和 ByteBuddy 转换器**

InterceptorHolder:

```java
// weaver-girl-core/src/main/java/com/github/cc11001100/weavergirl/core/InterceptorHolder.java
package com.github.cc11001100.weavergirl.core;

import com.github.cc11001100.weavergirl.api.registry.InterceptorRegistry;

/**
 * Global holder for the InterceptorRegistry instance.
 * Needed because ByteBuddy Advice classes are static and cannot
 * access instance fields — they need a global reference.
 */
public class InterceptorHolder {

    private static volatile InterceptorRegistry registry;

    public static void setRegistry(InterceptorRegistry registry) {
        InterceptorHolder.registry = registry;
    }

    public static InterceptorRegistry getRegistry() {
        return registry;
    }
}
```

WeaverTransformer:

```java
// weaver-girl-core/src/main/java/com/github/cc11001100/weavergirl/core/transformer/WeaverTransformer.java
package com.github.cc11001100.weavergirl.core.transformer;

import com.github.cc11001100.weavergirl.api.interceptor.InterceptorDefinition;
import com.github.cc11001100.weavergirl.api.matcher.ClassMatcher;
import com.github.cc11001100.weavergirl.api.registry.InterceptorRegistry;
import com.github.cc11001100.weavergirl.core.InterceptAdvice;
import net.bytebuddy.agent.builder.AgentBuilder;
import net.bytebuddy.description.type.TypeDescription;
import net.bytebuddy.dynamic.DynamicType;
import net.bytebuddy.utility.JavaModule;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.lang.instrument.Instrumentation;

import static net.bytebuddy.matcher.ElementMatchers.*;

/**
 * Core transformer that registers ByteBuddy type transformers
 * based on registered interceptor definitions.
 */
public class WeaverTransformer {

    private static final Logger log = LoggerFactory.getLogger(WeaverTransformer.class);

    private final InterceptorRegistry registry;

    public WeaverTransformer(InterceptorRegistry registry) {
        this.registry = registry;
    }

    /**
     * Install this transformer onto the given Instrumentation instance.
     */
    public void install(Instrumentation instrumentation) {
        AgentBuilder agentBuilder = new AgentBuilder.Default()
                .disableClassFormatChanges()
                .with(AgentBuilder.RedefinitionStrategy.RETRANSFORMATION)
                .with(new AgentBuilder.Listener.Adapter() {
                    @Override
                    public void onTransformation(TypeDescription typeDescription, ClassLoader classLoader,
                                                  JavaModule module, DynamicType dynamicType) {
                        log.info("Transformed class: {}", typeDescription.getName());
                    }

                    @Override
                    public void onError(String typeName, ClassLoader classLoader,
                                        JavaModule module, Throwable throwable) {
                        log.warn("Error transforming class {}: {}", typeName, throwable.getMessage());
                    }
                });

        for (InterceptorDefinition definition : registry.getAllDefinitions()) {
            ClassMatcher classMatcher = definition.getPointcut().getClassMatcher();
            net.bytebuddy.matcher.ElementMatcher.Junction<TypeDescription> typeMatcher = buildTypeMatcher(classMatcher);
            if (typeMatcher != null) {
                agentBuilder = agentBuilder
                        .type(typeMatcher)
                        .transform((builder, typeDescription, classLoader, module) ->
                                builder.visit(net.bytebuddy.asm.Advice.to(InterceptAdvice.class)
                                        .on(buildMethodMatcher(definition.getPointcut().getMethodMatcher())))
                        );
            }
        }

        agentBuilder.installOn(instrumentation);
        log.info("WeaverTransformer installed with {} interceptor definitions", registry.getAllDefinitions().size());
    }

    private net.bytebuddy.matcher.ElementMatcher.Junction<TypeDescription> buildTypeMatcher(ClassMatcher classMatcher) {
        switch (classMatcher.getMatchType()) {
            case EXACT_NAME:
                return named(classMatcher.getPattern());
            case NAME_PATTERN:
                return nameMatches(classMatcher.getPattern());
            case ANNOTATION:
                return isAnnotatedWith(named(classMatcher.getPattern()));
            case SUPER_CLASS:
                return isSubTypeOf(named(classMatcher.getPattern()));
            case INTERFACE:
                return implementsInterface(named(classMatcher.getPattern()));
            default:
                log.warn("Unsupported class match type: {}", classMatcher.getMatchType());
                return null;
        }
    }

    private net.bytebuddy.matcher.ElementMatcher.Junction<net.bytebuddy.description.method.MethodDescription> buildMethodMatcher(
            com.github.cc11001100.weavergirl.api.matcher.MethodMatcher methodMatcher) {
        switch (methodMatcher.getMatchType()) {
            case EXACT_NAME:
                return named(methodMatcher.getPattern());
            case NAME_PATTERN:
                return nameMatches(methodMatcher.getPattern());
            case ANY:
                return isMethod();
            default:
                return isMethod();
        }
    }
}
```

- [ ] **Step 4: 创建 PluginLoader — SPI 插件加载器**

```java
// weaver-girl-core/src/main/java/com/github/cc11001100/weavergirl/core/plugin/PluginLoader.java
package com.github.cc11001100.weavergirl.core.plugin;

import com.github.cc11001100.weavergirl.api.plugin.WeaverPlugin;
import com.github.cc11001100.weavergirl.api.registry.InterceptorRegistry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.List;
import java.util.ServiceLoader;

/**
 * Loads WeaverPlugin implementations via Java ServiceLoader mechanism.
 */
public class PluginLoader {

    private static final Logger log = LoggerFactory.getLogger(PluginLoader.class);

    /**
     * Load all plugins from the given ClassLoader and register their interceptors.
     */
    public List<WeaverPlugin> loadPlugins(ClassLoader classLoader, InterceptorRegistry registry) {
        List<WeaverPlugin> loaded = new ArrayList<>();
        ServiceLoader<WeaverPlugin> serviceLoader = ServiceLoader.load(WeaverPlugin.class, classLoader);

        for (WeaverPlugin plugin : serviceLoader) {
            try {
                log.info("Loading plugin: {}", plugin.name());
                plugin.registerInterceptors(registry);
                loaded.add(plugin);
                log.info("Plugin {} loaded successfully", plugin.name());
            } catch (Exception e) {
                log.error("Failed to load plugin {}: {}", plugin.name(), e.getMessage(), e);
            }
        }

        log.info("Loaded {} plugins total", loaded.size());
        return loaded;
    }
}
```

- [ ] **Step 5: 创建 AnnotationPluginLoader — 扫描注解并注册拦截器**

```java
// weaver-girl-core/src/main/java/com/github/cc11001100/weavergirl/core/plugin/AnnotationPluginLoader.java
package com.github.cc11001100.weavergirl.core.plugin;

import com.github.cc11001100.weavergirl.annotation.*;
import com.github.cc11001100.weavergirl.api.interceptor.Interceptor;
import com.github.cc11001100.weavergirl.api.interceptor.InterceptorDefinition;
import com.github.cc11001100.weavergirl.api.interceptor.MethodInvocation;
import com.github.cc11001100.weavergirl.api.matcher.ClassMatcher;
import com.github.cc11001100.weavergirl.api.matcher.MethodMatcher;
import com.github.cc11001100.weavergirl.api.pointcut.Pointcut;
import com.github.cc11001100.weavergirl.api.registry.InterceptorRegistry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.lang.reflect.Method;
import java.util.*;

/**
 * Scans classes for weaver-girl annotations and registers them as interceptor definitions.
 * Supports @WeaveClass with @Before, @After, and @Around method annotations.
 */
public class AnnotationPluginLoader {

    private static final Logger log = LoggerFactory.getLogger(AnnotationPluginLoader.class);

    /**
     * Scan a set of annotated classes and register their interceptors.
     */
    public void loadAnnotatedInterceptors(Set<Class<?>> annotatedClasses, InterceptorRegistry registry) {
        for (Class<?> clazz : annotatedClasses) {
            try {
                loadFromAnnotatedClass(clazz, registry);
            } catch (Exception e) {
                log.error("Failed to load annotated interceptor class {}: {}", clazz.getName(), e.getMessage(), e);
            }
        }
    }

    private void loadFromAnnotatedClass(Class<?> clazz, InterceptorRegistry registry) {
        WeaveClass weaveClass = clazz.getAnnotation(WeaveClass.class);
        if (weaveClass == null) {
            log.warn("Class {} has no @WeaveClass annotation, skipping", clazz.getName());
            return;
        }

        ClassMatcher classMatcher = weaveClass.targetPattern().isEmpty()
                ? ClassMatcher.byName(weaveClass.target())
                : ClassMatcher.byNamePattern(weaveClass.targetPattern());

        Object interceptorInstance;
        try {
            interceptorInstance = clazz.newInstance();
        } catch (InstantiationException | IllegalAccessException e) {
            log.error("Cannot instantiate interceptor class {}: {}", clazz.getName(), e.getMessage());
            return;
        }

        Map<String, List<Method>> beforeMethods = new HashMap<>();
        Map<String, List<Method>> afterMethods = new HashMap<>();
        Map<String, List<Method>> aroundMethods = new HashMap<>();

        for (Method m : clazz.getDeclaredMethods()) {
            if (m.isAnnotationPresent(Before.class)) {
                String targetMethod = m.getAnnotation(Before.class).value();
                beforeMethods.computeIfAbsent(targetMethod, k -> new ArrayList<>()).add(m);
            }
            if (m.isAnnotationPresent(After.class)) {
                String targetMethod = m.getAnnotation(After.class).value();
                afterMethods.computeIfAbsent(targetMethod, k -> new ArrayList<>()).add(m);
            }
            if (m.isAnnotationPresent(Around.class)) {
                String targetMethod = m.getAnnotation(Around.class).value();
                aroundMethods.computeIfAbsent(targetMethod, k -> new ArrayList<>()).add(m);
            }
        }

        Set<String> allTargetMethods = new HashSet<>();
        allTargetMethods.addAll(beforeMethods.keySet());
        allTargetMethods.addAll(afterMethods.keySet());
        allTargetMethods.addAll(aroundMethods.keySet());

        for (String targetMethod : allTargetMethods) {
            List<Method> befores = beforeMethods.getOrDefault(targetMethod, Collections.emptyList());
            List<Method> afters = afterMethods.getOrDefault(targetMethod, Collections.emptyList());
            List<Method> arounds = aroundMethods.getOrDefault(targetMethod, Collections.emptyList());

            MethodMatcher methodMatcher = MethodMatcher.byName(targetMethod);
            Interceptor interceptor = createReflectiveInterceptor(interceptorInstance, befores, afters, arounds);
            Pointcut pointcut = new Pointcut(classMatcher, methodMatcher);

            String defName = "annotation-" + clazz.getSimpleName() + "-" + targetMethod;
            InterceptorDefinition definition = new InterceptorDefinition(defName, pointcut, interceptor);
            registry.register(definition);
        }
    }

    private Interceptor createReflectiveInterceptor(Object instance,
                                                    List<Method> befores, List<Method> afters, List<Method> arounds) {
        return new Interceptor() {
            @Override
            public void before(MethodInvocation invocation) {
                invokeMethods(instance, arounds, invocation);
                invokeMethods(instance, befores, invocation);
            }

            @Override
            public void after(MethodInvocation invocation) {
                invokeMethods(instance, afters, invocation);
            }

            @Override
            public void onException(MethodInvocation invocation) {
                invokeMethods(instance, arounds, invocation);
            }

            private void invokeMethods(Object inst, List<Method> methods, MethodInvocation inv) {
                for (Method m : methods) {
                    try {
                        m.setAccessible(true);
                        m.invoke(inst, inv);
                    } catch (Exception e) {
                        log.warn("Error invoking interceptor method {}: {}", m.getName(), e.getMessage());
                    }
                }
            }
        };
    }
}
```

- [ ] **Step 6: 创建 WeaverConfig + YamlConfigLoader — YAML 配置引擎**

WeaverConfig:

```java
// weaver-girl-core/src/main/java/com/github/cc11001100/weavergirl/core/config/WeaverConfig.java
package com.github.cc11001100.weavergirl.core.config;

import java.util.ArrayList;
import java.util.List;

/**
 * Data model for weaver-girl YAML configuration.
 */
public class WeaverConfig {

    private List<InterceptorConfig> interceptors = new ArrayList<>();

    public List<InterceptorConfig> getInterceptors() {
        return interceptors;
    }

    public void setInterceptors(List<InterceptorConfig> interceptors) {
        this.interceptors = interceptors != null ? interceptors : new ArrayList<>();
    }

    public static class InterceptorConfig {
        private String className;
        private String classPattern;
        private String method;
        private String methodPattern;
        private String before;
        private String after;
        private String around;
        private int priority;

        public String getClassName() { return className; }
        public void setClassName(String className) { this.className = className; }

        public String getClassPattern() { return classPattern; }
        public void setClassPattern(String classPattern) { this.classPattern = classPattern; }

        public String getMethod() { return method; }
        public void setMethod(String method) { this.method = method; }

        public String getMethodPattern() { return methodPattern; }
        public void setMethodPattern(String methodPattern) { this.methodPattern = methodPattern; }

        public String getBefore() { return before; }
        public void setBefore(String before) { this.before = before; }

        public String getAfter() { return after; }
        public void setAfter(String after) { this.after = after; }

        public String getAround() { return around; }
        public void setAround(String around) { this.around = around; }

        public int getPriority() { return priority; }
        public void setPriority(int priority) { this.priority = priority; }
    }
}
```

YamlConfigLoader:

```java
// weaver-girl-core/src/main/java/com/github/cc11001100/weavergirl/core/config/YamlConfigLoader.java
package com.github.cc11001100.weavergirl.core.config;

import com.github.cc11001100.weavergirl.api.interceptor.Interceptor;
import com.github.cc11001100.weavergirl.api.interceptor.InterceptorDefinition;
import com.github.cc11001100.weavergirl.api.interceptor.MethodInvocation;
import com.github.cc11001100.weavergirl.api.matcher.ClassMatcher;
import com.github.cc11001100.weavergirl.api.matcher.MethodMatcher;
import com.github.cc11001100.weavergirl.api.pointcut.Pointcut;
import com.github.cc11001100.weavergirl.api.registry.InterceptorRegistry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.yaml.snakeyaml.Yaml;
import org.yaml.snakeyaml.error.YAMLException;

import java.io.*;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;

/**
 * Loads interceptor definitions from YAML configuration files.
 */
public class YamlConfigLoader {

    private static final Logger log = LoggerFactory.getLogger(YamlConfigLoader.class);

    public WeaverConfig loadFromFile(String filePath, InterceptorRegistry registry) {
        Path path = Paths.get(filePath);
        if (!Files.exists(path)) {
            log.warn("Configuration file not found: {}", filePath);
            return new WeaverConfig();
        }

        try (Reader reader = Files.newBufferedReader(path)) {
            return loadFromReader(reader, registry);
        } catch (IOException e) {
            log.error("Failed to read config file {}: {}", filePath, e.getMessage());
            return new WeaverConfig();
        }
    }

    public WeaverConfig loadFromReader(Reader reader, InterceptorRegistry registry) {
        try {
            Yaml yaml = new Yaml();
            WeaverConfig config = yaml.loadAs(reader, WeaverConfig.class);
            registerFromConfig(config, registry);
            return config;
        } catch (YAMLException e) {
            log.error("Failed to parse YAML config: {}", e.getMessage());
            return new WeaverConfig();
        }
    }

    private void registerFromConfig(WeaverConfig config, InterceptorRegistry registry) {
        if (config == null || config.getInterceptors() == null) {
            return;
        }

        for (WeaverConfig.InterceptorConfig ic : config.getInterceptors()) {
            try {
                registerInterceptorConfig(ic, registry);
            } catch (Exception e) {
                log.error("Failed to register interceptor for class {}: {}",
                        ic.getClassName(), e.getMessage());
            }
        }
    }

    private void registerInterceptorConfig(WeaverConfig.InterceptorConfig ic, InterceptorRegistry registry) {
        ClassMatcher classMatcher = ic.getClassPattern() != null && !ic.getClassPattern().isEmpty()
                ? ClassMatcher.byNamePattern(ic.getClassPattern())
                : ClassMatcher.byName(ic.getClassName());

        MethodMatcher methodMatcher = ic.getMethodPattern() != null && !ic.getMethodPattern().isEmpty()
                ? MethodMatcher.byNamePattern(ic.getMethodPattern())
                : (ic.getMethod() != null ? MethodMatcher.byName(ic.getMethod()) : MethodMatcher.any());

        Interceptor interceptor = buildInterceptor(ic);
        Pointcut pointcut = new Pointcut(classMatcher, methodMatcher);
        String name = "yaml-" + ic.getClassName() + "-" + (ic.getMethod() != null ? ic.getMethod() : "*");

        InterceptorDefinition definition = new InterceptorDefinition(name, pointcut, interceptor, ic.getPriority());
        registry.register(definition);
    }

    private Interceptor buildInterceptor(WeaverConfig.InterceptorConfig ic) {
        return new Interceptor() {
            @Override
            public void before(MethodInvocation invocation) {
                invokeAdviceClass(ic.getBefore(), invocation);
                invokeAdviceClass(ic.getAround(), invocation);
            }

            @Override
            public void after(MethodInvocation invocation) {
                invokeAdviceClass(ic.getAfter(), invocation);
            }

            @Override
            public void onException(MethodInvocation invocation) {
                invokeAdviceClass(ic.getAround(), invocation);
            }
        };
    }

    private void invokeAdviceClass(String adviceClassName, MethodInvocation invocation) {
        if (adviceClassName == null || adviceClassName.isEmpty()) {
            return;
        }
        try {
            Class<?> adviceClass = Class.forName(adviceClassName);
            Object instance = adviceClass.newInstance();
            if (instance instanceof Interceptor) {
                Interceptor advice = (Interceptor) instance;
                advice.before(invocation);
            }
        } catch (Exception e) {
            log.warn("Failed to invoke advice class {}: {}", adviceClassName, e.getMessage());
        }
    }
}
```

- [ ] **Step 7: 创建 WeaverGirl — 引导入口和 Fluent API**

```java
// weaver-girl-core/src/main/java/com/github/cc11001100/weavergirl/core/WeaverGirl.java
package com.github.cc11001100.weavergirl.core;

import com.github.cc11001100.weavergirl.api.interceptor.Interceptor;
import com.github.cc11001100.weavergirl.api.interceptor.InterceptorDefinition;
import com.github.cc11001100.weavergirl.api.interceptor.MethodInvocation;
import com.github.cc11001100.weavergirl.api.matcher.ClassMatcher;
import com.github.cc11001100.weavergirl.api.matcher.MethodMatcher;
import com.github.cc11001100.weavergirl.api.plugin.WeaverPlugin;
import com.github.cc11001100.weavergirl.api.pointcut.Pointcut;
import com.github.cc11001100.weavergirl.api.registry.InterceptorRegistry;
import com.github.cc11001100.weavergirl.core.plugin.PluginLoader;
import com.github.cc11001100.weavergirl.core.registry.DefaultInterceptorRegistry;
import com.github.cc11001100.weavergirl.core.transformer.WeaverTransformer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.lang.instrument.Instrumentation;
import java.util.List;
import java.util.function.Consumer;

/**
 * Main entry point for the weaver-girl framework.
 * Provides both a fluent programmatic API and internal bootstrap logic.
 */
public class WeaverGirl {

    private static final Logger log = LoggerFactory.getLogger(WeaverGirl.class);

    private final InterceptorRegistry registry;
    private Instrumentation instrumentation;

    private WeaverGirl() {
        this.registry = new DefaultInterceptorRegistry();
    }

    /**
     * Bootstrap the agent — called from premain/agentmain.
     * Loads plugins via SPI and installs the transformer.
     */
    public static WeaverGirl bootstrap(Instrumentation instrumentation) {
        log.info("WeaverGirl agent starting...");
        WeaverGirl weaverGirl = new WeaverGirl();
        weaverGirl.instrumentation = instrumentation;

        InterceptorHolder.setRegistry(weaverGirl.registry);

        PluginLoader pluginLoader = new PluginLoader();
        pluginLoader.loadPlugins(WeaverGirl.class.getClassLoader(), weaverGirl.registry);

        WeaverTransformer transformer = new WeaverTransformer(weaverGirl.registry);
        transformer.install(instrumentation);

        log.info("WeaverGirl agent started with {} interceptor definitions",
                weaverGirl.registry.getAllDefinitions().size());
        return weaverGirl;
    }

    /**
     * Create a new WeaverGirl instance for programmatic API usage.
     */
    public static WeaverGirl create() {
        return new WeaverGirl();
    }

    /**
     * Start a fluent interceptor definition for the given class name.
     */
    public InterceptBuilder intercept(String className) {
        return new InterceptBuilder(this, className);
    }

    public InterceptorRegistry getRegistry() {
        return registry;
    }

    /**
     * Fluent builder for programmatic interceptor registration.
     */
    public static class InterceptBuilder {
        private final WeaverGirl weaverGirl;
        private final String className;
        private String methodName = "*";
        private Interceptor interceptor;

        InterceptBuilder(WeaverGirl weaverGirl, String className) {
            this.weaverGirl = weaverGirl;
            this.className = className;
        }

        public InterceptBuilder method(String methodName) {
            this.methodName = methodName;
            return this;
        }

        public InterceptBuilder before(final Consumer<MethodInvocation> callback) {
            Interceptor existing = this.interceptor;
            this.interceptor = new Interceptor() {
                @Override
                public void before(MethodInvocation invocation) {
                    callback.accept(invocation);
                }

                @Override
                public void after(MethodInvocation invocation) {
                    if (existing != null) existing.after(invocation);
                }

                @Override
                public void onException(MethodInvocation invocation) {
                    if (existing != null) existing.onException(invocation);
                }
            };
            return this;
        }

        public InterceptBuilder after(final Consumer<MethodInvocation> callback) {
            Interceptor existing = this.interceptor;
            this.interceptor = new Interceptor() {
                @Override
                public void before(MethodInvocation invocation) {
                    if (existing != null) existing.before(invocation);
                }

                @Override
                public void after(MethodInvocation invocation) {
                    callback.accept(invocation);
                }

                @Override
                public void onException(MethodInvocation invocation) {
                    if (existing != null) existing.onException(invocation);
                }
            };
            return this;
        }

        public InterceptBuilder onException(final Consumer<MethodInvocation> callback) {
            Interceptor existing = this.interceptor;
            this.interceptor = new Interceptor() {
                @Override
                public void before(MethodInvocation invocation) {
                    if (existing != null) existing.before(invocation);
                }

                @Override
                public void after(MethodInvocation invocation) {
                    if (existing != null) existing.after(invocation);
                }

                @Override
                public void onException(MethodInvocation invocation) {
                    callback.accept(invocation);
                }
            };
            return this;
        }

        public WeaverGirl install() {
            ClassMatcher classMatcher = ClassMatcher.byName(className);
            MethodMatcher methodMatcher = "*".equals(methodName)
                    ? MethodMatcher.any() : MethodMatcher.byName(methodName);
            Pointcut pointcut = new Pointcut(classMatcher, methodMatcher);
            if (interceptor == null) {
                interceptor = new Interceptor() {};
            }
            InterceptorDefinition definition = new InterceptorDefinition(
                    "programmatic-" + className + "-" + methodName, pointcut, interceptor);
            weaverGirl.registry.register(definition);
            return weaverGirl;
        }
    }
}
```

- [ ] **Step 8: 创建 Core 模块单元测试**

DefaultInterceptorRegistryTest:

```java
// weaver-girl-core/src/test/java/com/github/cc11001100/weavergirl/core/registry/DefaultInterceptorRegistryTest.java
package com.github.cc11001100.weavergirl.core.registry;

import com.github.cc11001100.weavergirl.api.interceptor.Interceptor;
import com.github.cc11001100.weavergirl.api.interceptor.InterceptorDefinition;
import com.github.cc11001100.weavergirl.api.matcher.ClassMatcher;
import com.github.cc11001100.weavergirl.api.matcher.MethodMatcher;
import com.github.cc11001100.weavergirl.api.pointcut.Pointcut;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class DefaultInterceptorRegistryTest {

    private DefaultInterceptorRegistry registry;

    @BeforeEach
    void setUp() {
        registry = new DefaultInterceptorRegistry();
    }

    @Test
    void register_andLookupByClassName_returnsMatchingDefinition() {
        InterceptorDefinition def = createDefinition("test", "com.example.TargetService", "doWork");
        registry.register(def);

        List<InterceptorDefinition> result = registry.getInterceptorsForClass("com.example.TargetService");
        assertEquals(1, result.size());
        assertEquals("test", result.get(0).getName());
    }

    @Test
    void register_nullDefinition_ignored() {
        registry.register(null);
        assertEquals(0, registry.getAllDefinitions().size());
    }

    @Test
    void getInterceptorsForClass_noMatch_returnsEmptyList() {
        InterceptorDefinition def = createDefinition("test", "com.example.TargetService", "doWork");
        registry.register(def);

        List<InterceptorDefinition> result = registry.getInterceptorsForClass("com.example.OtherService");
        assertTrue(result.isEmpty());
    }

    @Test
    void register_multipleDefinitions_sortedByPriority() {
        InterceptorDefinition low = createDefinition("low", "com.example.Svc", "run", 10);
        InterceptorDefinition high = createDefinition("high", "com.example.Svc", "run", 1);
        registry.register(low);
        registry.register(high);

        List<InterceptorDefinition> result = registry.getInterceptorsForClass("com.example.Svc");
        assertEquals(2, result.size());
        assertEquals("high", result.get(0).getName());
        assertEquals("low", result.get(1).getName());
    }

    @Test
    void clear_removesAllDefinitions() {
        registry.register(createDefinition("test", "com.example.Svc", "run"));
        registry.clear();
        assertEquals(0, registry.getAllDefinitions().size());
    }

    private InterceptorDefinition createDefinition(String name, String className, String methodName) {
        return createDefinition(name, className, methodName, 0);
    }

    private InterceptorDefinition createDefinition(String name, String className, String methodName, int priority) {
        Pointcut pointcut = new Pointcut(ClassMatcher.byName(className), MethodMatcher.byName(methodName));
        Interceptor interceptor = new Interceptor() {};
        return new InterceptorDefinition(name, pointcut, interceptor, priority);
    }
}
```

PluginLoaderTest:

```java
// weaver-girl-core/src/test/java/com/github/cc11001100/weavergirl/core/plugin/PluginLoaderTest.java
package com.github.cc11001100.weavergirl.core.plugin;

import com.github.cc11001100.weavergirl.api.interceptor.InterceptorDefinition;
import com.github.cc11001100.weavergirl.api.plugin.AbstractPlugin;
import com.github.cc11001100.weavergirl.api.plugin.WeaverPlugin;
import com.github.cc11001100.weavergirl.api.registry.InterceptorRegistry;
import com.github.cc11001100.weavergirl.core.registry.DefaultInterceptorRegistry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class PluginLoaderTest {

    private InterceptorRegistry registry;
    private PluginLoader loader;

    @BeforeEach
    void setUp() {
        registry = new DefaultInterceptorRegistry();
        loader = new PluginLoader();
    }

    @Test
    void loadPlugins_withNoPlugins_returnsEmptyList() {
        List<WeaverPlugin> plugins = loader.loadPlugins(getClass().getClassLoader(), registry);
        assertNotNull(plugins);
    }

    @Test
    void loadPlugins_pluginThrowsException_doesNotCrash() {
        WeaverPlugin brokenPlugin = new WeaverPlugin() {
            @Override
            public String name() {
                return "broken";
            }

            @Override
            public void registerInterceptors(InterceptorRegistry reg) {
                throw new RuntimeException("Plugin init failed");
            }
        };
        assertThrows(RuntimeException.class, () -> brokenPlugin.registerInterceptors(registry));
    }

    @Test
    void abstractPlugin_intercept_buildsCorrectDefinition() {
        AbstractPlugin plugin = new AbstractPlugin() {
            @Override
            public String name() {
                return "test-plugin";
            }

            @Override
            public void registerInterceptors(InterceptorRegistry reg) {
                InterceptorDefinition def = intercept("com.example.Service")
                        .method("process")
                        .before(inv -> {})
                        .build();
                reg.register(def);
            }
        };

        plugin.registerInterceptors(registry);
        List<InterceptorDefinition> defs = registry.getInterceptorsForClass("com.example.Service");
        assertEquals(1, defs.size());
    }
}
```

YamlConfigLoaderTest:

```java
// weaver-girl-core/src/test/java/com/github/cc11001100/weavergirl/core/config/YamlConfigLoaderTest.java
package com.github.cc11001100.weavergirl.core.config;

import com.github.cc11001100.weavergirl.api.interceptor.InterceptorDefinition;
import com.github.cc11001100.weavergirl.api.registry.InterceptorRegistry;
import com.github.cc11001100.weavergirl.core.registry.DefaultInterceptorRegistry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.io.StringReader;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class YamlConfigLoaderTest {

    private InterceptorRegistry registry;
    private YamlConfigLoader loader;

    @BeforeEach
    void setUp() {
        registry = new DefaultInterceptorRegistry();
        loader = new YamlConfigLoader();
    }

    @Test
    void loadFromReader_validYaml_registersInterceptors() {
        String yaml = "interceptors:\n" +
                "  - className: \"com.example.UserService\"\n" +
                "    method: \"createUser\"\n" +
                "    before: \"com.example.TestInterceptor\"\n";

        WeaverConfig config = loader.loadFromReader(new StringReader(yaml), registry);
        assertNotNull(config);
        assertEquals(1, config.getInterceptors().size());
        assertEquals("com.example.UserService", config.getInterceptors().get(0).getClassName());

        List<InterceptorDefinition> defs = registry.getInterceptorsForClass("com.example.UserService");
        assertEquals(1, defs.size());
    }

    @Test
    void loadFromReader_multipleInterceptors_registersAll() {
        String yaml = "interceptors:\n" +
                "  - className: \"com.example.ServiceA\"\n" +
                "    method: \"doWork\"\n" +
                "  - className: \"com.example.ServiceB\"\n" +
                "    methodPattern: \"process.*\"\n";

        loader.loadFromReader(new StringReader(yaml), registry);
        assertEquals(2, registry.getAllDefinitions().size());
    }

    @Test
    void loadFromReader_invalidYaml_returnsEmptyConfig() {
        String yaml = "not: valid: yaml: {{{";
        WeaverConfig config = loader.loadFromReader(new StringReader(yaml), registry);
        assertNotNull(config);
    }

    @Test
    void loadFromReader_emptyYaml_returnsEmptyConfig() {
        String yaml = "";
        WeaverConfig config = loader.loadFromReader(new StringReader(yaml), registry);
        assertNotNull(config);
        assertTrue(config.getInterceptors().isEmpty());
    }

    @Test
    void loadFromFile_nonExistentFile_returnsEmptyConfig() {
        WeaverConfig config = loader.loadFromFile("/nonexistent/path.yml", registry);
        assertNotNull(config);
        assertTrue(config.getInterceptors().isEmpty());
    }
}
```

- [ ] **Step 9: 验证 Core Engine 编译和测试**
Run: `cd /home/cc11001100/github/weaver-girl/weaver-girl && mvn test -pl weaver-girl-core -q`
Expected:
  - Exit code: 0
  - Output contains: "BUILD SUCCESS"

- [ ] **Step 10: 提交**
Run: `git add weaver-girl-core/src && git commit -m "feat(core): add core engine — DefaultInterceptorRegistry, InterceptAdvice, WeaverTransformer, PluginLoader, YamlConfigLoader, WeaverGirl bootstrap"`

---

### Task 5: Agent Assembly — premain/agentmain 入口和打包

**Depends on:** Task 4
**Files:**
- Create: `weaver-girl-agent/src/main/java/com/github/cc11001100/weavergirl/agent/WeaverGirlAgent.java`

- [ ] **Step 1: 创建 WeaverGirlAgent — Agent 入口类（premain + agentmain）**

```java
// weaver-girl-agent/src/main/java/com/github/cc11001100/weavergirl/agent/WeaverGirlAgent.java
package com.github.cc11001100.weavergirl.agent;

import com.github.cc11001100.weavergirl.core.WeaverGirl;
import com.github.cc11001100.weavergirl.core.config.YamlConfigLoader;

import java.lang.instrument.Instrumentation;

/**
 * Java Agent entry point.
 * Supports both premain (startup-time) and agentmain (runtime attach) modes.
 *
 * <p>Usage:</p>
 * <pre>
 *   java -javaagent:weaver-girl-agent.jar -jar app.jar
 *   java -javaagent:weaver-girl-agent.jar=config=/path/to/weaver.yml -jar app.jar
 * </pre>
 */
public class WeaverGirlAgent {

    private static final String CONFIG_PREFIX = "config=";

    /**
     * Premain entry — called before application main() when using -javaagent flag.
     */
    public static void premain(String agentArgs, Instrumentation instrumentation) {
        init(agentArgs, instrumentation);
    }

    /**
     * Agentmain entry — called when dynamically attaching to a running JVM.
     */
    public static void agentmain(String agentArgs, Instrumentation instrumentation) {
        init(agentArgs, instrumentation);
    }

    private static void init(String agentArgs, Instrumentation instrumentation) {
        System.out.println("[weaver-girl] Agent initializing...");

        WeaverGirl weaverGirl = WeaverGirl.bootstrap(instrumentation);

        // Load YAML config if specified via agent arguments
        if (agentArgs != null && !agentArgs.isEmpty()) {
            String configPath = parseConfigPath(agentArgs);
            if (configPath != null) {
                YamlConfigLoader configLoader = new YamlConfigLoader();
                configLoader.loadFromFile(configPath, weaverGirl.getRegistry());
            }
        }

        System.out.println("[weaver-girl] Agent initialized with " +
                weaverGirl.getRegistry().getAllDefinitions().size() + " interceptor definitions");
    }

    private static String parseConfigPath(String agentArgs) {
        if (agentArgs.startsWith(CONFIG_PREFIX)) {
            return agentArgs.substring(CONFIG_PREFIX.length()).trim();
        }
        if (!agentArgs.isEmpty() && (agentArgs.endsWith(".yml") || agentArgs.endsWith(".yaml"))) {
            return agentArgs;
        }
        return null;
    }
}
```

- [ ] **Step 2: 验证 Agent 模块编译和打包**
Run: `cd /home/cc11001100/github/weaver-girl/weaver-girl && mvn package -pl weaver-girl-agent -DskipTests -q`
Expected:
  - Exit code: 0
  - Output contains: "BUILD SUCCESS"
  - File exists: `weaver-girl-agent/target/weaver-girl-agent-1.0.0-SNAPSHOT.jar`

- [ ] **Step 3: 提交**
Run: `git add weaver-girl-agent/src && git commit -m "feat(agent): add premain/agentmain entry point with YAML config loading"`

---

### Task 6: Sample Application — 三种 Hook 模式演示

**Depends on:** Task 5
**Files:**
- Create: `weaver-girl-sample/src/main/java/com/github/cc11001100/weavergirl/sample/app/SampleApplication.java`
- Create: `weaver-girl-sample/src/main/java/com/github/cc11001100/weavergirl/sample/app/TargetService.java`
- Create: `weaver-girl-sample/src/main/java/com/github/cc11001100/weavergirl/sample/plugin/LoggingPlugin.java`
- Create: `weaver-girl-sample/src/main/java/com/github/cc11001100/weavergirl/sample/interceptor/AnnotationInterceptor.java`
- Create: `weaver-girl-sample/src/main/java/com/github/cc11001100/weavergirl/sample/interceptor/SampleYamlInterceptor.java`
- Create: `weaver-girl-sample/src/main/resources/META-INF/services/com.github.cc11001100.weavergirl.api.plugin.WeaverPlugin`
- Create: `weaver-girl-sample/src/main/resources/weaver-sample.yml`
- Test: `weaver-girl-sample/src/test/java/com/github/cc11001100/weavergirl/sample/app/SampleApplicationTest.java`

**设计原则：** sample 模块演示如何使用 weaver-girl 作为底层库构建自定义 Agent。重点是展示插件开发者只需依赖 `weaver-girl-api`。

- [ ] **Step 1: 创建 TargetService — 被拦截的示例业务类**

```java
// weaver-girl-sample/src/main/java/com/github/cc11001100/weavergirl/sample/app/TargetService.java
package com.github.cc11001100.weavergirl.sample.app;

/**
 * Sample target service class that will be intercepted by the agent.
 */
public class TargetService {

    public String greet(String name) {
        return "Hello, " + name + "!";
    }

    public int calculate(int a, int b) {
        return a + b;
    }

    public void riskyOperation() {
        throw new RuntimeException("Something went wrong!");
    }
}
```

- [ ] **Step 2: 创建 LoggingPlugin — 编程式 Plugin 示例（只依赖 api 层）**

```java
// weaver-girl-sample/src/main/java/com/github/cc11001100/weavergirl/sample/plugin/LoggingPlugin.java
package com.github.cc11001100.weavergirl.sample.plugin;

import com.github.cc11001100.weavergirl.api.interceptor.InterceptorDefinition;
import com.github.cc11001100.weavergirl.api.plugin.AbstractPlugin;
import com.github.cc11001100.weavergirl.api.registry.InterceptorRegistry;

/**
 * Sample plugin using the programmatic API.
 * Demonstrates that plugin developers only need to depend on weaver-girl-api.
 */
public class LoggingPlugin extends AbstractPlugin {

    @Override
    public String name() {
        return "logging-plugin";
    }

    @Override
    public void registerInterceptors(InterceptorRegistry registry) {
        InterceptorDefinition greetDef = intercept(
                "com.github.cc11001100.weavergirl.sample.app.TargetService")
                .method("greet")
                .around(
                        inv -> System.out.println("[LOG] Before greet: " + java.util.Arrays.toString(inv.getArguments())),
                        inv -> System.out.println("[LOG] After greet: returned " + inv.getReturnValue())
                )
                .build();
        registry.register(greetDef);

        InterceptorDefinition calcDef = intercept(
                "com.github.cc11001100.weavergirl.sample.app.TargetService")
                .method("calculate")
                .around(
                        inv -> System.out.println("[LOG] Before calculate: " + inv.getArgument(0) + " + " + inv.getArgument(1)),
                        inv -> System.out.println("[LOG] After calculate: result = " + inv.getReturnValue())
                )
                .build();
        registry.register(calcDef);

        InterceptorDefinition riskyDef = intercept(
                "com.github.cc11001100.weavergirl.sample.app.TargetService")
                .method("riskyOperation")
                .around(
                        inv -> System.out.println("[LOG] Before riskyOperation"),
                        inv -> {
                            if (inv.hasException()) {
                                System.out.println("[LOG] riskyOperation threw: " + inv.getThrowable().getMessage());
                            }
                        }
                )
                .build();
        registry.register(riskyDef);
    }
}
```

- [ ] **Step 3: 创建 AnnotationInterceptor — 注解式拦截器示例**

```java
// weaver-girl-sample/src/main/java/com/github/cc11001100/weavergirl/sample/interceptor/AnnotationInterceptor.java
package com.github.cc11001100.weavergirl.sample.interceptor;

import com.github.cc11001100.weavergirl.annotation.After;
import com.github.cc11001100.weavergirl.annotation.Before;
import com.github.cc11001100.weavergirl.annotation.WeaveClass;
import com.github.cc11001100.weavergirl.api.interceptor.MethodInvocation;

/**
 * Sample annotation-based interceptor.
 * Demonstrates declarative hook definition using @WeaveClass + @Before/@After.
 */
@WeaveClass(target = "com.github.cc11001100.weavergirl.sample.app.TargetService")
public class AnnotationInterceptor {

    @Before("greet")
    public void beforeGreet(MethodInvocation invocation) {
        System.out.println("[ANNOTATION] Before greet: " + java.util.Arrays.toString(invocation.getArguments()));
    }

    @After("greet")
    public void afterGreet(MethodInvocation invocation) {
        System.out.println("[ANNOTATION] After greet: " + invocation.getReturnValue());
    }

    @Before("calculate")
    public void beforeCalculate(MethodInvocation invocation) {
        System.out.println("[ANNOTATION] Before calculate");
    }
}
```

- [ ] **Step 4: 创建 SampleYamlInterceptor + SPI 声明 + YAML 配置**

SampleYamlInterceptor:

```java
// weaver-girl-sample/src/main/java/com/github/cc11001100/weavergirl/sample/interceptor/SampleYamlInterceptor.java
package com.github.cc11001100.weavergirl.sample.interceptor;

import com.github.cc11001100.weavergirl.api.interceptor.Interceptor;
import com.github.cc11001100.weavergirl.api.interceptor.MethodInvocation;

/**
 * Sample Interceptor implementation referenced by YAML config.
 */
public class SampleYamlInterceptor implements Interceptor {

    @Override
    public void before(MethodInvocation invocation) {
        System.out.println("[YAML] Before " + invocation.getMethodName()
                + " args=" + java.util.Arrays.toString(invocation.getArguments()));
    }

    @Override
    public void after(MethodInvocation invocation) {
        System.out.println("[YAML] After " + invocation.getMethodName()
                + " returned=" + invocation.getReturnValue());
    }
}
```

SPI 服务声明:

文件: `weaver-girl-sample/src/main/resources/META-INF/services/com.github.cc11001100.weavergirl.api.plugin.WeaverPlugin`

```text
com.github.cc11001100.weavergirl.sample.plugin.LoggingPlugin
```

YAML 配置示例:

文件: `weaver-girl-sample/src/main/resources/weaver-sample.yml`

```yaml
# Example YAML-based hook configuration
interceptors:
  - className: "com.github.cc11001100.weavergirl.sample.app.TargetService"
    method: "greet"
    before: "com.github.cc11001100.weavergirl.sample.interceptor.SampleYamlInterceptor"
  - className: "com.github.cc11001100.weavergirl.sample.app.TargetService"
    methodPattern: "calc.*"
    after: "com.github.cc11001100.weavergirl.sample.interceptor.SampleYamlInterceptor"
```

- [ ] **Step 5: 创建 SampleApplication 启动类及单元测试**

SampleApplication:

```java
// weaver-girl-sample/src/main/java/com/github/cc11001100/weavergirl/sample/app/SampleApplication.java
package com.github.cc11001100.weavergirl.sample.app;

/**
 * Sample application demonstrating weaver-girl agent usage.
 */
public class SampleApplication {

    public static void main(String[] args) {
        System.out.println("=== Weaver-Girl Sample Application ===\n");

        TargetService service = new TargetService();

        System.out.println("--- Testing greet ---");
        String greeting = service.greet("World");
        System.out.println("Result: " + greeting);

        System.out.println("\n--- Testing calculate ---");
        int result = service.calculate(3, 4);
        System.out.println("Result: " + result);

        System.out.println("\n--- Testing riskyOperation ---");
        try {
            service.riskyOperation();
        } catch (RuntimeException e) {
            System.out.println("Caught: " + e.getMessage());
        }

        System.out.println("\n=== Sample Application Complete ===");
    }
}
```

SampleApplicationTest:

```java
// weaver-girl-sample/src/test/java/com/github/cc11001100/weavergirl/sample/app/SampleApplicationTest.java
package com.github.cc11001100.weavergirl.sample.app;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class SampleApplicationTest {

    @Test
    void targetService_greet_returnsCorrectGreeting() {
        TargetService service = new TargetService();
        assertEquals("Hello, World!", service.greet("World"));
    }

    @Test
    void targetService_greet_emptyName_returnsHelloWithEmpty() {
        TargetService service = new TargetService();
        assertEquals("Hello, !", service.greet(""));
    }

    @Test
    void targetService_calculate_returnsSum() {
        TargetService service = new TargetService();
        assertEquals(7, service.calculate(3, 4));
        assertEquals(0, service.calculate(0, 0));
        assertEquals(-1, service.calculate(-5, 4));
    }

    @Test
    void targetService_riskyOperation_throwsRuntimeException() {
        TargetService service = new TargetService();
        RuntimeException exception = assertThrows(RuntimeException.class, service::riskyOperation);
        assertEquals("Something went wrong!", exception.getMessage());
    }
}
```

- [ ] **Step 6: 验证全部模块编译和测试通过**
Run: `cd /home/cc11001100/github/weaver-girl/weaver-girl && mvn test -q`
Expected:
  - Exit code: 0
  - Output contains: "BUILD SUCCESS"

- [ ] **Step 7: 验证 Agent JAR 打包**
Run: `cd /home/cc11001100/github/weaver-girl/weaver-girl && mvn package -DskipTests -q && java -jar weaver-girl-agent/target/weaver-girl-agent-1.0.0-SNAPSHOT.jar 2>&1 || true`
Expected:
  - File exists: `weaver-girl-agent/target/weaver-girl-agent-1.0.0-SNAPSHOT.jar`
  - JAR is a valid agent (contains Premain-Class in manifest)

- [ ] **Step 8: 提交**
Run: `git add weaver-girl-sample/src && git commit -m "feat(sample): add sample application demonstrating all three hook modes (programmatic, annotation, YAML)"`
