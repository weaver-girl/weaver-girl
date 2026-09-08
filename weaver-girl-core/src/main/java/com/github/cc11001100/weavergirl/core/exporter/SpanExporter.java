package com.github.cc11001100.weavergirl.core.exporter;

import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Batch exporter for trace span data.
 *
 * <p>Collects completed spans in a bounded buffer and exports them in batches through pluggable
 * span formatters. Supports multiple output backends:
 *
 * <ul>
 *   <li>OTLP JSON format (OpenTelemetry Protocol)
 *   <li>Logging (SLF4J structured output)
 *   <li>In-memory (for testing/debugging)
 * </ul>
 *
 * <p>Features:
 *
 * <ul>
 *   <li>Configurable batch size and export interval
 *   <li>Bounded buffer with overflow protection
 *   <li>Graceful flush on shutdown
 *   <li>Export statistics (exported, dropped, failed)
 * </ul>
 */
public class SpanExporter {

  private static final Logger log = LoggerFactory.getLogger(SpanExporter.class);

  private static final int DEFAULT_BATCH_SIZE = 100;
  private static final long DEFAULT_EXPORT_INTERVAL_MS = 5_000;
  private static final int DEFAULT_BUFFER_SIZE = 10_000;

  private final int batchSize;
  private final long exportIntervalMs;
  private final int bufferSize;
  private final BlockingQueue<SpanData> buffer;
  private final List<SpanFormatter> formatters;
  private final ScheduledExecutorService scheduler;
  private final AtomicBoolean running;

  // Statistics
  private final AtomicInteger pendingCount;
  private final AtomicLong exportedCount;
  private final AtomicLong droppedCount;
  private final AtomicLong failedExportCount;
  private final AtomicLong exportBatchCount;

  private ScheduledFuture<?> scheduledTask;

  public SpanExporter() {
    this(DEFAULT_BATCH_SIZE, DEFAULT_EXPORT_INTERVAL_MS, DEFAULT_BUFFER_SIZE);
  }

  public SpanExporter(int batchSize, long exportIntervalMs, int bufferSize) {
    this.batchSize = batchSize > 0 ? batchSize : DEFAULT_BATCH_SIZE;
    this.exportIntervalMs = exportIntervalMs > 0 ? exportIntervalMs : DEFAULT_EXPORT_INTERVAL_MS;
    this.bufferSize = bufferSize > 0 ? bufferSize : DEFAULT_BUFFER_SIZE;
    this.buffer = new LinkedBlockingQueue<>(this.bufferSize);
    this.formatters = new CopyOnWriteArrayList<>();
    this.scheduler =
        Executors.newSingleThreadScheduledExecutor(
            r -> {
              Thread t = new Thread(r, "weaver-girl-span-exporter");
              t.setDaemon(true);
              return t;
            });
    this.running = new AtomicBoolean(false);
    this.pendingCount = new AtomicInteger();
    this.exportedCount = new AtomicLong();
    this.droppedCount = new AtomicLong();
    this.failedExportCount = new AtomicLong();
    this.exportBatchCount = new AtomicLong();
  }

  // --- Span submission ---

  /**
   * Submit a completed span for export. Returns true if accepted, false if the buffer is full (span
   * dropped).
   */
  public boolean submit(SpanData span) {
    if (span == null) return false;
    boolean added = buffer.offer(span);
    if (added) {
      pendingCount.incrementAndGet();
    } else {
      droppedCount.incrementAndGet();
      log.debug("[SpanExporter] Buffer full, dropping span: {}", span.getTraceId());
    }
    return added;
  }

  // --- Formatters ---

  public SpanExporter addFormatter(SpanFormatter formatter) {
    formatters.add(formatter);
    return this;
  }

  public SpanExporter removeFormatter(SpanFormatter formatter) {
    formatters.remove(formatter);
    return this;
  }

  // --- Lifecycle ---

  public void start() {
    if (running.compareAndSet(false, true)) {
      scheduledTask =
          scheduler.scheduleAtFixedRate(
              this::exportBatch, exportIntervalMs, exportIntervalMs, TimeUnit.MILLISECONDS);
      log.info(
          "[SpanExporter] Started (batchSize={}, interval={}ms, buffer={})",
          batchSize,
          exportIntervalMs,
          bufferSize);
    }
  }

  public void stop() {
    if (running.compareAndSet(true, false)) {
      // Flush remaining spans
      flush();
      if (scheduledTask != null) {
        scheduledTask.cancel(false);
      }
      scheduler.shutdown();
      try {
        if (!scheduler.awaitTermination(5, TimeUnit.SECONDS)) {
          scheduler.shutdownNow();
        }
      } catch (InterruptedException e) {
        scheduler.shutdownNow();
        Thread.currentThread().interrupt();
      }
      log.info(
          "[SpanExporter] Stopped (exported={}, dropped={}, failed={})",
          exportedCount.get(),
          droppedCount.get(),
          failedExportCount.get());
    }
  }

  /** Flush all pending spans immediately. */
  public int flush() {
    List<SpanData> batch = new ArrayList<>();
    buffer.drainTo(batch, bufferSize);
    if (!batch.isEmpty()) {
      doExport(batch);
    }
    return batch.size();
  }

  // --- Export ---

  private void exportBatch() {
    List<SpanData> batch = new ArrayList<>(batchSize);
    buffer.drainTo(batch, batchSize);
    if (!batch.isEmpty()) {
      doExport(batch);
    }
  }

  private void doExport(List<SpanData> batch) {
    int count = batch.size();
    exportBatchCount.incrementAndGet();

    for (SpanFormatter formatter : formatters) {
      try {
        formatter.export(Collections.unmodifiableList(batch));
      } catch (Exception e) {
        failedExportCount.incrementAndGet();
        log.warn(
            "[SpanExporter] Formatter {} failed: {}",
            formatter.getClass().getSimpleName(),
            e.getMessage());
      }
    }

    exportedCount.addAndGet(count);
    pendingCount.addAndGet(-count);
  }

  // --- Accessors ---

  public boolean isRunning() {
    return running.get();
  }

  public int getPendingCount() {
    return pendingCount.get();
  }

  public long getExportedCount() {
    return exportedCount.get();
  }

  public long getDroppedCount() {
    return droppedCount.get();
  }

  public long getFailedExportCount() {
    return failedExportCount.get();
  }

  public long getExportBatchCount() {
    return exportBatchCount.get();
  }

  public int getBatchSize() {
    return batchSize;
  }

  public long getExportIntervalMs() {
    return exportIntervalMs;
  }

  public int getBufferSize() {
    return bufferSize;
  }

  public int getFormatterCount() {
    return formatters.size();
  }
}
