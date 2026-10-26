package com.github.cc11001100.weavergirl.api.tracing;

import static org.junit.jupiter.api.Assertions.*;

import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

/** Every submission path propagates the current span, and lifecycle calls reach the delegate. */
class TraceExecutorServiceTest {

  private final ExecutorService pool = Executors.newSingleThreadExecutor();
  private final TraceExecutorService traced = TraceExecutorService.wrap(pool);

  @AfterEach
  void shutdown() {
    Tracer.clearCurrentSpan();
    pool.shutdownNow();
  }

  @Test
  void executeRunsTheTaskWithTheSubmittingSpan() throws Exception {
    SpanContext span = Tracer.startSpan("submit");

    traced.execute(() -> assertEquals(span.getSpanId(), Tracer.getCurrentSpan().getSpanId()));
    pool.shutdown();
    assertTrue(pool.awaitTermination(5, TimeUnit.SECONDS));
  }

  @Test
  void submitCallableReturnsItsValueUnderTheSpan() throws Exception {
    SpanContext span = Tracer.startSpan("submit");

    String seen =
        traced.submit(() -> Tracer.getCurrentSpan().getSpanId()).get(5, TimeUnit.SECONDS);

    assertEquals(span.getSpanId(), seen);
  }

  @Test
  void submitRunnableCompletes() throws Exception {
    traced.submit(() -> {}).get(5, TimeUnit.SECONDS);
    assertEquals("done", traced.submit(() -> {}, "done").get(5, TimeUnit.SECONDS));
  }

  @Test
  void invokeAllAndInvokeAnyPropagateTheSpan() throws Exception {
    SpanContext span = Tracer.startSpan("submit");
    List<Callable<String>> tasks =
        Arrays.<Callable<String>>asList(
            () -> Tracer.getCurrentSpan().getSpanId(), () -> Tracer.getCurrentSpan().getSpanId());

    assertEquals(span.getSpanId(), traced.invokeAll(tasks).get(0).get());
    assertEquals(span.getSpanId(), traced.invokeAll(tasks, 5, TimeUnit.SECONDS).get(1).get());
    assertEquals(span.getSpanId(), traced.invokeAny(Collections.singletonList(tasks.get(0))));
    assertEquals(
        span.getSpanId(),
        traced.invokeAny(Collections.singletonList(tasks.get(0)), 5, TimeUnit.SECONDS));
  }

  @Test
  void lifecycleDelegatesToTheUnderlyingPool() throws Exception {
    assertFalse(traced.isShutdown());
    assertFalse(traced.isTerminated());

    traced.shutdown();

    assertTrue(traced.isShutdown());
    assertTrue(traced.awaitTermination(5, TimeUnit.SECONDS));
    assertTrue(traced.isTerminated());
    assertTrue(traced.shutdownNow().isEmpty());
  }
}
