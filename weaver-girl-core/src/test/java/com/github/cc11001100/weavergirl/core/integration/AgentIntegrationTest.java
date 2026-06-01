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
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.*;

/**
 * End-to-end integration test that verifies bytecode transformation
 * actually works at runtime.
 *
 * <p>Uses ByteBuddyAgent to obtain an Instrumentation instance,
 * installs the WeaverTransformer, then invokes target methods and
 * verifies interceptors are called.</p>
 *
 * <p><strong>Important:</strong> All interceptors must be registered BEFORE
 * the transformer is installed, because the transformer reads the registry
 * at install time to determine which classes/methods to transform.
 * Subsequent interceptor registrations after install will NOT cause
 * retransformation of already-loaded classes.</p>
 */
class AgentIntegrationTest {

    /** Target service that we intercept in tests. */
    public static class TargetService {
        public String greet(String name) {
            return "Hello, " + name;
        }

        public int add(int a, int b) {
            return a + b;
        }

        public void riskyOperation() {
            throw new RuntimeException("something went wrong");
        }
    }

    private static final String TARGET_CLASS =
            "com.github.cc11001100.weavergirl.core.integration.AgentIntegrationTest$TargetService";

    private static Instrumentation instrumentation;
    private DefaultInterceptorRegistry registry;

    @BeforeAll
    static void setUpClass() {
        try {
            instrumentation = ByteBuddyAgent.install();
        } catch (Exception e) {
            // Not all environments support self-attach
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
    void beforeCallbackIsInvokedAtRuntime() {
        AtomicBoolean beforeCalled = new AtomicBoolean(false);
        AtomicReference<String> capturedArg = new AtomicReference<>();

        Interceptor interceptor = new Interceptor() {
            @Override
            public void before(MethodInvocation inv) {
                beforeCalled.set(true);
                capturedArg.set((String) inv.getArgument(0));
            }
        };

        registry.register(new InterceptorDefinition("test-before",
                new Pointcut(ClassMatcher.byName(TARGET_CLASS), MethodMatcher.byName("greet")),
                interceptor));

        installTransformer();

        TargetService service = new TargetService();
        String result = service.greet("World");

        assertTrue(beforeCalled.get(), "before() should have been called");
        assertEquals("World", capturedArg.get(), "Argument should have been captured");
        assertEquals("Hello, World", result, "Original return value should be preserved");
    }

    @Test
    void afterCallbackIsInvokedAtRuntime() {
        AtomicBoolean afterCalled = new AtomicBoolean(false);
        AtomicReference<Object> capturedReturn = new AtomicReference<>();

        Interceptor interceptor = new Interceptor() {
            @Override
            public void before(MethodInvocation inv) {
                // no-op
            }

            @Override
            public void after(MethodInvocation inv) {
                afterCalled.set(true);
                capturedReturn.set(inv.getReturnValue());
            }
        };

        // Register for ALL methods on TargetService, then install transformer once
        registry.register(new InterceptorDefinition("test-after",
                new Pointcut(ClassMatcher.byName(TARGET_CLASS), MethodMatcher.any()),
                interceptor));

        installTransformer();

        TargetService service = new TargetService();
        int result = service.add(3, 4);

        assertTrue(afterCalled.get(), "after() should have been called");
        assertEquals(7, capturedReturn.get(), "Return value should have been captured");
        assertEquals(7, result, "Original return value should be preserved");
    }

    @Test
    void onExceptionCallbackIsInvokedAtRuntime() {
        AtomicBoolean exceptionCalled = new AtomicBoolean(false);
        AtomicReference<Throwable> capturedException = new AtomicReference<>();

        Interceptor interceptor = new Interceptor() {
            @Override
            public void before(MethodInvocation inv) {
                // no-op
            }

            @Override
            public void onException(MethodInvocation inv) {
                exceptionCalled.set(true);
                capturedException.set(inv.getThrowable());
            }
        };

        // Register for ALL methods on TargetService, then install transformer once
        registry.register(new InterceptorDefinition("test-exception",
                new Pointcut(ClassMatcher.byName(TARGET_CLASS), MethodMatcher.any()),
                interceptor));

        installTransformer();

        TargetService service = new TargetService();
        RuntimeException thrown = assertThrows(RuntimeException.class, service::riskyOperation);

        assertTrue(exceptionCalled.get(), "onException() should have been called");
        assertNotNull(capturedException.get(), "Throwable should have been captured");
        assertEquals("something went wrong", capturedException.get().getMessage());
    }

    @Test
    void allCallbacksWorkTogetherOnSameMethod() {
        AtomicBoolean beforeCalled = new AtomicBoolean(false);
        AtomicBoolean afterCalled = new AtomicBoolean(false);
        AtomicReference<String> capturedArg = new AtomicReference<>();
        AtomicReference<Object> capturedReturn = new AtomicReference<>();

        Interceptor interceptor = new Interceptor() {
            @Override
            public void before(MethodInvocation inv) {
                beforeCalled.set(true);
                capturedArg.set((String) inv.getArgument(0));
            }

            @Override
            public void after(MethodInvocation inv) {
                afterCalled.set(true);
                capturedReturn.set(inv.getReturnValue());
            }
        };

        registry.register(new InterceptorDefinition("test-all",
                new Pointcut(ClassMatcher.byName(TARGET_CLASS), MethodMatcher.byName("greet")),
                interceptor));

        installTransformer();

        TargetService service = new TargetService();
        String result = service.greet("Integration");

        assertTrue(beforeCalled.get(), "before() should have been called");
        assertTrue(afterCalled.get(), "after() should have been called");
        assertEquals("Integration", capturedArg.get());
        assertEquals("Hello, Integration", capturedReturn.get());
        assertEquals("Hello, Integration", result);
    }

    private void installTransformer() {
        WeaverTransformer transformer = new WeaverTransformer(registry);
        transformer.install(instrumentation);
    }
}
