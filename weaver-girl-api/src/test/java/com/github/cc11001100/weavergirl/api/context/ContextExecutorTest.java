package com.github.cc11001100.weavergirl.api.context;

import static org.junit.jupiter.api.Assertions.*;

import com.github.cc11001100.weavergirl.api.tracing.SpanContext;
import com.github.cc11001100.weavergirl.api.tracing.Tracer;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

class ContextExecutorTest {

  @AfterEach
  void tearDown() {
    ThreadContext.clear();
    Tracer.clearCurrentSpan();
  }

  @Test
  void wrap_executorPropagatesThreadContext() throws Exception {
    ThreadContext.put("traceId", "ctx-exec-1");
    ExecutorService delegate = Executors.newSingleThreadExecutor();
    try {
      AtomicReference<String> worker = new AtomicReference<>();
      CountDownLatch latch = new CountDownLatch(1);
      ContextExecutor wrapped = ContextExecutor.wrap(delegate);
      wrapped.execute(() -> {
        worker.set(ThreadContext.get("traceId"));
        latch.countDown();
      });
      assertTrue(latch.await(5, TimeUnit.SECONDS), "worker thread should finish in time");
      assertEquals(
          "ctx-exec-1",
          worker.get(),
          "ThreadContext should propagate through ContextExecutor.execute");
    } finally {
      delegate.shutdownNow();
    }
  }

  @Test
  void wrap_alreadyWrappedExecutorIsReturned() {
    ExecutorService delegate = Executors.newSingleThreadExecutor();
    ContextExecutor first = ContextExecutor.wrap(delegate);
    ContextExecutor second = ContextExecutor.wrap(first);
    assertSame(first, second, "Double-wrapping an already-wrapped executor should return the same instance");
  }

  @Test
  void wrap_nullExecutor_throwsNpe() {
    assertThrows(NullPointerException.class, () -> ContextExecutor.wrap((Executor) null));
  }

  @Test
  void wrap_executorService_returnsContextExecutorService() {
    ExecutorService delegate = Executors.newSingleThreadExecutor();
    try {
      ContextExecutor wrapped = ContextExecutor.wrap(delegate);
      assertTrue(wrapped instanceof ContextExecutorService, "ExecutorService should be wrapped as ContextExecutorService");
    } finally {
      delegate.shutdownNow();
    }
  }

  @Test
  void wrap_scheduledExecutorService_returnsContextScheduledExecutorService() {
    ScheduledExecutorService delegate = Executors.newScheduledThreadPool(1);
    try {
      ContextExecutor wrapped = ContextExecutor.wrap(delegate);
      assertTrue(
          wrapped instanceof ContextScheduledExecutorService,
          "ScheduledExecutorService should be wrapped as ContextScheduledExecutorService");
    } finally {
      delegate.shutdownNow();
    }
  }

  @Test
  void execute_capturesContextAtSubmissionTime() throws Exception {
    ThreadContext.put("traceId", "ctx-exec-capture");
    ExecutorService delegate = Executors.newSingleThreadExecutor();
    try {
      AtomicReference<String> worker = new AtomicReference<>();
      CountDownLatch latch = new CountDownLatch(1);
      ContextExecutor.wrap(delegate).execute(() -> {
        worker.set(ThreadContext.get("traceId"));
        latch.countDown();
      });
      ThreadContext.clear();
      assertTrue(latch.await(5, TimeUnit.SECONDS), "worker thread should finish in time");
      assertEquals(
          "ctx-exec-capture",
          worker.get(),
          "Context snapshot should be captured at submission time, not execution time");
    } finally {
      delegate.shutdownNow();
    }
  }

  @Test
  void startVirtualThread_propagatesContext() throws Exception {
    ThreadContext.put("traceId", "ctx-exec-vt");
    AtomicReference<String> worker = new AtomicReference<>();
    CountDownLatch latch = new CountDownLatch(1);
    ContextExecutor.startVirtualThread(() -> {
      worker.set(ThreadContext.get("traceId"));
      latch.countDown();
    });
    assertTrue(latch.await(5, TimeUnit.SECONDS), "virtual/fallback thread should finish in time");
    assertEquals(
        "ctx-exec-vt",
        worker.get(),
        "ThreadContext should propagate through startVirtualThread");
  }

  @Test
  void startVirtualThread_propagatesTracerSpan() throws Exception {
    SpanContext span = Tracer.startSpan();
    AtomicReference<SpanContext> workerSpan = new AtomicReference<>();
    CountDownLatch latch = new CountDownLatch(1);
    ContextExecutor.startVirtualThread(() -> {
      workerSpan.set(Tracer.getCurrentSpan());
      latch.countDown();
    });
    assertTrue(latch.await(5, TimeUnit.SECONDS), "virtual/fallback thread should finish in time");
    assertNotNull(workerSpan.get(), "Tracer span should propagate to virtual thread");
    assertEquals(span.getTraceId(), workerSpan.get().getTraceId());
  }

  @Test
  void startVirtualThread_fallsBackToPlatformThreadOnPreJava21() throws Exception {
    ThreadContext.put("traceId", "ctx-exec-fallback");
    AtomicReference<String> worker = new AtomicReference<>();
    CountDownLatch latch = new CountDownLatch(1);
    ContextExecutor.startVirtualThread(() -> {
      worker.set(ThreadContext.get("traceId"));
      latch.countDown();
    });
    assertTrue(latch.await(5, TimeUnit.SECONDS), "fallback thread should finish in time");
    assertEquals(
        "ctx-exec-fallback",
        worker.get(),
        "Context should propagate even when virtual thread falls back to platform thread");
  }

  @Test
  void startVirtualThread_nullTask_throwsNpe() {
    assertThrows(NullPointerException.class, () -> ContextExecutor.startVirtualThread(null));
  }
}
