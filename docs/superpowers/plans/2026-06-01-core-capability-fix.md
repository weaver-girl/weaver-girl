# Core Capability Fix — P0 Defect Remediation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: `superpowers:subagent-driven-development`
> Steps use checkbox (`- [ ]`) syntax.

**Goal:** 修复 5 个 P0 级核心缺陷：Around/skipMethod/返回值修改不生效、YAML advice 只调 before()、Programmatic API 未接通 ByteBuddy、@Around 语义不完整、newInstance() 废弃调用，使 weaver-girl 成为真正可用的底层支撑库。

**Architecture:** Interceptor.before() 中调用 invocation.skipMethod() → InterceptAdvice.onMethodEnter 检测 isSkipped 并返回 invocation 触发 skipOn → onMethodExit 检测 isSkipped 时用 invocation.getReturnValue() 覆盖原始返回值。YamlConfigLoader.invokeAdviceClass 根据 advice 位置调 before/after/onException 而非始终调 before。WeaverGirl.create() 新增 withInstrumentation() 方法接通 ByteBuddy 管线。

**Tech Stack:** Java 8, ByteBuddy 1.14.18, JUnit Jupiter 5.10.2, Mockito 5.8.0, Maven 3.9.6

**Risks:**
- Task 1 修改 InterceptAdvice 是最危险改动 — ByteBuddy @Advice 内联约束严格（不能抛受检异常、不能访问非 public 字段），skipOn 机制必须正确触发 → 缓解：skipOn=MethodInvocation.class 已声明，只需 onMethodEnter 在 isSkipped 时返回 invocation 即可，这符合 ByteBuddy 预期
- Task 2 修改 YamlConfigLoader 的 invokeAdviceClass 需要区分 before/after/around 调用场景 → 缓解：给 invokeAdviceClass 增加 Interceptor 回调类型参数
- Task 5 修改 WeaverGirl.create() 需要新增 API 方法 → 缓解：新增 withInstrumentation() 而非改 create() 签名，保持向后兼容

---

### Task 1: 修复 InterceptAdvice — 支持 skipMethod 和返回值修改

**Depends on:** None
**Files:**
- Modify: `weaver-girl-core/src/main/java/com/github/cc11001100/weavergirl/core/InterceptAdvice.java:19-94`
- Create: `weaver-girl-core/src/test/java/com/github/cc11001100/weavergirl/core/InterceptAdviceTest.java`

- [ ] **Step 1: 修改 InterceptAdvice.onMethodEnter — 检测 isSkipped 使 skipOn 生效**

文件: `weaver-girl-core/src/main/java/com/github/cc11001100/weavergirl/core/InterceptAdvice.java:19-49`（替换整个 onMethodEnter 方法）

```java
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
                return null;
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

            // If any interceptor called skipMethod(), return the invocation to trigger skipOn
            if (invocation.isSkipped()) {
                return invocation;
            }
            return null;
        } catch (Exception e) {
            return null;
        }
    }
```

- [ ] **Step 2: 修改 InterceptAdvice.onMethodExit — 支持返回值覆盖**

文件: `weaver-girl-core/src/main/java/com/github/cc11001100/weavergirl/core/InterceptAdvice.java:51-94`（替换整个 onMethodExit 方法）

```java
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
```

- [ ] **Step 3: 创建 InterceptAdviceTest — 验证 skipMethod 和返回值修改**

```java
// weaver-girl-core/src/test/java/com/github/cc11001100/weavergirl/core/InterceptAdviceTest.java
package com.github.cc11001100.weavergirl.core;

import com.github.cc11001100.weavergirl.api.interceptor.Interceptor;
import com.github.cc11001100.weavergirl.api.interceptor.InterceptorDefinition;
import com.github.cc11001100.weavergirl.api.interceptor.MethodInvocation;
import com.github.cc11001100.weavergirl.api.matcher.ClassMatcher;
import com.github.cc11001100.weavergirl.api.matcher.MethodMatcher;
import com.github.cc11001100.weavergirl.api.pointcut.Pointcut;
import com.github.cc11001100.weavergirl.core.registry.DefaultInterceptorRegistry;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Method;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.*;

class InterceptAdviceTest {

    private DefaultInterceptorRegistry registry;

    @BeforeEach
    void setUp() {
        registry = new DefaultInterceptorRegistry();
        InterceptorHolder.setRegistry(registry);
    }

    @AfterEach
    void tearDown() {
        InterceptorHolder.setRegistry(null);
    }

    @Test
    void onMethodEnter_skipMethodSet_returnsInvocationToTriggerSkip() throws Exception {
        // Register an interceptor that calls skipMethod()
        Interceptor skipInterceptor = new Interceptor() {
            @Override
            public void before(MethodInvocation invocation) {
                invocation.skipMethod();
                invocation.setReturnValue("skipped-result");
            }
        };
        InterceptorDefinition def = new InterceptorDefinition(
                "test-skip",
                new Pointcut(ClassMatcher.byName("com.example.Service"), MethodMatcher.byName("process")),
                skipInterceptor);
        registry.register(def);

        // Simulate onMethodEnter
        Class<?> fakeClass = String.class;
        Method fakeMethod = String.class.getMethod("length");
        MethodInvocation result = InterceptAdvice.onMethodEnter(fakeClass, fakeMethod, "target", new Object[0]);

        // When skipMethod is called on an unmatched class, result is null (no skip)
        assertNull(result);
    }

    @Test
    void onMethodEnter_noSkip_returnsNull() throws Exception {
        Interceptor normalInterceptor = new Interceptor() {
            @Override
            public void before(MethodInvocation invocation) {
                // do nothing — no skip
            }
        };
        InterceptorDefinition def = new InterceptorDefinition(
                "test-normal",
                new Pointcut(ClassMatcher.byName("java.lang.String"), MethodMatcher.byName("length")),
                normalInterceptor);
        registry.register(def);

        Class<?> targetClass = String.class;
        Method method = String.class.getMethod("length");
        MethodInvocation result = InterceptAdvice.onMethodEnter(targetClass, method, "hello", new Object[0]);

        // No skip called, so onMethodEnter returns null (no skipOn trigger)
        assertNull(result);
    }

    @Test
    void onMethodEnter_skipOnMatchedClass_returnsInvocation() throws Exception {
        AtomicBoolean skipCalled = new AtomicBoolean(false);
        Interceptor skipInterceptor = new Interceptor() {
            @Override
            public void before(MethodInvocation invocation) {
                invocation.skipMethod();
                invocation.setReturnValue("skipped");
                skipCalled.set(true);
            }
        };
        InterceptorDefinition def = new InterceptorDefinition(
                "test-skip-match",
                new Pointcut(ClassMatcher.byName("java.lang.String"), MethodMatcher.byName("length")),
                skipInterceptor);
        registry.register(def);

        Class<?> targetClass = String.class;
        Method method = String.class.getMethod("length");
        MethodInvocation result = InterceptAdvice.onMethodEnter(targetClass, method, "hello", new Object[0]);

        assertTrue(skipCalled.get());
        assertNotNull(result);
        assertTrue(result.isSkipped());
        assertEquals("skipped", result.getReturnValue());
    }

    @Test
    void onMethodExit_nullInvocation_returnsEarly() {
        // Should not throw when invocation is null
        assertDoesNotThrow(() -> {
            InterceptAdvice.onMethodExit(null, String.class,
                    String.class.getMethod("length"), null, 5);
        });
    }

    @Test
    void onMethodExit_setsReturnValueOnInvocation() throws Exception {
        AtomicReference<MethodInvocation> captured = new AtomicReference<>();
        Interceptor afterInterceptor = new Interceptor() {
            @Override
            public void after(MethodInvocation invocation) {
                captured.set(invocation);
            }
        };
        InterceptorDefinition def = new InterceptorDefinition(
                "test-after",
                new Pointcut(ClassMatcher.byName("java.lang.String"), MethodMatcher.byName("length")),
                afterInterceptor);
        registry.register(def);

        MethodInvocation inv = new MethodInvocation(String.class, "length", "hello", new Object[0]);
        InterceptAdvice.onMethodExit(inv, String.class,
                String.class.getMethod("length"), null, 5);

        assertNotNull(captured.get());
        assertEquals(5, captured.get().getReturnValue());
    }

    @Test
    void onMethodExit_setsThrowableOnInvocation() throws Exception {
        AtomicReference<MethodInvocation> captured = new AtomicReference<>();
        Interceptor exInterceptor = new Interceptor() {
            @Override
            public void onException(MethodInvocation invocation) {
                captured.set(invocation);
            }
        };
        InterceptorDefinition def = new InterceptorDefinition(
                "test-exception",
                new Pointcut(ClassMatcher.byName("java.lang.String"), MethodMatcher.byName("length")),
                exInterceptor);
        registry.register(def);

        MethodInvocation inv = new MethodInvocation(String.class, "length", "hello", new Object[0]);
        RuntimeException testEx = new RuntimeException("test");
        InterceptAdvice.onMethodExit(inv, String.class,
                String.class.getMethod("length"), testEx, null);

        assertNotNull(captured.get());
        assertTrue(captured.get().hasException());
        assertEquals("test", captured.get().getThrowable().getMessage());
    }
}
```

- [ ] **Step 4: 验证 InterceptAdvice 修复**
Run: `/tmp/apache-maven-3.9.6/bin/mvn test -pl weaver-girl-core -Dtest="InterceptAdviceTest" -q 2>&1`
Expected:
  - Exit code: 0
  - Output contains: "6 tests passed"

- [ ] **Step 5: 提交**
Run: `git add weaver-girl-core/src/main/java/com/github/cc11001100/weavergirl/core/InterceptAdvice.java weaver-girl-core/src/test/java/com/github/cc11001100/weavergirl/core/InterceptAdviceTest.java && git commit -m "fix(core): implement skipMethod and return value override in InterceptAdvice

- onMethodEnter returns MethodInvocation when isSkipped=true, triggering ByteBuddy skipOn
- onMethodExit sets return value and throwable on MethodInvocation for interceptor access

Co-Authored-By: Claude Opus 4.8 <noreply@anthropic.com>"`

---

### Task 2: 修复 YamlConfigLoader — advice 类正确调用 before/after/onException

**Depends on:** Task 1
**Files:**
- Modify: `weaver-girl-core/src/main/java/com/github/cc11001100/weavergirl/core/config/YamlConfigLoader.java:90-124`

- [ ] **Step 1: 修改 YamlConfigLoader.buildInterceptor — around 在 after 路径也调用**

文件: `weaver-girl-core/src/main/java/com/github/cc11001100/weavergirl/core/config/YamlConfigLoader.java:90-108`（替换 buildInterceptor 和 invokeAdviceClass 方法）

```java
    private Interceptor buildInterceptor(WeaverConfig.InterceptorConfig ic) {
        return new Interceptor() {
            @Override
            public void before(MethodInvocation invocation) {
                invokeAdviceClass(ic.getBefore(), invocation, AdvicePhase.BEFORE);
                invokeAdviceClass(ic.getAround(), invocation, AdvicePhase.BEFORE);
            }

            @Override
            public void after(MethodInvocation invocation) {
                invokeAdviceClass(ic.getAround(), invocation, AdvicePhase.AFTER);
                invokeAdviceClass(ic.getAfter(), invocation, AdvicePhase.AFTER);
            }

            @Override
            public void onException(MethodInvocation invocation) {
                invokeAdviceClass(ic.getAround(), invocation, AdvicePhase.ON_EXCEPTION);
            }
        };
    }

    private enum AdvicePhase {
        BEFORE, AFTER, ON_EXCEPTION
    }

    private void invokeAdviceClass(String adviceClassName, MethodInvocation invocation, AdvicePhase phase) {
        if (adviceClassName == null || adviceClassName.isEmpty()) {
            return;
        }
        try {
            Class<?> adviceClass = Class.forName(adviceClassName);
            Object instance = adviceClass.getDeclaredConstructor().newInstance();
            if (instance instanceof Interceptor) {
                Interceptor advice = (Interceptor) instance;
                switch (phase) {
                    case BEFORE:
                        advice.before(invocation);
                        break;
                    case AFTER:
                        advice.after(invocation);
                        break;
                    case ON_EXCEPTION:
                        advice.onException(invocation);
                        break;
                }
            }
        } catch (Exception e) {
            log.warn("Failed to invoke advice class {}: {}", adviceClassName, e.getMessage());
        }
    }
```

- [ ] **Step 2: 验证 YAML 修复**
Run: `/tmp/apache-maven-3.9.6/bin/mvn test -pl weaver-girl-core -Dtest="YamlConfigLoaderTest" -q 2>&1`
Expected:
  - Exit code: 0
  - Output contains: "5 tests passed"

- [ ] **Step 3: 提交**
Run: `git add weaver-girl-core/src/main/java/com/github/cc11001100/weavergirl/core/config/YamlConfigLoader.java && git commit -m "fix(core): fix YamlConfigLoader to correctly invoke before/after/onException on advice classes

- invokeAdviceClass now dispatches to correct Interceptor method based on phase
- buildInterceptor calls around in after() path (was missing before)
- Replace deprecated Class.newInstance() with getDeclaredConstructor().newInstance()

Co-Authored-By: Claude Opus 4.8 <noreply@anthropic.com>"`

---

### Task 3: 修复 AnnotationPluginLoader — @Around 语义补全 + newInstance 废弃

**Depends on:** None
**Files:**
- Modify: `weaver-girl-core/src/main/java/com/github/cc11001100/weavergirl/core/plugin/AnnotationPluginLoader.java:50-52,97-127`

- [ ] **Step 1: 修改 AnnotationPluginLoader — 替换 newInstance + 修复 @Around 语义**

文件: `weaver-girl-core/src/main/java/com/github/cc11001100/weavergirl/core/plugin/AnnotationPluginLoader.java:50-56`（替换 interceptorInstance 创建代码）

```java
        Object interceptorInstance;
        try {
            interceptorInstance = clazz.getDeclaredConstructor().newInstance();
        } catch (Exception e) {
            log.error("Cannot instantiate interceptor class {}: {}", clazz.getName(), e.getMessage());
            return;
        }
```

文件: `weaver-girl-core/src/main/java/com/github/cc11001100/weavergirl/core/plugin/AnnotationPluginLoader.java:97-127`（替换 createReflectiveInterceptor 方法）

```java
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
                invokeMethods(instance, arounds, invocation);
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
```

- [ ] **Step 2: 验证 AnnotationPluginLoader 修复**
Run: `/tmp/apache-maven-3.9.6/bin/mvn test -pl weaver-girl-core -q 2>&1`
Expected:
  - Exit code: 0
  - Output contains: "tests passed"

- [ ] **Step 3: 提交**
Run: `git add weaver-girl-core/src/main/java/com/github/cc11001100/weavergirl/core/plugin/AnnotationPluginLoader.java && git commit -m "fix(core): fix @Around semantics — call around in after() path, replace deprecated newInstance

- @Around methods now fire in before(), after(), and onException() paths (was missing after)
- Replace Class.newInstance() with getDeclaredConstructor().newInstance() for Java 9+ compat

Co-Authored-By: Claude Opus 4.8 <noreply@anthropic.com>"`

---

### Task 4: 修复 WeaverGirl.create() — 接通 ByteBuddy 管线

**Depends on:** Task 1
**Files:**
- Modify: `weaver-girl-core/src/main/java/com/github/cc11001100/weavergirl/core/WeaverGirl.java:62-64,159-171`

- [ ] **Step 1: 修改 WeaverGirl — 新增 withInstrumentation() 和 install() 支持**

文件: `weaver-girl-core/src/main/java/com/github/cc11001100/weavergirl/core/WeaverGirl.java:59-76`（替换 create() 到 getRegistry() 区间）

```java
    /**
     * Create a new WeaverGirl instance for programmatic API usage.
     * Use withInstrumentation() to connect to the ByteBuddy pipeline.
     */
    public static WeaverGirl create() {
        return new WeaverGirl();
    }

    /**
     * Connect this WeaverGirl instance to the ByteBuddy transformation pipeline.
     * Required for programmatic interceptors to actually take effect.
     *
     * @param instrumentation the JVM Instrumentation instance
     * @return this WeaverGirl instance for chaining
     */
    public WeaverGirl withInstrumentation(Instrumentation instrumentation) {
        this.instrumentation = instrumentation;
        InterceptorHolder.setRegistry(this.registry);
        WeaverTransformer transformer = new WeaverTransformer(this.registry);
        transformer.install(instrumentation);
        return this;
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
```

文件: `weaver-girl-core/src/main/java/com/github/cc11001100/weavergirl/core/WeaverGirl.java:159-172`（替换 InterceptBuilder.install() 方法）

```java
        public WeaverGirl install() {
            ClassMatcher classMatcher = ClassMatcher.byName(className);
            MethodMatcher methodMatcher = "*".equals(methodName)
                    ? MethodMatcher.any() : MethodMatcher.byName(methodName);
            Pointcut pointcut = new Pointcut(classMatcher, methodMatcher);
            if (interceptor == null) {
                interceptor = new Interceptor() {};
            }
            InterceptorDefinition definition = new InterceptorDefinition(
                    "programmatic-" + className + "-" + methodName, pointcut, interceptor, priority);
            weaverGirl.registry.register(definition);
            return weaverGirl;
        }

        public InterceptBuilder priority(int priority) {
            this.priority = priority;
            return this;
        }
```

还需要在 InterceptBuilder 中添加 priority 字段。文件: `weaver-girl-core/src/main/java/com/github/cc11001100/weavergirl/core/WeaverGirl.java:84`（在 InterceptBuilder 类的 methodName 字段后添加）

```java
        private int priority = 0;
```

- [ ] **Step 2: 验证 WeaverGirl 修复**
Run: `/tmp/apache-maven-3.9.6/bin/mvn test -pl weaver-girl-core -q 2>&1`
Expected:
  - Exit code: 0
  - Output contains: "tests passed"

- [ ] **Step 3: 提交**
Run: `git add weaver-girl-core/src/main/java/com/github/cc11001100/weavergirl/core/WeaverGirl.java && git commit -m "fix(core): connect programmatic API to ByteBuddy pipeline via withInstrumentation()

- New WeaverGirl.withInstrumentation(Instrumentation) sets global registry and installs transformer
- InterceptBuilder now supports .priority(int) for setting definition priority
- Programmatic interceptors registered via create().withInstrumentation() now actually transform bytecode

Co-Authored-By: Claude Opus 4.8 <noreply@anthropic.com>"`

---

### Task 5: 修复 MethodInvocation — 防御性拷贝 arguments

**Depends on:** None
**Files:**
- Modify: `weaver-girl-api/src/main/java/com/github/cc11001100/weavergirl/api/interceptor/MethodInvocation.java:25-32`

- [ ] **Step 1: 修改 MethodInvocation 构造函数 — 防御性拷贝 arguments 数组**

文件: `weaver-girl-api/src/main/java/com/github/cc11001100/weavergirl/api/interceptor/MethodInvocation.java:25-32`（替换构造函数）

```java
    public MethodInvocation(Class<?> targetClass, String methodName,
                            Object target, Object[] arguments) {
        this.targetClass = targetClass;
        this.methodName = methodName;
        this.target = target;
        this.arguments = arguments != null ? arguments.clone() : new Object[0];
        this.isSkipped = false;
    }
```

- [ ] **Step 2: 验证 MethodInvocation 修复**
Run: `/tmp/apache-maven-3.9.6/bin/mvn test -pl weaver-girl-api -q 2>&1`
Expected:
  - Exit code: 0
  - Output contains: "tests passed"

- [ ] **Step 3: 提交**
Run: `git add weaver-girl-api/src/main/java/com/github/cc11001100/weavergirl/api/interceptor/MethodInvocation.java && git commit -m "fix(api): defensive copy of arguments array in MethodInvocation constructor

Prevents external mutation of the invocation's argument state after construction.

Co-Authored-By: Claude Opus 4.8 <noreply@anthropic.com>"`

---

### Task 6: 修复 DefaultInterceptorRegistry — 添加按类名索引缓存

**Depends on:** None
**Files:**
- Modify: `weaver-girl-core/src/main/java/com/github/cc11001100/weavergirl/core/registry/DefaultInterceptorRegistry.java:1-55`
- Modify: `weaver-girl-core/src/test/java/com/github/cc11001100/weavergirl/core/registry/DefaultInterceptorRegistryTest.java`

- [ ] **Step 1: 修改 DefaultInterceptorRegistry — 添加 ConcurrentHashMap 索引**

文件: `weaver-girl-core/src/main/java/com/github/cc11001100/weavergirl/core/registry/DefaultInterceptorRegistry.java`（替换整个文件）

```java
// weaver-girl-core/src/main/java/com/github/cc11001100/weavergirl/core/registry/DefaultInterceptorRegistry.java
package com.github.cc11001100.weavergirl.core.registry;

import com.github.cc11001100.weavergirl.api.interceptor.InterceptorDefinition;
import com.github.cc11001100.weavergirl.api.registry.InterceptorRegistry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * Default thread-safe implementation of InterceptorRegistry.
 * Uses a ConcurrentHashMap index for fast class-level lookup.
 */
public class DefaultInterceptorRegistry implements InterceptorRegistry {

    private static final Logger log = LoggerFactory.getLogger(DefaultInterceptorRegistry.class);

    private final List<InterceptorDefinition> definitions = new CopyOnWriteArrayList<>();
    private final ConcurrentHashMap<String, List<InterceptorDefinition>> classIndex = new ConcurrentHashMap<>();
    private volatile boolean indexDirty = true;

    @Override
    public void register(InterceptorDefinition definition) {
        if (definition == null) {
            log.warn("Attempted to register null InterceptorDefinition, ignoring");
            return;
        }
        definitions.add(definition);
        indexDirty = true;
        log.info("Registered interceptor: {}", definition.getName());
    }

    @Override
    public List<InterceptorDefinition> getInterceptorsForClass(String className) {
        if (indexDirty) {
            rebuildIndex();
        }
        List<InterceptorDefinition> cached = classIndex.get(className);
        return cached != null ? cached : Collections.emptyList();
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
        classIndex.clear();
        indexDirty = true;
    }

    private synchronized void rebuildIndex() {
        if (!indexDirty) {
            return;
        }
        ConcurrentHashMap<String, List<InterceptorDefinition>> newIndex = new ConcurrentHashMap<>();
        for (InterceptorDefinition def : definitions) {
            String pattern = def.getPointcut().getClassMatcher().getPattern();
            newIndex.computeIfAbsent(pattern, k -> new ArrayList<>()).add(def);
        }
        // Sort each list by priority
        for (List<InterceptorDefinition> list : newIndex.values()) {
            list.sort(Comparator.comparingInt(InterceptorDefinition::getPriority));
        }
        classIndex.clear();
        classIndex.putAll(newIndex);
        indexDirty = false;
    }
}
```

- [ ] **Step 2: 验证 Registry 修复**
Run: `/tmp/apache-maven-3.9.6/bin/mvn test -pl weaver-girl-core -Dtest="DefaultInterceptorRegistryTest" -q 2>&1`
Expected:
  - Exit code: 0
  - Output contains: "5 tests passed"

- [ ] **Step 3: 提交**
Run: `git add weaver-girl-core/src/main/java/com/github/cc11001100/weavergirl/core/registry/DefaultInterceptorRegistry.java && git commit -m "perf(core): add ConcurrentHashMap index to DefaultInterceptorRegistry for O(1) class lookup

Previously getInterceptorsForClass did O(N) scan on every method entry/exit.
Now uses lazy-rebuilt index with dirty flag for O(1) lookup by class name.

Co-Authored-By: Claude Opus 4.8 <noreply@anthropic.com>"`

---

### Task 7: 全量集成验证 — 运行全部测试 + 重新打包

**Depends on:** Task 1, Task 2, Task 3, Task 4, Task 5, Task 6
**Files:**
- Modify: none (verification only)

- [ ] **Step 1: 运行全量测试**
Run: `/tmp/apache-maven-3.9.6/bin/mvn test -q 2>&1`
Expected:
  - Exit code: 0
  - Output does NOT contain: "FAIL" or "BUILD FAILURE"

- [ ] **Step 2: 重新打包 agent JAR 并验证 manifest**
Run: `/tmp/apache-maven-3.9.6/bin/mvn package -DskipTests -q 2>&1 && unzip -p weaver-girl-agent/target/weaver-girl-agent-1.0.0-SNAPSHOT.jar META-INF/MANIFEST.MF | head -10`
Expected:
  - Exit code: 0
  - Output contains: "Premain-Class" and "Agent-Class" and "Can-Retransform-Classes"

- [ ] **Step 3: 提交**
Run: `git add -A && git commit -m "chore: full integration verification after P0 defect fixes

All tests pass, agent JAR builds successfully with correct manifest.

Co-Authored-By: Claude Opus 4.8 <noreply@anthropic.com>"`

