package com.github.cc11001100.weavergirl.api.context;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.github.cc11001100.weavergirl.api.tenant.TenantContext;
import com.github.cc11001100.weavergirl.api.tracing.Tracer;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ForkJoinPool;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

/**
 * Tests for {@link ContextCompletableFuture}.
 *
 * <p>Covers: supplyAsync/runAsync with an explicit executor, the no-executor overloads
 * (commonPool), null-executor fallback, no-leak when no context is set, and end-to-end
 * Tracer/Tenant/MDC propagation through the wrapped executor.
 */
class ContextCompletableFutureTest {

  @AfterEach
  void tearDown() {
    ThreadContext.clear();
    TenantContext.clear();
    Tracer.clearCurrentSpan();
  }

  @Test
  void supplyAsyncWithExecutor_propagatesThreadContext() throws Exception {
    ThreadContext.put("traceId", "cf-api-1");
    ExecutorService delegate = Executors.newSingleThreadExecutor();
    try {
      String result =
          ContextCompletableFuture.supplyAsync(() -> ThreadContext.<String>get("traceId"), delegate)
              .get(5, TimeUnit.SECONDS);

      assertEquals(
          "cf-api-1",
          result,
          "ThreadContext should be propagated through supplyAsync with executor");
    } finally {
      delegate.shutdownNow();
    }
  }

  @Test
  void runAsyncWithExecutor_propagatesThreadContext() throws Exception {
    ThreadContext.put("traceId", "cf-api-2");
    ExecutorService delegate = Executors.newSingleThreadExecutor();
    try {
      ConcurrentLinkedQueue<String> captured = new ConcurrentLinkedQueue<>();
      ContextCompletableFuture.runAsync(() -> captured.add(ThreadContext.get("traceId")), delegate)
          .get(5, TimeUnit.SECONDS);

      assertEquals(
          "cf-api-2",
          captured.poll(),
          "ThreadContext should be propagated through runAsync with executor");
    } finally {
      delegate.shutdownNow();
    }
  }

  @Test
  void supplyAsyncWithoutExecutor_propagatesThreadContext() throws Exception {
    ThreadContext.put("traceId", "cf-api-common");
    String result =
        ContextCompletableFuture.supplyAsync(() -> ThreadContext.<String>get("traceId"))
            .get(5, TimeUnit.SECONDS);

    assertEquals(
        "cf-api-common",
        result,
        "ThreadContext should be propagated through the no-executor supplyAsync (commonPool)");
  }

  @Test
  void runAsyncWithoutExecutor_propagatesThreadContext() throws Exception {
    ThreadContext.put("traceId", "cf-api-common-run");
    ConcurrentLinkedQueue<String> captured = new ConcurrentLinkedQueue<>();
    ContextCompletableFuture.runAsync(() -> captured.add(ThreadContext.get("traceId")))
        .get(5, TimeUnit.SECONDS);

    assertEquals(
        "cf-api-common-run",
        captured.poll(),
        "ThreadContext should be propagated through the no-executor runAsync (commonPool)");
  }

  @Test
  void nullExecutorFallsBackToCommonPool() throws Exception {
    ThreadContext.put("traceId", "cf-api-null");
    String result =
        ContextCompletableFuture.supplyAsync(() -> ThreadContext.<String>get("traceId"), null)
            .get(5, TimeUnit.SECONDS);

    assertEquals(
        "cf-api-null",
        result,
        "Null executor should fall back to the common ForkJoinPool with propagation");
  }

  @Test
  void alreadyWrappedExecutorIsReused() {
    ExecutorService delegate = Executors.newSingleThreadExecutor();
    try {
      ContextExecutor wrapped = ContextExecutor.wrap(delegate);
      assertSame(
          wrapped,
          ContextCompletableFuture.wrapExecutor(wrapped),
          "An already-wrapped executor should be reused, not double-wrapped");
      assertEquals(
          delegate,
          ((ContextExecutor) ContextCompletableFuture.wrapExecutor(delegate)).getDelegate(),
          "wrapExecutor should return a ContextExecutor around the delegate");
    } finally {
      delegate.shutdownNow();
    }
  }

  @Test
  void wrapExecutorNull_returnsCommonPoolWrapper() {
    assertTrue(
        ContextCompletableFuture.wrapExecutor(null) instanceof ContextExecutor,
        "Null executor should produce a ContextExecutor around the common pool");
  }

  @Test
  void wrapExecutorCommonPool_returnsContextExecutor() {
    assertTrue(
        ContextCompletableFuture.wrapExecutor(ForkJoinPool.commonPool()) instanceof ContextExecutor,
        "Common pool should be wrapped in a ContextExecutor");
  }

  @Test
  void noContextSet_noLeak() throws Exception {
    ExecutorService delegate = Executors.newSingleThreadExecutor();
    try {
      AtomicReference<String> seen = new AtomicReference<>("unset");
      ContextCompletableFuture.runAsync(() -> seen.set(ThreadContext.get("traceId")), delegate)
          .get(5, TimeUnit.SECONDS);

      assertNull(seen.get(), "No context should leak when none is set on the submitting thread");
    } finally {
      delegate.shutdownNow();
    }
  }

  @Test
  void chainedStages_propagateContext() throws Exception {
    ThreadContext.put("traceId", "cf-api-chain");
    ExecutorService delegate = Executors.newFixedThreadPool(2);
    try {
      String result =
          ContextCompletableFuture.supplyAsync(() -> ThreadContext.<String>get("traceId"), delegate)
              .thenApplyAsync(
                  traceId -> traceId + "-chained", ContextCompletableFuture.wrapExecutor(delegate))
              .get(5, TimeUnit.SECONDS);

      assertEquals(
          "cf-api-chain-chained",
          result,
          "ThreadContext should propagate through chained stages on a wrapped executor");
    } finally {
      delegate.shutdownNow();
    }
  }

  @Test
  void thenApplyAsync_propagatesThreadContext() throws Exception {
    ThreadContext.put("traceId", "cf-api-then-apply");
    CompletableFuture<String> source = ContextCompletableFuture.supplyAsync(() -> ThreadContext.<String>get("traceId"));
    String result =
        ContextCompletableFuture.thenApplyAsync(
            source,
            traceId -> traceId + "-applied",
            ContextCompletableFuture.wrapExecutor(null))
            .get(5, TimeUnit.SECONDS);

    assertEquals(
        "cf-api-then-apply-applied",
        result,
        "ThreadContext should propagate through thenApplyAsync using the built-in stage helper");
  }

  @Test
  void thenComposeAsync_propagatesThreadContext() throws Exception {
    ThreadContext.put("traceId", "cf-api-then-compose");
    CompletableFuture<String> source = ContextCompletableFuture.supplyAsync(() -> ThreadContext.<String>get("traceId"));
    String result =
        ContextCompletableFuture.thenComposeAsync(
            source,
            traceId -> CompletableFuture.completedFuture(traceId + "-composed"),
            ContextCompletableFuture.wrapExecutor(null))
            .get(5, TimeUnit.SECONDS);

    assertEquals(
        "cf-api-then-compose-composed",
        result,
        "ThreadContext should propagate through thenComposeAsync using the built-in stage helper");
  }

  @Test
  void thenRunAsync_propagatesThreadContext() throws Exception {
    ThreadContext.put("traceId", "cf-api-then-run");
    ConcurrentLinkedQueue<String> captured = new ConcurrentLinkedQueue<>();
    CompletableFuture<String> source = ContextCompletableFuture.supplyAsync(() -> ThreadContext.<String>get("traceId"));
    ContextCompletableFuture.thenRunAsync(
            source,
            () -> captured.add(ThreadContext.get("traceId")),
            ContextCompletableFuture.wrapExecutor(null))
        .get(5, TimeUnit.SECONDS);

    assertEquals(
        "cf-api-then-run",
        captured.poll(),
        "ThreadContext should propagate through thenRunAsync using the built-in stage helper");
  }

  @Test
  void endToEnd_tracerAndTenantPropagate() throws Exception {
    com.github.cc11001100.weavergirl.api.tracing.SpanContext span = Tracer.startSpan();
    TenantContext.setTenantId("tenant-123");
    ExecutorService delegate = Executors.newSingleThreadExecutor();
    try {
      AtomicReference<String> workerTraceId = new AtomicReference<>();
      AtomicReference<String> workerTenantId = new AtomicReference<>();
      ContextCompletableFuture.runAsync(
              () -> {
                workerTraceId.set(ThreadContext.get("traceId"));
                workerTenantId.set(TenantContext.getTenantId());
              },
              delegate)
          .get(5, TimeUnit.SECONDS);

      assertEquals(
          span.getTraceId(),
          workerTraceId.get(),
          "Tracer span should be bridged into ThreadContext on the worker thread");
      assertEquals(
          "tenant-123",
          workerTenantId.get(),
          "TenantContext should be propagated to the worker thread");
    } finally {
      delegate.shutdownNow();
    }
  }
}
