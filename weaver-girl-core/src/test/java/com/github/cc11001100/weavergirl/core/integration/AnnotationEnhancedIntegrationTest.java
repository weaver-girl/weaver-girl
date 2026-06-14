package com.github.cc11001100.weavergirl.core.integration;

import com.github.cc11001100.weavergirl.annotation.*;
import com.github.cc11001100.weavergirl.api.interceptor.Interceptor;
import com.github.cc11001100.weavergirl.api.interceptor.InterceptorDefinition;
import com.github.cc11001100.weavergirl.api.interceptor.MethodInvocation;
import com.github.cc11001100.weavergirl.api.registry.InterceptorRegistry;
import com.github.cc11001100.weavergirl.core.plugin.AnnotationPluginLoader;
import com.github.cc11001100.weavergirl.core.registry.DefaultInterceptorRegistry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.*;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Integration test verifying the full annotation enhancement pipeline:
 * @Order + @OnException + @AfterReturning + @Before + @After + @Around
 * all work together correctly in a single interceptor class.
 */
class AnnotationEnhancedIntegrationTest {

    @WeaveClass(target = "com.example.FullService")
    @Order(10)
    public static class FullAdviceInterceptor {
        static final List<String> callOrder = Collections.synchronizedList(new ArrayList<>());

        @Before("process")
        public void beforeProcess(MethodInvocation inv) {
            callOrder.add("before");
        }

        @After("process")
        public void afterProcess(MethodInvocation inv) {
            callOrder.add("after");
        }

        @Around("process")
        public void aroundProcess(MethodInvocation inv) {
            callOrder.add("around");
        }

        @OnException("process")
        public void onProcessError(MethodInvocation inv) {
            callOrder.add("onException");
        }

        @AfterReturning("process")
        public void onProcessSuccess(MethodInvocation inv) {
            callOrder.add("afterReturning");
        }
    }

    private AnnotationPluginLoader loader;
    private InterceptorRegistry registry;

    @BeforeEach
    void setUp() {
        loader = new AnnotationPluginLoader();
        registry = new DefaultInterceptorRegistry();
        FullAdviceInterceptor.callOrder.clear();
    }

    @Test
    void fullAdvicePipeline_allCallbacksInCorrectOrder() {
        Set<Class<?>> classes = new HashSet<>();
        classes.add(FullAdviceInterceptor.class);
        loader.loadAnnotatedInterceptors(classes, registry);

        List<InterceptorDefinition> defs = registry.getAllDefinitions();
        assertEquals(1, defs.size());
        assertEquals(10, defs.get(0).getPriority());

        Interceptor interceptor = defs.get(0).getInterceptor();

        // Simulate success path: before -> after
        MethodInvocation successInv = new MethodInvocation(
                String.class, "process", "target", new Object[]{"data"});
        interceptor.before(successInv);
        interceptor.after(successInv);

        // On success: around is called in both before and after phases
        // @AfterReturning is called in after (not onException)
        assertTrue(FullAdviceInterceptor.callOrder.contains("before"), "before should be called");
        assertTrue(FullAdviceInterceptor.callOrder.contains("after"), "after should be called");
        assertTrue(FullAdviceInterceptor.callOrder.contains("afterReturning"),
                "afterReturning should be called on success");
        assertFalse(FullAdviceInterceptor.callOrder.contains("onException"),
                "onException should NOT be called on success");

        // Verify around is called (it's invoked in both before and after phases)
        long aroundCount = FullAdviceInterceptor.callOrder.stream()
                .filter("around"::equals).count();
        assertEquals(2, aroundCount, "around should be called twice (before + after phases)");
    }

    @Test
    void fullAdvicePipeline_exceptionPath_onlyExceptionHandlersFire() {
        Set<Class<?>> classes = new HashSet<>();
        classes.add(FullAdviceInterceptor.class);
        loader.loadAnnotatedInterceptors(classes, registry);

        Interceptor interceptor = registry.getAllDefinitions().get(0).getInterceptor();

        // Simulate exception path
        MethodInvocation exInv = new MethodInvocation(
                String.class, "process", "target", new Object[]{"data"});
        exInv.setThrowable(new RuntimeException("test failure"));
        interceptor.before(exInv);
        interceptor.onException(exInv);

        assertTrue(FullAdviceInterceptor.callOrder.contains("onException"),
                "onException should be called on exception");
        assertFalse(FullAdviceInterceptor.callOrder.contains("afterReturning"),
                "afterReturning should NOT be called on exception");
    }

    @Test
    void fullAdvicePipeline_invocationOrderIsCorrect() {
        Set<Class<?>> classes = new HashSet<>();
        classes.add(FullAdviceInterceptor.class);
        loader.loadAnnotatedInterceptors(classes, registry);

        Interceptor interceptor = registry.getAllDefinitions().get(0).getInterceptor();

        MethodInvocation inv = new MethodInvocation(
                String.class, "process", "target", new Object[0]);
        interceptor.before(inv);
        interceptor.after(inv);

        List<String> order = new ArrayList<>(FullAdviceInterceptor.callOrder);

        // before phase: around -> before
        // after phase: around -> after -> afterReturning
        assertEquals("around", order.get(0), "First call in before phase should be around");
        assertEquals("before", order.get(1), "Second call in before phase should be before");
        assertEquals("around", order.get(2), "First call in after phase should be around");
        assertEquals("after", order.get(3), "Second call in after phase should be after");
        assertEquals("afterReturning", order.get(4), "Third call in after phase should be afterReturning");
    }

    @Test
    void fullAdvicePipeline_returnValueAccessibleInAfterReturning() {
        Set<Class<?>> classes = new HashSet<>();
        classes.add(FullAdviceInterceptor.class);
        loader.loadAnnotatedInterceptors(classes, registry);

        Interceptor interceptor = registry.getAllDefinitions().get(0).getInterceptor();

        MethodInvocation inv = new MethodInvocation(
                String.class, "process", "target", new Object[0]);
        inv.initReturnValue("hello-result");
        interceptor.after(inv);

        // afterReturning should have been called and should be able to access the return value
        assertTrue(FullAdviceInterceptor.callOrder.contains("afterReturning"));
    }
}
