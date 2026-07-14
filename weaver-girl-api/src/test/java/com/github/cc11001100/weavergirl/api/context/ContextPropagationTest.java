package com.github.cc11001100.weavergirl.api.context;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.util.Arrays;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.FutureTask;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.*;

class ContextPropagationTest {

    @AfterEach
    void tearDown() {
        ThreadContext.clear();
    }

    @Test
    void snapshotCapturesThreadContextImmutably() {
        ThreadContext.put("traceId", "t1");

        ContextSnapshot snapshot = ContextSnapshot.capture();
        ThreadContext.put("traceId", "t2");

        assertEquals("t1", snapshot.getThreadContext().get("traceId"));
        assertThrows(UnsupportedOperationException.class,
                () -> snapshot.getThreadContext().put("new", "value"));
    }

    @Test
    void scopeActivatesSnapshotAndRestoresPreviousContext() {
        ThreadContext.put("traceId", "outer");
        ContextSnapshot snapshot = ContextSnapshot.fromThreadContext(
                java.util.Collections.<String, Object>singletonMap("traceId", "inner"));

        try (ContextScope ignored = snapshot.activate()) {
            assertEquals("inner", ThreadContext.get("traceId"));
            ThreadContext.put("requestId", "req-1");
        }

        assertEquals("outer", ThreadContext.get("traceId"));
        assertNull(ThreadContext.get("requestId"));
    }

    @Test
    void scopeCloseIsIdempotent() {
        ThreadContext.put("traceId", "outer");
        ContextScope scope = ContextSnapshot.empty().activate();

        assertNull(ThreadContext.get("traceId"));
        scope.close();
        scope.close();

        assertEquals("outer", ThreadContext.get("traceId"));
    }

    @Test
    void threadContextRestoreNullClearsContext() {
        ThreadContext.put("traceId", "trace");

        ThreadContext.restore(null);

        assertNull(ThreadContext.get("traceId"));
    }

    @Test
    void threadContextContainsKeySeesNullValues() {
        ThreadContext.put("nullable", null);

        assertTrue(ThreadContext.containsKey("nullable"));
        assertNull(ThreadContext.get("nullable", "default"));
    }

    @Test
    void propagatorWrapRunnableUsesCapturedSnapshot() {
        ThreadContext.put("traceId", "captured");
        Runnable wrapped = ContextPropagators.wrap(() ->
                assertEquals("captured", ThreadContext.get("traceId")));

        ThreadContext.put("traceId", "changed");
        wrapped.run();

        assertEquals("changed", ThreadContext.get("traceId"));
    }

    @Test
    void propagatorWrapCallablesUsesOneSnapshotForWholeCollection() throws Exception {
        ThreadContext.put("traceId", "batch");
        List<Callable<String>> wrapped = ContextPropagators.wrapCallables(Arrays.<Callable<String>>asList(
                () -> ThreadContext.get("traceId"),
                () -> ThreadContext.get("traceId")
        ));

        ThreadContext.clear();

        assertEquals("batch", wrapped.get(0).call());
        assertEquals("batch", wrapped.get(1).call());
    }

    @Test
    void fromThreadContextCopiesInputMap() {
        java.util.Map<String, Object> source = new java.util.HashMap<>();
        source.put("traceId", "before");

        ContextSnapshot snapshot = ContextSnapshot.fromThreadContext(source);
        source.put("traceId", "after");

        assertEquals("before", snapshot.getThreadContext().get("traceId"));
    }

    @Test
    void contextExecutorPropagatesExecuteAndRestoresWorkerContext() throws Exception {
        ExecutorService delegate = Executors.newSingleThreadExecutor();
        try {
            ContextExecutor executor = ContextExecutor.wrap(delegate);
            ThreadContext.put("traceId", "execute");

            FutureTask<String> task = new FutureTask<>(() -> ThreadContext.get("traceId"));
            executor.execute(task);

            assertEquals("execute", task.get(5, TimeUnit.SECONDS));
            assertWorkerContextCleared(delegate);
        } finally {
            delegate.shutdownNow();
        }
    }

    @Test
    void contextExecutorWrapPreservesSpecificExecutorTypes() {
        ExecutorService executorService = Executors.newSingleThreadExecutor();
        ScheduledExecutorService scheduledExecutorService = Executors.newSingleThreadScheduledExecutor();
        try {
            assertInstanceOf(ContextExecutorService.class, ContextExecutor.wrap(executorService));
            assertInstanceOf(ContextScheduledExecutorService.class, ContextExecutor.wrap(scheduledExecutorService));
            assertInstanceOf(ContextScheduledExecutorService.class,
                    ContextExecutorService.wrap(scheduledExecutorService));
        } finally {
            executorService.shutdownNow();
            scheduledExecutorService.shutdownNow();
        }
    }

    @Test
    void contextExecutorServicePropagatesSubmitVariants() throws Exception {
        ExecutorService delegate = Executors.newSingleThreadExecutor();
        try {
            ContextExecutorService executor = ContextExecutorService.wrap(delegate);
            ThreadContext.put("traceId", "submit");

            Future<String> callable = executor.submit(() -> ThreadContext.get("traceId"));
            Future<?> runnable = executor.submit(
                    () -> assertEquals("submit", ThreadContext.get("traceId")));
            Future<String> runnableWithResult = executor.submit(
                    () -> assertEquals("submit", ThreadContext.get("traceId")), "done");

            assertEquals("submit", callable.get(5, TimeUnit.SECONDS));
            assertNull(runnable.get(5, TimeUnit.SECONDS));
            assertEquals("done", runnableWithResult.get(5, TimeUnit.SECONDS));
            assertWorkerContextCleared(delegate);
        } finally {
            delegate.shutdownNow();
        }
    }

    @Test
    void contextExecutorServicePropagatesInvokeAllAndInvokeAny() throws Exception {
        ExecutorService delegate = Executors.newFixedThreadPool(2);
        try {
            ContextExecutorService executor = ContextExecutorService.wrap(delegate);
            ThreadContext.put("traceId", "batch");

            List<Callable<String>> tasks = Arrays.<Callable<String>>asList(
                    () -> ThreadContext.get("traceId"),
                    () -> ThreadContext.get("traceId")
            );

            List<Future<String>> futures = executor.invokeAll(tasks);
            assertEquals("batch", futures.get(0).get(5, TimeUnit.SECONDS));
            assertEquals("batch", futures.get(1).get(5, TimeUnit.SECONDS));
            List<Future<String>> timedFutures = executor.invokeAll(tasks, 5, TimeUnit.SECONDS);
            assertEquals("batch", timedFutures.get(0).get(5, TimeUnit.SECONDS));
            assertEquals("batch", timedFutures.get(1).get(5, TimeUnit.SECONDS));
            assertEquals("batch", executor.invokeAny(tasks));
            assertEquals("batch", executor.invokeAny(tasks, 5, TimeUnit.SECONDS));
        } finally {
            delegate.shutdownNow();
        }
    }

    @Test
    void contextScheduledExecutorServicePropagatesDelayedTasks() throws Exception {
        ScheduledExecutorService delegate = Executors.newSingleThreadScheduledExecutor();
        try {
            ContextScheduledExecutorService executor = ContextScheduledExecutorService.wrap(delegate);
            ThreadContext.put("traceId", "scheduled");

            ScheduledFuture<?> runnable = executor.schedule(
                    () -> assertEquals("scheduled", ThreadContext.get("traceId")),
                    1, TimeUnit.MILLISECONDS);
            ScheduledFuture<String> callable = executor.schedule(
                    () -> ThreadContext.get("traceId"),
                    1, TimeUnit.MILLISECONDS);

            assertNull(runnable.get(5, TimeUnit.SECONDS));
            assertEquals("scheduled", callable.get(5, TimeUnit.SECONDS));
            assertWorkerContextCleared(delegate);
        } finally {
            delegate.shutdownNow();
        }
    }

    @Test
    void contextScheduledExecutorServicePropagatesPeriodicTasks() throws Exception {
        ScheduledExecutorService delegate = Executors.newSingleThreadScheduledExecutor();
        try {
            ContextScheduledExecutorService executor = ContextScheduledExecutorService.wrap(delegate);
            CountDownLatch latch = new CountDownLatch(2);
            AtomicReference<String> traceId = new AtomicReference<>();
            ThreadContext.put("traceId", "periodic");

            ScheduledFuture<?> future = executor.scheduleAtFixedRate(() -> {
                traceId.set(ThreadContext.get("traceId"));
                latch.countDown();
            }, 1, 1, TimeUnit.MILLISECONDS);

            assertTrue(latch.await(5, TimeUnit.SECONDS));
            future.cancel(true);
            assertEquals("periodic", traceId.get());
            assertWorkerContextCleared(delegate);
        } finally {
            delegate.shutdownNow();
        }
    }

    @Test
    void contextScheduledExecutorServicePropagatesFixedDelayTasks() throws Exception {
        ScheduledExecutorService delegate = Executors.newSingleThreadScheduledExecutor();
        try {
            ContextScheduledExecutorService executor = ContextScheduledExecutorService.wrap(delegate);
            CountDownLatch latch = new CountDownLatch(2);
            AtomicReference<String> traceId = new AtomicReference<>();
            ThreadContext.put("traceId", "fixed-delay");

            ScheduledFuture<?> future = executor.scheduleWithFixedDelay(() -> {
                traceId.set(ThreadContext.get("traceId"));
                latch.countDown();
            }, 1, 1, TimeUnit.MILLISECONDS);

            assertTrue(latch.await(5, TimeUnit.SECONDS));
            future.cancel(true);
            assertEquals("fixed-delay", traceId.get());
            assertWorkerContextCleared(delegate);
        } finally {
            delegate.shutdownNow();
        }
    }

    private void assertWorkerContextCleared(ExecutorService delegate) throws Exception {
        ThreadContext.clear();
        Future<String> leaked = delegate.submit(() -> ThreadContext.get("traceId"));
        assertNull(leaked.get(5, TimeUnit.SECONDS));
    }
}
