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
import java.util.concurrent.Callable;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;

class AsyncArgumentAdviceTest {

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
    void onMethodEnterFiltersArgumentRewriteInterceptorsByFullSignature() throws Exception {
        AtomicInteger runnableResultInvocations = new AtomicInteger();
        AtomicInteger callableInvocations = new AtomicInteger();

        registry.register(argumentRewrite("submit-runnable-result",
                MethodMatcher.bySignature("submit", "java.lang.Runnable,java.lang.Object"),
                new Interceptor() {
                    @Override
                    public void before(MethodInvocation invocation) {
                        runnableResultInvocations.incrementAndGet();
                    }
                }));
        registry.register(argumentRewrite("submit-callable",
                MethodMatcher.bySignature("submit", "java.util.concurrent.Callable"),
                new Interceptor() {
                    @Override
                    public void before(MethodInvocation invocation) {
                        callableInvocations.incrementAndGet();
                    }
                }));

        Method method = SampleExecutor.class.getMethod("submit", Runnable.class, Object.class);

        AsyncArgumentAdvice.onMethodEnter(SampleExecutor.class, method, (Runnable) () -> {});

        assertEquals(1, runnableResultInvocations.get(),
                "The matching submit(Runnable,Object) interceptor should run");
        assertEquals(0, callableInvocations.get(),
                "Same-name submit(Callable) interceptor must not run for a different signature");
    }

    private InterceptorDefinition argumentRewrite(String name, MethodMatcher matcher, Interceptor interceptor) {
        return new InterceptorDefinition(
                name,
                new Pointcut(ClassMatcher.byName(SampleExecutor.class.getName()), matcher),
                interceptor,
                0,
                InterceptorDefinition.AdviceMode.ARGUMENT_REWRITE);
    }

    static final class SampleExecutor {
        public void submit(Runnable task, Object result) {
        }

        public void submit(Callable<?> task) {
        }
    }
}
