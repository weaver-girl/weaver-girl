package com.github.cc11001100.weavergirl.api.context;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;

class ThreadContextTest {

    @AfterEach
    void tearDown() {
        ThreadContext.clear();
    }

    @Test
    void putAndGetWorksInSameThread() {
        ThreadContext.put("traceId", "abc123");
        ThreadContext.put("userId", 42);

        String traceId = ThreadContext.get("traceId");
        Integer userId = ThreadContext.get("userId");

        assertEquals("abc123", traceId);
        assertEquals(42, userId);
    }

    @Test
    void getWithDefaultReturnsDefaultWhenKeyMissing() {
        String value = ThreadContext.get("nonexistent", "fallback");
        assertEquals("fallback", value);
    }

    @Test
    void getWithDefaultReturnsValueWhenKeyPresent() {
        ThreadContext.put("key", "actual");
        String value = ThreadContext.get("key", "fallback");
        assertEquals("actual", value);
    }

    @Test
    void getReturnsNullWhenKeyMissing() {
        assertNull(ThreadContext.get("nonexistent"));
    }

    @Test
    void removeRemovesAKey() {
        ThreadContext.put("toRemove", "value");
        ThreadContext.remove("toRemove");
        assertNull(ThreadContext.get("toRemove"));
    }

    @Test
    void clearRemovesAllKeys() {
        ThreadContext.put("a", 1);
        ThreadContext.put("b", 2);
        ThreadContext.clear();
        assertNull(ThreadContext.get("a"));
        assertNull(ThreadContext.get("b"));
    }

    @Test
    void captureAndRestoreWorksAcrossThreads() throws Exception {
        ThreadContext.put("traceId", "cross-thread-123");

        Map<String, Object> captured = ThreadContext.capture();
        assertEquals("cross-thread-123", captured.get("traceId"));

        // Verify the captured map is immutable
        assertThrows(UnsupportedOperationException.class, () -> captured.put("newKey", "newValue"));

        CountDownLatch latch = new CountDownLatch(1);
        String[] result = new String[1];

        new Thread(() -> {
            ThreadContext.restore(captured);
            result[0] = ThreadContext.get("traceId");
            latch.countDown();
        }).start();

        assertTrue(latch.await(5, TimeUnit.SECONDS));
        assertEquals("cross-thread-123", result[0]);
    }

    @Test
    void contextRunnablePropagatesContextToNewThread() throws Exception {
        ThreadContext.put("traceId", "runnable-trace");

        ContextRunnable contextRunnable = new ContextRunnable(() -> {
            String value = ThreadContext.get("traceId");
            assertEquals("runnable-trace", value);
        });

        // Clear the current thread context to prove propagation
        ThreadContext.clear();

        CountDownLatch latch = new CountDownLatch(1);
        ExecutorService executor = Executors.newSingleThreadExecutor();
        executor.submit(() -> {
            contextRunnable.run();
            latch.countDown();
        });

        assertTrue(latch.await(5, TimeUnit.SECONDS));
        executor.shutdown();
    }

    @Test
    void contextCallablePropagatesContextToNewThread() throws Exception {
        ThreadContext.put("traceId", "callable-trace");

        ContextCallable<String> contextCallable = new ContextCallable<>(() -> {
            return ThreadContext.get("traceId");
        });

        ThreadContext.clear();

        ExecutorService executor = Executors.newSingleThreadExecutor();
        Future<String> future = executor.submit(contextCallable);

        assertEquals("callable-trace", future.get(5, TimeUnit.SECONDS));
        executor.shutdown();
    }

    @Test
    void afterContextRunnableCompletesOriginalThreadContextIsRestored() throws Exception {
        ThreadContext.put("originalKey", "originalValue");

        ContextRunnable contextRunnable = new ContextRunnable(() -> {
            // Inside the runnable, the captured context is active
            assertEquals("originalValue", ThreadContext.get("originalKey"));
            // Modify context inside runnable
            ThreadContext.put("newKey", "newValue");
        });

        // Simulate running in another thread
        CountDownLatch latch = new CountDownLatch(1);
        new Thread(() -> {
            ThreadContext.put("otherThreadKey", "shouldNotLeak");
            contextRunnable.run();
            // After runnable, the previous context of this thread should be restored
            assertEquals("shouldNotLeak", ThreadContext.get("otherThreadKey"));
            assertNull(ThreadContext.get("newKey"));
            latch.countDown();
        }).start();

        assertTrue(latch.await(5, TimeUnit.SECONDS));

        // Original thread context should be untouched
        assertEquals("originalValue", ThreadContext.get("originalKey"));
        assertNull(ThreadContext.get("newKey"));
    }

    @Test
    void captureReturnsEmptyMapWhenContextIsEmpty() {
        ThreadContext.clear();
        Map<String, Object> captured = ThreadContext.capture();
        assertTrue(captured.isEmpty());
    }
}
