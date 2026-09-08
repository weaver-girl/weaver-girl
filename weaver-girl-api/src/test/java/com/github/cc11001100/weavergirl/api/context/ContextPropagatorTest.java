package com.github.cc11001100.weavergirl.api.context;

import static org.junit.jupiter.api.Assertions.*;

import com.github.cc11001100.weavergirl.api.tracing.SpanContext;
import com.github.cc11001100.weavergirl.api.tracing.Tracer;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

/**
 * Tests for the ContextPropagator SPI and built-in propagators.
 *
 * <p>Covers: TracerPropagator (span propagation + spanId bridge), ContextPropagatorRegistry
 * (register/unregister), and multi-propagator restore/cleanup ordering.
 */
class ContextPropagatorTest {

  @AfterEach
  void tearDown() {
    ThreadContext.clear();
    Tracer.clearCurrentSpan();
    // Reset registry to built-in propagators only
    ContextPropagatorRegistry.resetForTest();
  }

  // ===== TracerPropagator: span propagation =====

  @Test
  void tracerPropagator_propagatesSpanToWorkerThread() throws Exception {
    SpanContext span = Tracer.startSpan();
    ExecutorService executor = Executors.newSingleThreadExecutor();
    try {
      AtomicReference<SpanContext> workerSpan = new AtomicReference<>();
      Runnable wrapped = ContextPropagators.wrap(() -> workerSpan.set(Tracer.getCurrentSpan()));

      ThreadContext.clear();
      Tracer.clearCurrentSpan();
      executor.submit(wrapped).get(5, TimeUnit.SECONDS);

      assertNotNull(workerSpan.get());
      assertEquals(span.getTraceId(), workerSpan.get().getTraceId());
      assertEquals(span.getSpanId(), workerSpan.get().getSpanId());
    } finally {
      executor.shutdownNow();
    }
  }

  @Test
  void tracerPropagator_cleanupRestoresWorkerPriorSpan() throws Exception {
    // Worker thread starts with a span
    ExecutorService executor = Executors.newSingleThreadExecutor();
    try {
      // Establish a span on the worker thread first
      SpanContext workerSpan =
          executor
              .submit(
                  () -> {
                    SpanContext s = Tracer.startSpan();
                    return s;
                  })
              .get(5, TimeUnit.SECONDS);

      // Now submit a task with a different span from the main thread
      SpanContext mainSpan = Tracer.startSpan();
      executor
          .submit(
              ContextPropagators.wrap(
                  () -> {
                    // Inside the task, the main span should be active
                    assertEquals(mainSpan.getTraceId(), Tracer.getCurrentSpan().getTraceId());
                  }))
          .get(5, TimeUnit.SECONDS);

      // After the task, the worker's original span should be restored
      AtomicReference<SpanContext> afterSpan = new AtomicReference<>();
      executor.submit(() -> afterSpan.set(Tracer.getCurrentSpan())).get(5, TimeUnit.SECONDS);

      // The worker's original span should be back
      assertNotNull(afterSpan.get());
      assertEquals(workerSpan.getTraceId(), afterSpan.get().getTraceId());
    } finally {
      executor.shutdownNow();
    }
  }

  @Test
  void tracerPropagator_cleanupClearsSpanWhenWorkerHadNone() throws Exception {
    ExecutorService executor = Executors.newSingleThreadExecutor();
    try {
      SpanContext span = Tracer.startSpan();

      executor
          .submit(
              ContextPropagators.wrap(
                  () -> {
                    // Task runs with the propagated span
                    assertNotNull(Tracer.getCurrentSpan());
                  }))
          .get(5, TimeUnit.SECONDS);

      // Worker thread should have no span after cleanup
      AtomicReference<SpanContext> afterSpan = new AtomicReference<>();
      executor.submit(() -> afterSpan.set(Tracer.getCurrentSpan())).get(5, TimeUnit.SECONDS);

      assertNull(afterSpan.get(), "Worker span should be cleared after task with no prior span");
    } finally {
      executor.shutdownNow();
    }
  }

  // ===== TracerPropagator: spanId bridge =====

  @Test
  void tracerPropagator_bridgesTraceIdAndSpanIdIntoThreadContext() throws Exception {
    SpanContext span = Tracer.startSpan();
    ExecutorService executor = Executors.newSingleThreadExecutor();
    try {
      AtomicReference<String> tcTraceId = new AtomicReference<>();
      AtomicReference<String> tcSpanId = new AtomicReference<>();

      executor
          .submit(
              ContextPropagators.wrap(
                  () -> {
                    tcTraceId.set(ThreadContext.get("traceId"));
                    tcSpanId.set(ThreadContext.get("spanId"));
                  }))
          .get(5, TimeUnit.SECONDS);

      assertEquals(
          span.getTraceId(), tcTraceId.get(), "traceId should be bridged into ThreadContext");
      assertEquals(span.getSpanId(), tcSpanId.get(), "spanId should be bridged into ThreadContext");
    } finally {
      executor.shutdownNow();
    }
  }

  @Test
  void tracerPropagator_bridgeCleanedUpAfterTask() throws Exception {
    SpanContext span = Tracer.startSpan();
    ExecutorService executor = Executors.newSingleThreadExecutor();
    try {
      executor
          .submit(
              ContextPropagators.wrap(
                  () -> {
                    // Bridge keys exist during task
                    assertNotNull(ThreadContext.get("traceId"));
                    assertNotNull(ThreadContext.get("spanId"));
                  }))
          .get(5, TimeUnit.SECONDS);

      // After task, bridge keys should be cleaned from worker's ThreadContext
      AtomicReference<String> afterTraceId = new AtomicReference<>();
      AtomicReference<String> afterSpanId = new AtomicReference<>();
      executor
          .submit(
              () -> {
                afterTraceId.set(ThreadContext.get("traceId"));
                afterSpanId.set(ThreadContext.get("spanId"));
              })
          .get(5, TimeUnit.SECONDS);

      assertNull(afterTraceId.get(), "Bridged traceId should be cleaned after task");
      assertNull(afterSpanId.get(), "Bridged spanId should be cleaned after task");
    } finally {
      executor.shutdownNow();
    }
  }

  @Test
  void tracerPropagator_noSpan_noBridgeKeys() throws Exception {
    // No active span
    ExecutorService executor = Executors.newSingleThreadExecutor();
    try {
      AtomicReference<String> tcTraceId = new AtomicReference<>();
      AtomicReference<String> tcSpanId = new AtomicReference<>();

      executor
          .submit(
              ContextPropagators.wrap(
                  () -> {
                    tcTraceId.set(ThreadContext.get("traceId"));
                    tcSpanId.set(ThreadContext.get("spanId"));
                  }))
          .get(5, TimeUnit.SECONDS);

      assertNull(tcTraceId.get());
      assertNull(tcSpanId.get());
    } finally {
      executor.shutdownNow();
    }
  }

  // ===== ContextPropagatorRegistry =====

  @Test
  void registry_hasBuiltInPropagators() {
    assertFalse(ContextPropagatorRegistry.getAll().isEmpty());
    assertNotNull(ContextPropagatorRegistry.get("thread-context"));
    assertNotNull(ContextPropagatorRegistry.get("tracer-span"));
  }

  @Test
  void registry_registerUnregisterCustomPropagator() {
    String name = "test-propagator";
    ContextPropagator custom =
        new ContextPropagator() {
          @Override
          public String name() {
            return name;
          }

          @Override
          public Snapshot capture() {
            return new Snapshot() {};
          }

          @Override
          public void restore(Snapshot snapshot) {}

          @Override
          public void cleanup(Snapshot previous) {}
        };

    ContextPropagatorRegistry.register(custom);
    assertNotNull(ContextPropagatorRegistry.get(name));

    ContextPropagatorRegistry.unregister(name);
    assertNull(ContextPropagatorRegistry.get(name));
  }

  @Test
  void registry_duplicateRegisterReplaces() {
    String name = "dup-test";
    ContextPropagator first = simplePropagator(name);
    ContextPropagator second = simplePropagator(name);

    ContextPropagatorRegistry.register(first);
    ContextPropagatorRegistry.register(second);

    assertSame(
        second,
        ContextPropagatorRegistry.get(name),
        "Second registration should replace the first");
  }

  // ===== Multi-propagator: combined ThreadContext + Tracer span =====

  @Test
  void combinedPropagation_threadContextAndSpan() throws Exception {
    ThreadContext.put("userId", "user-1");
    SpanContext span = Tracer.startSpan();

    ExecutorService executor = Executors.newSingleThreadExecutor();
    try {
      AtomicReference<String> tcUserId = new AtomicReference<>();
      AtomicReference<String> tcTraceId = new AtomicReference<>();
      AtomicReference<SpanContext> workerSpan = new AtomicReference<>();

      executor
          .submit(
              ContextPropagators.wrap(
                  () -> {
                    tcUserId.set(ThreadContext.get("userId"));
                    tcTraceId.set(ThreadContext.get("traceId"));
                    workerSpan.set(Tracer.getCurrentSpan());
                  }))
          .get(5, TimeUnit.SECONDS);

      assertEquals("user-1", tcUserId.get(), "ThreadContext should be propagated");
      assertEquals(span.getTraceId(), tcTraceId.get(), "traceId should be bridged");
      assertNotNull(workerSpan.get(), "Tracer span should be propagated");
      assertEquals(span.getTraceId(), workerSpan.get().getTraceId());
    } finally {
      executor.shutdownNow();
    }
  }

  @Test
  void combinedCleanup_restoresWorkerState() throws Exception {
    ExecutorService executor = Executors.newSingleThreadExecutor();
    try {
      // Pre-populate worker thread with state
      executor
          .submit(
              () -> {
                ThreadContext.put("workerKey", "workerValue");
                Tracer.startSpan();
              })
          .get(5, TimeUnit.SECONDS);

      // Submit task from main thread with different state
      ThreadContext.put("mainKey", "mainValue");
      SpanContext mainSpan = Tracer.startSpan();

      executor
          .submit(
              ContextPropagators.wrap(
                  () -> {
                    // During task: main thread's state should be visible
                    assertEquals("mainValue", ThreadContext.get("mainKey"));
                    assertNotNull(Tracer.getCurrentSpan());
                  }))
          .get(5, TimeUnit.SECONDS);

      // After task: worker's original state should be restored
      AtomicReference<String> workerKey = new AtomicReference<>();
      AtomicReference<SpanContext> workerSpan = new AtomicReference<>();
      executor
          .submit(
              () -> {
                workerKey.set(ThreadContext.get("workerKey"));
                workerSpan.set(Tracer.getCurrentSpan());
              })
          .get(5, TimeUnit.SECONDS);

      assertEquals("workerValue", workerKey.get(), "Worker's ThreadContext should be restored");
      assertNotNull(workerSpan.get(), "Worker's Tracer span should be restored");
    } finally {
      executor.shutdownNow();
    }
  }

  // ===== ContextSnapshot.empty() semantics =====

  @Test
  void emptySnapshotClearsThreadContext() {
    ThreadContext.put("key", "value");
    try (ContextScope ignored = ContextSnapshot.empty().activate()) {
      assertNull(ThreadContext.get("key"), "Empty snapshot should clear ThreadContext");
    }
    assertEquals(
        "value", ThreadContext.get("key"), "Original context should be restored after scope close");
  }

  // ===== Helper =====

  private static ContextPropagator simplePropagator(String name) {
    return new ContextPropagator() {
      @Override
      public String name() {
        return name;
      }

      @Override
      public Snapshot capture() {
        return new Snapshot() {};
      }

      @Override
      public void restore(Snapshot snapshot) {}

      @Override
      public void cleanup(Snapshot previous) {}
    };
  }
}
