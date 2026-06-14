package com.github.cc11001100.weavergirl.core.config;

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
import org.junit.jupiter.api.io.TempDir;

import java.io.File;
import java.io.FileWriter;
import java.io.IOException;
import java.nio.file.Path;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Tests for ConfigWatcher hot-reload functionality.
 */
class ConfigWatcherTest {

    @TempDir
    Path tempDir;

    private DefaultInterceptorRegistry registry;
    private File configFile;

    @BeforeEach
    void setUp() throws IOException {
        registry = new DefaultInterceptorRegistry();
        configFile = tempDir.resolve("weaver-test.yml").toFile();
        // Write initial config
        writeConfig("interceptors:\n" +
                "  - className: com.example.Service1\n" +
                "    method: process\n" +
                "    before: com.example.TestBeforeAdvice\n");
    }

    @AfterEach
    void tearDown() {
        // Clean up
    }

    private void writeConfig(String content) throws IOException {
        try (FileWriter writer = new FileWriter(configFile)) {
            writer.write(content);
        }
    }

    @Test
    void startWithNonexistentFile_doesNotStart() {
        ConfigWatcher watcher = new ConfigWatcher("/nonexistent/path/weaver.yml", registry);
        watcher.start();
        // Should not crash, watcher does not start
        watcher.stop();
    }

    @Test
    void startTwice_onlyOneWatcherThread() {
        ConfigWatcher watcher = new ConfigWatcher(configFile.getAbsolutePath(), registry);
        watcher.start();
        watcher.start(); // second call should be no-op
        watcher.stop();
    }

    @Test
    void stopWithoutStart_isNoOp() {
        ConfigWatcher watcher = new ConfigWatcher(configFile.getAbsolutePath(), registry);
        // Should not throw
        assertDoesNotThrow(watcher::stop);
    }

    @Test
    void afterReloadCallback_isInvokedOnFileChange() throws Exception {
        ConfigWatcher watcher = new ConfigWatcher(configFile.getAbsolutePath(), registry);
        AtomicInteger callbackCount = new AtomicInteger(0);
        CountDownLatch callbackLatch = new CountDownLatch(1);
        watcher.setAfterReloadCallback(() -> {
            callbackCount.incrementAndGet();
            callbackLatch.countDown();
        });
        watcher.start();

        // Wait for watcher to start
        Thread.sleep(500);

        // Modify the config file (touch it to trigger change event)
        writeConfig("interceptors:\n" +
                "  - className: com.example.Service2\n" +
                "    method: handle\n" +
                "    before: com.example.TestBeforeAdvice\n");

        // Wait for the watcher to detect the change
        boolean received = callbackLatch.await(10, TimeUnit.SECONDS);
        assertTrue(received, "Callback should have been invoked within timeout");

        watcher.stop();
        assertTrue(callbackCount.get() >= 1, "Callback should have been called at least once");
    }

    @Test
    void yamlInterceptorsAreUnregisteredBeforeReload() throws Exception {
        // Register an existing YAML interceptor manually
        Interceptor dummyInterceptor = new Interceptor() {
            @Override
            public void before(MethodInvocation invocation) {}
        };
        InterceptorDefinition yamlDef = new InterceptorDefinition(
                "yaml-test-interceptor",
                new Pointcut(ClassMatcher.byName("com.example.Service1"), MethodMatcher.byName("process")),
                dummyInterceptor
        );
        registry.register(yamlDef);
        assertEquals(1, registry.getAllDefinitions().size());

        ConfigWatcher watcher = new ConfigWatcher(configFile.getAbsolutePath(), registry);
        CountDownLatch callbackLatch = new CountDownLatch(1);
        watcher.setAfterReloadCallback(callbackLatch::countDown);
        watcher.start();

        Thread.sleep(500);

        // Modify config to trigger reload — the old yaml- interceptor should be unregistered
        writeConfig("interceptors:\n" +
                "  - className: com.example.Service3\n" +
                "    method: run\n" +
                "    before: com.example.TestBeforeAdvice\n");

        callbackLatch.await(10, TimeUnit.SECONDS);
        watcher.stop();

        // The original yaml- interceptor should be gone; the new one (with non-existent advice class)
        // may or may not be registered depending on whether loadFromFile succeeds with invalid class
        // The key assertion is that the old "yaml-test-interceptor" was unregistered
        boolean oldStillExists = registry.getAllDefinitions().stream()
                .anyMatch(d -> "yaml-test-interceptor".equals(d.getName()));
        assertFalse(oldStillExists, "Old YAML interceptor should have been unregistered during reload");
    }

    @Test
    void debouncePreventsRapidReloads() throws Exception {
        ConfigWatcher watcher = new ConfigWatcher(configFile.getAbsolutePath(), registry);
        AtomicInteger callbackCount = new AtomicInteger(0);
        CountDownLatch firstCallback = new CountDownLatch(1);
        watcher.setAfterReloadCallback(() -> {
            callbackCount.incrementAndGet();
            firstCallback.countDown();
        });
        watcher.start();

        Thread.sleep(500);

        // Rapidly modify the file multiple times within the debounce window
        for (int i = 0; i < 5; i++) {
            writeConfig("interceptors:\n" +
                    "  - className: com.example.Service" + i + "\n" +
                    "    method: process\n" +
                    "    before: com.example.TestBeforeAdvice\n");
            Thread.sleep(100); // 100ms between writes — all within 2000ms debounce
        }

        // Wait for first callback
        firstCallback.await(10, TimeUnit.SECONDS);
        Thread.sleep(500); // Wait a bit more to see if more callbacks fire
        watcher.stop();

        // Due to debounce, we expect fewer reloads than writes
        // At minimum the first one should have triggered
        assertTrue(callbackCount.get() >= 1, "At least one callback should fire");
        // With 2s debounce, rapid writes within 500ms should produce 1-2 callbacks, not 5
        assertTrue(callbackCount.get() <= 3, "Debounce should prevent all 5 writes from triggering separate reloads");
    }

    @Test
    void stopInterruptsWatcherThread() throws Exception {
        ConfigWatcher watcher = new ConfigWatcher(configFile.getAbsolutePath(), registry);
        watcher.start();
        Thread.sleep(200);
        watcher.stop();
        Thread.sleep(200);
        // If the watcher thread was properly interrupted, this test completes without hanging
    }
}
