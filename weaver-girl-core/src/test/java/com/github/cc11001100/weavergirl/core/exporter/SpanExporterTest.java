package com.github.cc11001100.weavergirl.core.exporter;

import static org.junit.jupiter.api.Assertions.*;

import com.github.cc11001100.weavergirl.core.testsupport.Statics;
import java.util.List;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class SpanExporterTest {

  private SpanExporter exporter;
  private SpanFormatter.InMemorySpanFormatter inMemory;

  @BeforeEach
  void setUp() {
    exporter = new SpanExporter(10, 1000, 1000);
    inMemory = new SpanFormatter.InMemorySpanFormatter(1000);
    exporter.addFormatter(inMemory);
  }

  @AfterEach
  void tearDown() {
    if (exporter.isRunning()) {
      exporter.stop();
    }
  }

  private SpanData createSpan(String traceId, String spanId, String opName, long durationMs) {
    return SpanData.builder()
        .traceId(traceId)
        .spanId(spanId)
        .operationName(opName)
        .startTimeMs(System.currentTimeMillis())
        .durationMs(durationMs)
        .build();
  }

  @Test
  void submit_shouldAcceptSpan() {
    SpanData span = createSpan("t1", "s1", "test-op", 100);
    assertTrue(exporter.submit(span));
    assertEquals(1, exporter.getPendingCount());
  }

  @Test
  void submit_shouldRejectNull() {
    assertFalse(exporter.submit(null));
    assertEquals(0, exporter.getPendingCount());
  }

  @Test
  void flush_shouldExportPendingSpans() {
    exporter.submit(createSpan("t1", "s1", "op1", 10));
    exporter.submit(createSpan("t1", "s2", "op2", 20));

    int flushed = exporter.flush();
    assertEquals(2, flushed);
    assertEquals(2, exporter.getExportedCount());
    assertEquals(2, inMemory.size());
  }

  @Test
  void start_shouldTriggerPeriodicExport() throws InterruptedException {
    exporter.submit(createSpan("t1", "s1", "op1", 10));
    exporter.start();

    // Wait for at least one export cycle
    Thread.sleep(1500);

    assertEquals(1, exporter.getExportedCount());
    assertEquals(1, inMemory.size());
  }

  @Test
  void stop_shouldFlushRemainingSpans() {
    exporter.start();
    exporter.submit(createSpan("t1", "s1", "op1", 10));

    exporter.stop();

    assertEquals(1, exporter.getExportedCount());
    assertEquals(1, inMemory.size());
  }

  @Test
  void bufferFull_shouldDropSpans() {
    SpanExporter smallExporter = new SpanExporter(10, 60000, 2);
    smallExporter.addFormatter(inMemory);

    assertTrue(smallExporter.submit(createSpan("t1", "s1", "op1", 10)));
    assertTrue(smallExporter.submit(createSpan("t1", "s2", "op2", 10)));
    assertFalse(smallExporter.submit(createSpan("t1", "s3", "op3", 10)));

    assertEquals(1, smallExporter.getDroppedCount());
  }

  /** Export-pipeline health is visible to the Prometheus plugin through MetricRegistry. */
  @Test
  void exportPipelineHealthIsRecordedInMetricRegistry() {
    com.github.cc11001100.weavergirl.api.metrics.MetricRegistry.remove(
        "weavergirl.span.export.spans");
    com.github.cc11001100.weavergirl.api.metrics.MetricRegistry.remove(
        "weavergirl.span.export.dropped");
    try {
      SpanExporter smallExporter = new SpanExporter(10, 60000, 1);
      smallExporter.addFormatter(inMemory);

      assertTrue(smallExporter.submit(createSpan("t1", "s1", "op1", 10)));
      assertFalse(smallExporter.submit(createSpan("t1", "s2", "op2", 10)));
      smallExporter.flush();

      assertEquals(
          1,
          com.github.cc11001100.weavergirl.api.metrics.MetricRegistry.snapshot(
                  "weavergirl.span.export.dropped")
              .getCount());
      assertEquals(
          1,
          com.github.cc11001100.weavergirl.api.metrics.MetricRegistry.snapshot(
                  "weavergirl.span.export.spans")
              .getCount());
    } finally {
      com.github.cc11001100.weavergirl.api.metrics.MetricRegistry.remove(
          "weavergirl.span.export.spans");
      com.github.cc11001100.weavergirl.api.metrics.MetricRegistry.remove(
          "weavergirl.span.export.dropped");
    }
  }

  @Test
  void statistics_shouldTrackExportMetrics() {
    exporter.submit(createSpan("t1", "s1", "op1", 10));
    exporter.submit(createSpan("t1", "s2", "op2", 20));
    exporter.flush();

    assertEquals(2, exporter.getExportedCount());
    assertEquals(1, exporter.getExportBatchCount());
    assertEquals(0, exporter.getDroppedCount());
    assertEquals(0, exporter.getFailedExportCount());
  }

  /** A zero or negative size falls back to the built-in default rather than a broken buffer. */
  @Test
  void nonPositiveSizesFallBackToDefaults() {
    SpanExporter defaults = new SpanExporter(0, 0, -1);
    assertEquals(100, defaults.getBatchSize());
    assertEquals(5_000, defaults.getExportIntervalMs());
    assertEquals(10_000, defaults.getBufferSize());
  }

  /** A formatter can be taken back out, and then it no longer receives spans. */
  @Test
  void aRemovedFormatterStopsReceivingSpans() {
    assertEquals(1, exporter.getFormatterCount());
    assertSame(exporter, exporter.removeFormatter(inMemory));
    assertEquals(0, exporter.getFormatterCount());

    exporter.submit(createSpan("t1", "s1", "op1", 10));
    assertEquals(1, exporter.flush());
    assertEquals(0, inMemory.size(), "a removed formatter still received the batch");
  }

  /** A formatter that throws is counted as a failed export and does not stop the next one. */
  @Test
  void aFormatterThatThrowsIsCountedAndSurvived() {
    exporter.addFormatter(
        spans -> {
          throw new IllegalStateException("backend down");
        });

    exporter.submit(createSpan("t1", "s1", "op1", 10));
    assertEquals(1, exporter.flush());

    assertEquals(1, exporter.getFailedExportCount(), "the failing formatter was not counted");
    assertEquals(1, inMemory.size(), "the formatter after the failing one was skipped");
    assertEquals(1, exporter.getExportedCount());
  }

  /** A scheduler that ignores the shutdown wait is forced to stop. */
  @Test
  void stopForcesASchedulerThatWillNotFinish() throws Exception {
    java.util.concurrent.atomic.AtomicInteger forced = new java.util.concurrent.atomic.AtomicInteger();
    ScheduledExecutorService stubborn =
        new java.util.concurrent.ScheduledThreadPoolExecutor(1) {
          @Override
          public boolean awaitTermination(long timeout, TimeUnit unit) {
            return false;
          }

          @Override
          public List<Runnable> shutdownNow() {
            forced.incrementAndGet();
            return super.shutdownNow();
          }
        };
    withScheduler(
        stubborn,
        () -> {
          exporter.start();
          exporter.stop();
        });
    assertEquals(1, forced.get(), "a scheduler that ignored the wait was not forced to stop");
  }

  /** An interrupted wait while stopping forces the scheduler down and restores the interrupt. */
  @Test
  void anInterruptedStopRestoresTheInterrupt() throws Exception {
    ScheduledExecutorService blocking =
        new java.util.concurrent.ScheduledThreadPoolExecutor(1) {
          @Override
          public boolean awaitTermination(long timeout, TimeUnit unit) throws InterruptedException {
            throw new InterruptedException("stopping");
          }
        };
    Thread.interrupted();
    try {
      withScheduler(
          blocking,
          () -> {
            exporter.start();
            exporter.stop();
          });
      assertTrue(Thread.currentThread().isInterrupted(), "the interrupt was swallowed");
    } finally {
      Thread.interrupted();
    }
  }

  /** The log formatter emits every span, and the no-arg in-memory formatter keeps only its bound. */
  @Test
  void theLogFormatterEmitsAndTheDefaultMemoryFormatterDropsTheOverflow() {
    new SpanFormatter.LoggingSpanFormatter().export(java.util.Collections.singletonList(createSpan("t1", "s1", "op1", 10)));

    SpanFormatter.InMemorySpanFormatter bounded = new SpanFormatter.InMemorySpanFormatter();
    java.util.List<SpanData> spans = new java.util.ArrayList<>();
    for (int i = 0; i < 1001; i++) {
      spans.add(createSpan("t" + i, "s" + i, "op", 1));
    }
    bounded.export(spans);

    assertEquals(1000, bounded.size(), "the default bound kept more than a thousand spans");
    bounded.clear();
    assertEquals(0, bounded.size());
  }

  /** Runs {@code body} with the exporter's scheduler replaced, and puts the original back after. */
  private void withScheduler(ScheduledExecutorService replacement, Runnable body) throws Exception {
    java.lang.reflect.Field field = SpanExporter.class.getDeclaredField("scheduler");
    ScheduledExecutorService original = (ScheduledExecutorService) Statics.read(field, exporter);
    Statics.write(field, exporter, replacement);
    try {
      body.run();
    } finally {
      Statics.write(field, exporter, original);
      original.shutdownNow();
    }
  }
}
