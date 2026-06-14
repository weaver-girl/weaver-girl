package com.github.cc11001100.weavergirl.core.trace;

import com.github.cc11001100.weavergirl.api.tracing.*;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Tests for cross-thread trace context propagation utilities.
 * @since 1.1.0
 */
class TracePropagationTest {

    @AfterEach
    void tearDown() {
        Tracer.clearCurrentSpan();
    }

    @Test
    void traceRunnable_shouldPropagateContextToNewThread() throws Exception {
        // Start a span in the main thread
        SpanContext span = Tracer.startSpan();
        String expectedTraceId = span.getTraceId();

        CountDownLatch latch = new CountDownLatch(1);
        AtomicReference<SpanContext> capturedInThread = new AtomicReference<>();

        // Wrap a Runnable that captures the span from the worker thread
        TraceRunnable traceRunnable = new TraceRunnable(() -> {
            capturedInThread.set(Tracer.getCurrentSpan());
            latch.countDown();
        });

        // Run in a different thread
        ExecutorService executor = Executors.newSingleThreadExecutor();
        try {
            executor.execute(traceRunnable);
            assertTrue(latch.await(5, TimeUnit.SECONDS), "Task should complete within timeout");

            SpanContext spanInThread = capturedInThread.get();
            assertNotNull(spanInThread, "Span should be visible in worker thread");
            assertEquals(expectedTraceId, spanInThread.getTraceId(), "Trace ID should match");
        } finally {
            executor.shutdown();
        }
    }

    @Test
    void traceCallable_shouldPropagateContextAndReturnResult() throws Exception {
        // Start a span in the main thread
        SpanContext span = Tracer.startSpan();
        String expectedTraceId = span.getTraceId();

        // Wrap a Callable that reads the span and returns the trace ID
        TraceCallable<String> traceCallable = new TraceCallable<>(() -> {
            SpanContext current = Tracer.getCurrentSpan();
            return current != null ? current.getTraceId() : null;
        });

        ExecutorService executor = Executors.newSingleThreadExecutor();
        try {
            Future<String> future = executor.submit(traceCallable);
            String result = future.get(5, TimeUnit.SECONDS);

            assertEquals(expectedTraceId, result, "Trace ID should be propagated to Callable thread");
        } finally {
            executor.shutdown();
        }
    }

    @Test
    void traceExecutorService_shouldAutoPropagate() throws Exception {
        // Start a span in the main thread
        SpanContext span = Tracer.startSpan();
        String expectedTraceId = span.getTraceId();

        ExecutorService rawExecutor = Executors.newSingleThreadExecutor();
        TraceExecutorService traceExecutor = new TraceExecutorService(rawExecutor);

        try {
            CountDownLatch latch = new CountDownLatch(1);
            AtomicReference<SpanContext> capturedInThread = new AtomicReference<>();

            // Submit via TraceExecutorService — should auto-propagate
            traceExecutor.execute(() -> {
                capturedInThread.set(Tracer.getCurrentSpan());
                latch.countDown();
            });

            assertTrue(latch.await(5, TimeUnit.SECONDS), "Task should complete within timeout");

            SpanContext spanInThread = capturedInThread.get();
            assertNotNull(spanInThread, "Span should be visible in worker thread via TraceExecutorService");
            assertEquals(expectedTraceId, spanInThread.getTraceId(), "Trace ID should match");
        } finally {
            traceExecutor.shutdown();
        }
    }

    @Test
    void traceContext_shouldCleanUpAfterTask() throws Exception {
        // Start a span in the main thread
        Tracer.startSpan();

        CountDownLatch taskFinished = new CountDownLatch(1);
        AtomicReference<SpanContext> spanAfterCleanup = new AtomicReference<>();

        ExecutorService rawExecutor = Executors.newSingleThreadExecutor();
        TraceExecutorService traceExecutor = new TraceExecutorService(rawExecutor);

        try {
            // Submit two tasks sequentially to the same worker thread.
            // First task uses trace propagation and cleans up after itself.
            // Second task checks that no span remains from the first task.
            CountDownLatch firstTaskDone = new CountDownLatch(1);

            traceExecutor.execute(() -> {
                // This task has a span — on exit, TraceRunnable cleans it up
                firstTaskDone.countDown();
            });

            assertTrue(firstTaskDone.await(5, TimeUnit.SECONDS), "First task should complete");

            // Submit an unwrapped task to the same thread via the raw executor
            // to verify the first task's TraceRunnable cleaned up the span.
            rawExecutor.execute(() -> {
                spanAfterCleanup.set(Tracer.getCurrentSpan());
                taskFinished.countDown();
            });

            assertTrue(taskFinished.await(5, TimeUnit.SECONDS), "Second task should complete");

            // The unwrapped task should see no span because TraceRunnable cleared it
            assertNull(spanAfterCleanup.get(), "Span should be cleared after task completes");
        } finally {
            traceExecutor.shutdown();
        }
    }
}
