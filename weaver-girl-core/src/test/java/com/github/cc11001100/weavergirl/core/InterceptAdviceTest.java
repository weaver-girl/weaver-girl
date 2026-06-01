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

    // --- onMethodEnter tests ---

    @Test
    void onMethodEnter_noMatchingInterceptor_returnsNull() throws Exception {
        // No interceptors registered at all
        Method method = SampleClass.class.getMethod("greet");
        Object[] args = new Object[0];

        MethodInvocation result = InterceptAdvice.onMethodEnter(
                SampleClass.class, method, new SampleClass(), args);

        assertNull(result, "Should return null when no interceptors match — original method should execute");
    }

    @Test
    void onMethodEnter_interceptorCallsSkipMethod_onMatchedClass_returnsInvocation() throws Exception {
        // Register an interceptor that skips the method for SampleClass.greet
        Interceptor skipInterceptor = new Interceptor() {
            @Override
            public void before(MethodInvocation invocation) {
                invocation.skipMethod();
            }
        };
        registry.register(new InterceptorDefinition(
                "skip-test",
                new Pointcut(ClassMatcher.byName(SampleClass.class.getName()), MethodMatcher.byName("greet")),
                skipInterceptor
        ));

        Method method = SampleClass.class.getMethod("greet");
        MethodInvocation result = InterceptAdvice.onMethodEnter(
                SampleClass.class, method, new SampleClass(), new Object[0]);

        assertNotNull(result, "Should return non-null MethodInvocation when skipMethod is called — triggers ByteBuddy skipOn");
        assertTrue(result.isSkipped(), "The returned invocation should have isSkipped=true");
    }

    @Test
    void onMethodEnter_interceptorCallsSkipMethod_onUnmatchedClass_returnsNull() throws Exception {
        // Register an interceptor for a DIFFERENT class
        Interceptor skipInterceptor = new Interceptor() {
            @Override
            public void before(MethodInvocation invocation) {
                invocation.skipMethod();
            }
        };
        registry.register(new InterceptorDefinition(
                "skip-other",
                new Pointcut(ClassMatcher.byName("com.example.DoesNotExist"), MethodMatcher.byName("greet")),
                skipInterceptor
        ));

        // Call onMethodEnter for SampleClass — interceptor should NOT match
        Method method = SampleClass.class.getMethod("greet");
        MethodInvocation result = InterceptAdvice.onMethodEnter(
                SampleClass.class, method, new SampleClass(), new Object[0]);

        assertNull(result, "Should return null when no interceptor matches the class — no skip triggered");
    }

    @Test
    void onMethodEnter_nullRegistry_returnsNull() throws Exception {
        // Remove the registry
        InterceptorHolder.setRegistry(null);

        Method method = SampleClass.class.getMethod("greet");
        MethodInvocation result = InterceptAdvice.onMethodEnter(
                SampleClass.class, method, new SampleClass(), new Object[0]);

        assertNull(result, "Should return null when registry is null — original method should execute");
    }

    @Test
    void onMethodEnter_interceptorDoesNotSkip_returnsNull() throws Exception {
        // Register an interceptor that does NOT call skipMethod
        Interceptor noOpInterceptor = new Interceptor() {
            @Override
            public void before(MethodInvocation invocation) {
                // intentionally does not skip
            }
        };
        registry.register(new InterceptorDefinition(
                "no-skip",
                new Pointcut(ClassMatcher.byName(SampleClass.class.getName()), MethodMatcher.byName("greet")),
                noOpInterceptor
        ));

        Method method = SampleClass.class.getMethod("greet");
        MethodInvocation result = InterceptAdvice.onMethodEnter(
                SampleClass.class, method, new SampleClass(), new Object[0]);

        assertNull(result, "Should return null when interceptor does not call skipMethod — original method executes normally");
    }

    // --- onMethodExit tests ---

    @Test
    void onMethodExit_nullInvocation_returnsEarlyWithoutError() {
        // Should not throw
        assertDoesNotThrow(() -> InterceptAdvice.onMethodExit(
                null, SampleClass.class, SampleClass.class.getMethod("greet"),
                null, "hello"));
    }

    @Test
    void onMethodExit_withReturnValue_setsReturnValueAndCallsAfter() throws Exception {
        MethodInvocation invocation = new MethodInvocation(SampleClass.class, "greet", new SampleClass(), new Object[0]);

        boolean[] afterCalled = {false};
        Interceptor interceptor = new Interceptor() {
            @Override
            public void after(MethodInvocation inv) {
                afterCalled[0] = true;
                assertEquals("hello", inv.getReturnValue(), "Return value should be set on invocation");
            }
        };
        registry.register(new InterceptorDefinition(
                "after-test",
                new Pointcut(ClassMatcher.byName(SampleClass.class.getName()), MethodMatcher.byName("greet")),
                interceptor
        ));

        Method method = SampleClass.class.getMethod("greet");
        InterceptAdvice.onMethodExit(invocation, SampleClass.class, method, null, "hello");

        assertTrue(afterCalled[0], "after() should have been called");
        assertEquals("hello", invocation.getReturnValue(), "ReturnValue should be set on invocation");
    }

    @Test
    void onMethodExit_withThrowable_setsThrowableAndCallsOnException() throws Exception {
        MethodInvocation invocation = new MethodInvocation(SampleClass.class, "greet", new SampleClass(), new Object[0]);

        RuntimeException testException = new RuntimeException("test error");
        boolean[] onExceptionCalled = {false};
        Interceptor interceptor = new Interceptor() {
            @Override
            public void onException(MethodInvocation inv) {
                onExceptionCalled[0] = true;
                assertNotNull(inv.getThrowable(), "Throwable should be set on invocation");
                assertEquals("test error", inv.getThrowable().getMessage());
            }
        };
        registry.register(new InterceptorDefinition(
                "exception-test",
                new Pointcut(ClassMatcher.byName(SampleClass.class.getName()), MethodMatcher.byName("greet")),
                interceptor
        ));

        Method method = SampleClass.class.getMethod("greet");
        InterceptAdvice.onMethodExit(invocation, SampleClass.class, method, testException, null);

        assertTrue(onExceptionCalled[0], "onException() should have been called");
        assertEquals(testException, invocation.getThrowable(), "Throwable should be set on invocation");
    }

    // --- Helper sample class for testing ---

    public static class SampleClass {
        public String greet() {
            return "hello";
        }
    }
}
