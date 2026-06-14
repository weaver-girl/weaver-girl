package com.github.cc11001100.weavergirl.core.registry;

import com.github.cc11001100.weavergirl.api.interceptor.Interceptor;
import com.github.cc11001100.weavergirl.api.interceptor.InterceptorDefinition;
import com.github.cc11001100.weavergirl.api.matcher.ClassMatcher;
import com.github.cc11001100.weavergirl.api.matcher.MethodMatcher;
import com.github.cc11001100.weavergirl.api.pointcut.Pointcut;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.Collections;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;

class DefaultInterceptorRegistryConcurrentTest {

    private DefaultInterceptorRegistry registry;

    @BeforeEach
    void setUp() {
        registry = new DefaultInterceptorRegistry();
    }

    private InterceptorDefinition createDefinition(String name, String classPattern) {
        return new InterceptorDefinition(
                name,
                new Pointcut(ClassMatcher.byName(classPattern), MethodMatcher.any()),
                new Interceptor() {}
        );
    }

    @Test
    void concurrentRegisterFromMultipleThreads() throws Exception {
        int threadCount = 20;
        int registrationsPerThread = 50;
        CountDownLatch latch = new CountDownLatch(threadCount);
        ExecutorService executor = Executors.newFixedThreadPool(threadCount);

        for (int t = 0; t < threadCount; t++) {
            final int threadIdx = t;
            executor.submit(() -> {
                try {
                    for (int i = 0; i < registrationsPerThread; i++) {
                        String name = "interceptor-" + threadIdx + "-" + i;
                        registry.register(createDefinition(name, "com.example.Service"));
                    }
                } finally {
                    latch.countDown();
                }
            });
        }

        assertTrue(latch.await(10, TimeUnit.SECONDS));
        executor.shutdown();

        assertEquals(threadCount * registrationsPerThread, registry.getAllDefinitions().size());
    }

    @Test
    void concurrentRegisterAndUnregister() throws Exception {
        int count = 100;
        // Register initial definitions
        for (int i = 0; i < count; i++) {
            registry.register(createDefinition("interceptor-" + i, "com.example.Service"));
        }

        CountDownLatch latch = new CountDownLatch(count);
        ExecutorService executor = Executors.newFixedThreadPool(count);

        // Half threads unregister, half register new ones
        for (int i = 0; i < count; i++) {
            final int idx = i;
            executor.submit(() -> {
                try {
                    if (idx % 2 == 0) {
                        registry.unregister("interceptor-" + idx);
                    } else {
                        registry.register(createDefinition("new-interceptor-" + idx, "com.example.NewService"));
                    }
                } finally {
                    latch.countDown();
                }
            });
        }

        assertTrue(latch.await(10, TimeUnit.SECONDS));
        executor.shutdown();

        // Should have original odd-numbered interceptors + new ones
        List<InterceptorDefinition> all = registry.getAllDefinitions();
        // 50 original (odd indices) + 50 new = 100
        assertEquals(100, all.size());
    }

    @Test
    void concurrentRegisterAndGetInterceptorsForClass() throws Exception {
        int threadCount = 10;
        int registrationsPerThread = 50;
        AtomicInteger queryCount = new AtomicInteger(0);
        CountDownLatch latch = new CountDownLatch(threadCount * 2);
        ExecutorService executor = Executors.newFixedThreadPool(threadCount * 2);

        // Writers: register interceptors
        for (int t = 0; t < threadCount; t++) {
            final int threadIdx = t;
            executor.submit(() -> {
                try {
                    for (int i = 0; i < registrationsPerThread; i++) {
                        registry.register(createDefinition(
                                "writer-" + threadIdx + "-" + i,
                                "com.example.Service"));
                    }
                } finally {
                    latch.countDown();
                }
            });
        }

        // Readers: query interceptors
        for (int t = 0; t < threadCount; t++) {
            executor.submit(() -> {
                try {
                    for (int i = 0; i < registrationsPerThread; i++) {
                        List<InterceptorDefinition> defs = registry.getInterceptorsForClass("com.example.Service");
                        queryCount.incrementAndGet();
                    }
                } finally {
                    latch.countDown();
                }
            });
        }

        assertTrue(latch.await(10, TimeUnit.SECONDS));
        executor.shutdown();

        // All queries should have completed without exception
        assertEquals(threadCount * registrationsPerThread, queryCount.get());
    }

    @Test
    void registerWithSameNameReplaces() {
        InterceptorDefinition def1 = createDefinition("same-name", "com.example.ServiceA");
        InterceptorDefinition def2 = createDefinition("same-name", "com.example.ServiceB");

        registry.register(def1);
        registry.register(def2);

        // The registry replaces on duplicate name, so only one entry with that name
        List<InterceptorDefinition> all = registry.getAllDefinitions();
        long count = all.stream().filter(d -> "same-name".equals(d.getName())).count();

        // Only the latest registration should remain (replaces on duplicate name)
        assertEquals(1, count);

        // The remaining entry should be def2 (ServiceB), since it replaced def1
        InterceptorDefinition remaining = all.stream()
                .filter(d -> "same-name".equals(d.getName()))
                .findFirst()
                .orElse(null);
        assertNotNull(remaining);
        assertEquals("com.example.ServiceB", remaining.getPointcut().getClassMatcher().getPattern());

        // Unregister by name should remove the entry
        assertTrue(registry.unregister("same-name"));
        assertEquals(0, registry.getAllDefinitions().size());
    }
}
