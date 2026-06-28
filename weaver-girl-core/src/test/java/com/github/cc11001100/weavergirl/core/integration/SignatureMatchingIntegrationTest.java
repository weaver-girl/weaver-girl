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
 * Integration test for method-signature-level matching (P0-1).
 *
 * <p>Each test method uses its own dedicated inner class so that ByteBuddy
 * transformer registrations don't interfere across tests (ByteBuddy applies
 * all registered type transformers to each matched class, and once a class
 * has been transformed, subsequent tests sharing that class inherit the
 * earlier interceptor definitions).</p>
 */
class SignatureMatchingIntegrationTest {

    /** Target with overloaded methods: same name, different signatures. */
    public static class OverloadedService1 {
        public String process(String input) { return "string: " + input; }
        public String process(int number) { return "int: " + number; }
        public String process(String input, int count) { return "string+int: " + input + "," + count; }
        public String process() { return "no-args"; }
    }

    /** Second independent target class for a separate test. */
    public static class OverloadedService2 {
        public String process(String input) { return "string: " + input; }
        public String process(int number) { return "int: " + number; }
        public String process(String input, int count) { return "string+int: " + input + "," + count; }
    }

    /** Third independent target class. */
    public static class OverloadedService3 {
        public String process() { return "no-args"; }
        public String process(String input) { return "string: " + input; }
    }

    /** Fourth independent target class for primitive parameter test. */
    public static class OverloadedService4 {
        public String process(int number) { return "int: " + number; }
        public String process(String input) { return "string: " + input; }
        public String process(String input, int count) { return "string+int: " + input + "," + count; }
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
    void signatureMatcher_selectsOnlyStringOverload() {
        AtomicBoolean stringOverloadCalled = new AtomicBoolean(false);
        AtomicReference<String> capturedArg = new AtomicReference<>();

        Interceptor interceptor = new Interceptor() {
            @Override
            public void before(MethodInvocation inv) {
                stringOverloadCalled.set(true);
                capturedArg.set((String) inv.getArgument(0));
            }
        };

        String className = "com.github.cc11001100.weavergirl.core.integration.SignatureMatchingIntegrationTest$OverloadedService1";

        // Match ONLY process(String) — should NOT match process(int) etc.
        registry.register(new InterceptorDefinition("sig-string-only",
                new Pointcut(ClassMatcher.byName(className),
                        MethodMatcher.bySignature("process", "java.lang.String")),
                interceptor));

        installTransformer();

        OverloadedService1 service = new OverloadedService1();
        // Call the int overload — interceptor should NOT fire
        service.process(42);
        assertFalse(stringOverloadCalled.get(), "Should NOT fire for process(int)");

        // Call the String overload — interceptor SHOULD fire
        String result = service.process("hello");
        assertTrue(stringOverloadCalled.get(), "Should fire for process(String)");
        assertEquals("hello", capturedArg.get());
        assertEquals("string: hello", result);
    }

    @Test
    void signatureMatcher_selectsOnlyTwoArgOverload() {
        AtomicBoolean twoArgCalled = new AtomicBoolean(false);

        Interceptor interceptor = new Interceptor() {
            @Override
            public void before(MethodInvocation inv) {
                twoArgCalled.set(true);
            }
        };

        String className = "com.github.cc11001100.weavergirl.core.integration.SignatureMatchingIntegrationTest$OverloadedService2";

        // Match ONLY process(String, int)
        registry.register(new InterceptorDefinition("sig-two-arg",
                new Pointcut(ClassMatcher.byName(className),
                        MethodMatcher.bySignature("process", "java.lang.String,int")),
                interceptor));

        installTransformer();

        OverloadedService2 service = new OverloadedService2();

        // Call the String overload — should NOT match
        service.process("hello");
        assertFalse(twoArgCalled.get(), "Should NOT match process(String)");

        // Call the two-arg overload — SHOULD match
        String result = service.process("hello", 5);
        assertTrue(twoArgCalled.get(), "Should match process(String, int)");
        assertEquals("string+int: hello,5", result);
    }

    @Test
    void signatureMatcher_noArgsMatchesNoArgOverload() {
        AtomicBoolean noArgCalled = new AtomicBoolean(false);

        Interceptor interceptor = new Interceptor() {
            @Override
            public void before(MethodInvocation inv) {
                noArgCalled.set(true);
            }
        };

        String className = "com.github.cc11001100.weavergirl.core.integration.SignatureMatchingIntegrationTest$OverloadedService3";

        // Match ONLY process()
        registry.register(new InterceptorDefinition("sig-no-args",
                new Pointcut(ClassMatcher.byName(className),
                        MethodMatcher.bySignature("process", "")),
                interceptor));

        installTransformer();

        OverloadedService3 service = new OverloadedService3();

        // Call the String overload — should NOT match
        service.process("hello");
        assertFalse(noArgCalled.get(), "Should NOT match process(String)");

        // Call the no-arg overload — SHOULD match
        String result = service.process();
        assertTrue(noArgCalled.get(), "Should match process()");
        assertEquals("no-args", result);
    }

    @Test
    void signatureMatcher_intParamSelectsOnlyIntOverload() {
        AtomicInteger interceptCount = new AtomicInteger(0);

        Interceptor interceptor = new Interceptor() {
            @Override
            public void before(MethodInvocation inv) {
                interceptCount.incrementAndGet();
            }
        };

        String className = "com.github.cc11001100.weavergirl.core.integration.SignatureMatchingIntegrationTest$OverloadedService4";

        // Match ONLY process(int)
        registry.register(new InterceptorDefinition("sig-int-param",
                new Pointcut(ClassMatcher.byName(className),
                        MethodMatcher.bySignature("process", "int")),
                interceptor));

        installTransformer();

        OverloadedService4 service = new OverloadedService4();

        // String overload — should NOT match
        service.process("hello");
        assertEquals(0, interceptCount.get(), "Should NOT match process(String)");

        // int overload — SHOULD match
        String result = service.process(42);
        assertEquals(1, interceptCount.get(), "Should match process(int)");
        assertEquals("int: 42", result);

        // two-arg overload — should NOT match
        service.process("hello", 5);
        assertEquals(1, interceptCount.get(), "Should NOT match process(String, int)");
    }

    private void installTransformer() {
        WeaverTransformer transformer = new WeaverTransformer(registry);
        transformer.setIgnoreAgentClasses(false);
        transformer.install(instrumentation);
    }
}
