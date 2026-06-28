package com.github.cc11001100.weavergirl.core.integration;

import com.github.cc11001100.weavergirl.api.interceptor.Interceptor;
import com.github.cc11001100.weavergirl.api.interceptor.InterceptorDefinition;
import com.github.cc11001100.weavergirl.api.interceptor.MethodInvocation;
import com.github.cc11001100.weavergirl.api.matcher.ClassMatcher;
import com.github.cc11001100.weavergirl.api.matcher.MethodMatcher;
import com.github.cc11001100.weavergirl.api.pointcut.Pointcut;
import com.github.cc11001100.weavergirl.core.InterceptorHolder;
import com.github.cc11001100.weavergirl.core.registry.DefaultInterceptorRegistry;
import com.github.cc11001100.weavergirl.core.transformer.WeaverTransformer;
import net.bytebuddy.agent.ByteBuddyAgent;
import org.junit.jupiter.api.*;

import java.lang.instrument.Instrumentation;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Integration test for constructor hook support (P0-2).
 *
 * <p>Each test method uses its own dedicated inner class so that ByteBuddy
 * transformer registrations don't interfere across tests.</p>
 *
 * <p>Key invariant: constructors CANNOT be skipped. Even if an interceptor
 * calls {@code invocation.skipMethod()}, the constructor body still executes.
 * This is a JVM constraint — skipping a constructor would produce an
 * uninitialized object.</p>
 */
class ConstructorHookIntegrationTest {

    /** Target with a no-arg constructor. */
    public static class CtorService1 {
        public String value;
        public CtorService1() { this.value = "default"; }
        public CtorService1(String v) { this.value = v; }
    }

    /** Target with a parameterized constructor. */
    public static class CtorService2 {
        public String value;
        public int count;
        public CtorService2() { this.value = "empty"; this.count = 0; }
        public CtorService2(String v, int n) { this.value = v; this.count = n; }
    }

    /** Target for testing that skipMethod is ignored on constructors. */
    public static class CtorService3 {
        public String value;
        public CtorService3() { this.value = "constructed"; }
    }

    /** Target for testing that regular methods are NOT matched by byConstructor(). */
    public static class CtorService4 {
        public String value;
        public CtorService4() { this.value = "ctor"; }
        public String getValue() { return value; }
    }

    private static Instrumentation instrumentation;
    private DefaultInterceptorRegistry registry;

    @BeforeAll
    static void setUpClass() {
        try {
            instrumentation = ByteBuddyAgent.install();
        } catch (Exception e) {
            instrumentation = null;
        }
    }

    @BeforeEach
    void setUp() {
        Assumptions.assumeTrue(instrumentation != null,
                "ByteBuddyAgent self-attach not available in this environment");
        Assumptions.assumeTrue(instrumentation.isRetransformClassesSupported(),
                "JVM does not support class retransformation");

        registry = new DefaultInterceptorRegistry();
        InterceptorHolder.setRegistry(registry);
    }

    @AfterEach
    void tearDown() {
        InterceptorHolder.setRegistry(null);
        if (registry != null) {
            registry.clear();
        }
    }

    @Test
    void constructorHook_beforeCallbackFired() {
        AtomicBoolean beforeCalled = new AtomicBoolean(false);
        AtomicReference<String> capturedMethodName = new AtomicReference<>();

        Interceptor interceptor = new Interceptor() {
            @Override
            public void before(MethodInvocation inv) {
                beforeCalled.set(true);
                capturedMethodName.set(inv.getMethodName());
            }
        };

        String className = "com.github.cc11001100.weavergirl.core.integration.ConstructorHookIntegrationTest$CtorService1";

        registry.register(new InterceptorDefinition("ctor-before",
                new Pointcut(ClassMatcher.byName(className), MethodMatcher.byConstructor()),
                interceptor));

        installTransformer();

        CtorService1 service = new CtorService1();
        assertTrue(beforeCalled.get(), "before() should be called for constructor");
        assertEquals("<init>", capturedMethodName.get(), "Method name should be <init>");
        assertEquals("default", service.value, "Constructor body should still execute");
    }

    @Test
    void constructorHook_afterCallbackFired() {
        AtomicBoolean afterCalled = new AtomicBoolean(false);
        AtomicReference<String> capturedMethodName = new AtomicReference<>();

        Interceptor interceptor = new Interceptor() {
            @Override
            public void after(MethodInvocation inv) {
                afterCalled.set(true);
                capturedMethodName.set(inv.getMethodName());
            }
        };

        String className = "com.github.cc11001100.weavergirl.core.integration.ConstructorHookIntegrationTest$CtorService2";

        registry.register(new InterceptorDefinition("ctor-after",
                new Pointcut(ClassMatcher.byName(className), MethodMatcher.byConstructor()),
                interceptor));

        installTransformer();

        CtorService2 service = new CtorService2("hello", 5);
        assertTrue(afterCalled.get(), "after() should be called for constructor");
        assertEquals("<init>", capturedMethodName.get(), "Method name should be <init>");
        assertEquals("hello", service.value, "Constructor should have set value");
        assertEquals(5, service.count, "Constructor should have set count");
    }

    @Test
    void constructorHook_skipMethodIsIgnored() {
        AtomicBoolean beforeCalled = new AtomicBoolean(false);
        AtomicReference<String> capturedValue = new AtomicReference<>();

        Interceptor interceptor = new Interceptor() {
            @Override
            public void before(MethodInvocation inv) {
                beforeCalled.set(true);
                inv.skipMethod(); // This should be IGNORED for constructors
            }

            @Override
            public void after(MethodInvocation inv) {
                // Capture the constructed object's value to verify the ctor body ran
                Object target = inv.getTarget();
                if (target instanceof CtorService3) {
                    capturedValue.set(((CtorService3) target).value);
                }
            }
        };

        String className = "com.github.cc11001100.weavergirl.core.integration.ConstructorHookIntegrationTest$CtorService3";

        registry.register(new InterceptorDefinition("ctor-noskip",
                new Pointcut(ClassMatcher.byName(className), MethodMatcher.byConstructor()),
                interceptor));

        installTransformer();

        CtorService3 service = new CtorService3();
        assertTrue(beforeCalled.get(), "before() should be called");
        assertEquals("constructed", service.value,
                "Constructor body must execute even if skipMethod was called");
        assertEquals("constructed", capturedValue.get(),
                "after() should see the fully constructed object");
    }

    @Test
    void constructorHook_byConstructorDoesNotMatchRegularMethods() {
        AtomicInteger interceptCount = new AtomicInteger(0);

        Interceptor interceptor = new Interceptor() {
            @Override
            public void before(MethodInvocation inv) {
                interceptCount.incrementAndGet();
            }
        };

        String className = "com.github.cc11001100.weavergirl.core.integration.ConstructorHookIntegrationTest$CtorService4";

        // byConstructor() should ONLY match <init>, NOT getValue()
        registry.register(new InterceptorDefinition("ctor-only",
                new Pointcut(ClassMatcher.byName(className), MethodMatcher.byConstructor()),
                interceptor));

        installTransformer();

        CtorService4 service = new CtorService4();
        assertEquals(1, interceptCount.get(), "Should intercept the constructor exactly once");

        // Call a regular method — should NOT be intercepted by byConstructor()
        service.getValue();
        assertEquals(1, interceptCount.get(), "byConstructor() should NOT match regular methods");
    }

    @Test
    void constructorHook_parameterizedConstructor() {
        AtomicBoolean beforeCalled = new AtomicBoolean(false);
        AtomicReference<String> capturedMethodName = new AtomicReference<>();

        Interceptor interceptor = new Interceptor() {
            @Override
            public void before(MethodInvocation inv) {
                beforeCalled.set(true);
                capturedMethodName.set(inv.getMethodName());
            }
        };

        String className = "com.github.cc11001100.weavergirl.core.integration.ConstructorHookIntegrationTest$CtorService2";

        // Match only the (String, int) constructor
        registry.register(new InterceptorDefinition("ctor-sig",
                new Pointcut(ClassMatcher.byName(className),
                        MethodMatcher.byConstructor("java.lang.String,int")),
                interceptor));

        installTransformer();

        // No-arg constructor — should NOT match
        CtorService2 s1 = new CtorService2();
        assertFalse(beforeCalled.get(), "No-arg constructor should NOT match byConstructor(String,int)");

        // (String, int) constructor — SHOULD match
        CtorService2 s2 = new CtorService2("test", 3);
        assertTrue(beforeCalled.get(), "(String, int) constructor should match byConstructor(String,int)");
        assertEquals("<init>", capturedMethodName.get());
        assertEquals("test", s2.value);
        assertEquals(3, s2.count);
    }

    private void installTransformer() {
        WeaverTransformer transformer = new WeaverTransformer(registry);
        transformer.setIgnoreAgentClasses(false);
        transformer.install(instrumentation);
    }
}
