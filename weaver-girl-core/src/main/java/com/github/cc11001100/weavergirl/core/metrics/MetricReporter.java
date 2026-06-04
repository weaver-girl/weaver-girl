package com.github.cc11001100.weavergirl.core.metrics;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Supplier;

/**
 * Periodic metric collector and reporter.
 *
 * <p>Collects named metric values from registered suppliers at a configurable
 * interval and outputs them through pluggable formatters.</p>
 *
 * <p>Features:</p>
 * <ul>
 *   <li>Multiple metric suppliers registered by name</li>
 *   <li>Pluggable output formatters (JSON, Prometheus, Log)</li>
 *   <li>Configurable collection interval</li>
 *   <li>Scheduled daemon thread execution</li>
 *   <li>Graceful start/stop lifecycle</li>
 *   <li>Metric history with bounded retention</li>
 * </ul>
 */
public class MetricReporter {

    private static final Logger log = LoggerFactory.getLogger(MetricReporter.class);

    private static final long DEFAULT_INTERVAL_MS = 60_000;
    private static final int DEFAULT_HISTORY_SIZE = 60;

    private final long intervalMs;
    private final int historySize;
    private final Map<String, Supplier<Object>> suppliers;
    private final List<MetricFormatter> formatters;
    private final List<MetricSnapshot> history;
    private final ScheduledExecutorService scheduler;
    private final AtomicBoolean running;
    private final AtomicLong reportCount;

    private ScheduledFuture<?> scheduledTask;

    public MetricReporter() {
        this(DEFAULT_INTERVAL_MS, DEFAULT_HISTORY_SIZE);
    }

    public MetricReporter(long intervalMs, int historySize) {
        this.intervalMs = intervalMs > 0 ? intervalMs : DEFAULT_INTERVAL_MS;
        this.historySize = historySize > 0 ? historySize : DEFAULT_HISTORY_SIZE;
        this.suppliers = new ConcurrentHashMap<>();
        this.formatters = new CopyOnWriteArrayList<>();
        this.history = new CopyOnWriteArrayList<>();
        this.running = new AtomicBoolean(false);
        this.reportCount = new AtomicLong(0);
        this.scheduler = Executors.newSingleThreadScheduledExecutor(r -> {
            Thread t = new Thread(r, "weaver-girl-metric-reporter");
            t.setDaemon(true);
            return t;
        });
    }

    // --- Registration ---

    /**
     * Register a metric supplier by name.
     */
    public MetricReporter registerMetric(String name, Supplier<Object> supplier) {
        suppliers.put(name, supplier);
        return this;
    }

    /**
     * Unregister a metric supplier.
     */
    public MetricReporter unregisterMetric(String name) {
        suppliers.remove(name);
        return this;
    }

    /**
     * Add a metric formatter.
     */
    public MetricReporter addFormatter(MetricFormatter formatter) {
        formatters.add(formatter);
        return this;
    }

    /**
     * Remove a metric formatter.
     */
    public MetricReporter removeFormatter(MetricFormatter formatter) {
        formatters.remove(formatter);
        return this;
    }

    // --- Lifecycle ---

    /**
     * Start periodic metric reporting.
     */
    public void start() {
        if (running.compareAndSet(false, true)) {
            scheduledTask = scheduler.scheduleAtFixedRate(
                    this::collectAndReport,
                    intervalMs, intervalMs, TimeUnit.MILLISECONDS);
            log.info("[MetricReporter] Started (interval: {}ms, metrics: {}, formatters: {})",
                    intervalMs, suppliers.size(), formatters.size());
        }
    }

    /**
     * Stop periodic metric reporting.
     */
    public void stop() {
        if (running.compareAndSet(true, false)) {
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
            log.info("[MetricReporter] Stopped (reports: {})", reportCount.get());
        }
    }

    /**
     * Manually trigger a single collection and report.
     */
    public MetricSnapshot collectAndReport() {
        Map<String, Object> values = new LinkedHashMap<>();
        long timestamp = System.currentTimeMillis();

        for (Map.Entry<String, Supplier<Object>> entry : suppliers.entrySet()) {
            try {
                Object value = entry.getValue().get();
                values.put(entry.getKey(), value != null ? value : "null");
            } catch (Exception e) {
                values.put(entry.getKey(), "error:" + e.getMessage());
                log.debug("[MetricReporter] Failed to collect metric {}: {}", entry.getKey(), e.getMessage());
            }
        }

        MetricSnapshot snapshot = new MetricSnapshot(timestamp, values);

        // Add to history (bounded)
        history.add(snapshot);
        while (history.size() > historySize) {
            history.remove(0);
        }

        // Report through formatters
        for (MetricFormatter formatter : formatters) {
            try {
                formatter.format(snapshot);
            } catch (Exception e) {
                log.warn("[MetricReporter] Formatter {} failed: {}", formatter.getClass().getSimpleName(), e.getMessage());
            }
        }

        reportCount.incrementAndGet();
        return snapshot;
    }

    // --- Accessors ---

    public boolean isRunning() { return running.get(); }
    public long getReportCount() { return reportCount.get(); }
    public long getIntervalMs() { return intervalMs; }
    public int getMetricCount() { return suppliers.size(); }
    public int getFormatterCount() { return formatters.size(); }
    public Set<String> getMetricNames() { return Collections.unmodifiableSet(suppliers.keySet()); }

    /**
     * Get the latest metric snapshot, or null if no reports have been made.
     */
    public MetricSnapshot getLatestSnapshot() {
        return history.isEmpty() ? null : history.get(history.size() - 1);
    }

    /**
     * Get metric history (newest first).
     */
    public List<MetricSnapshot> getHistory() {
        List<MetricSnapshot> reversed = new ArrayList<>(history);
        Collections.reverse(reversed);
        return Collections.unmodifiableList(reversed);
    }

    /**
     * Get the history size limit.
     */
    public int getHistorySize() { return historySize; }
}
