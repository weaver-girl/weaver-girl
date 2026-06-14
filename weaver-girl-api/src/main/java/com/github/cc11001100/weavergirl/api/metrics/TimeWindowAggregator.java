package com.github.cc11001100.weavergirl.api.metrics;

import java.util.*;

/**
 * Aggregates numeric values over a sliding time window.
 *
 * <p>Thread-safe. Supports count, sum, min, max, avg, and percentile calculations.</p>
 *
 * <h3>Usage:</h3>
 * <pre>
 * TimeWindowAggregator agg = new TimeWindowAggregator("request_duration_ms", 60000);
 * agg.record(150);
 * agg.record(200);
 * agg.record(350);
 *
 * MetricSnapshot snap = agg.snapshot();
 * System.out.println("p99=" + snap.getPercentile(0.99));
 * </pre>
 *
 * @since 1.2.0
 */
public class TimeWindowAggregator {

    private final String name;
    private final long windowMs;
    private final LinkedList<TimestampedValue> values = new LinkedList<>();

    /**
     * Create an aggregator.
     *
     * @param name     metric name
     * @param windowMs time window in milliseconds
     */
    public TimeWindowAggregator(String name, long windowMs) {
        this.name = name;
        this.windowMs = Math.max(1000, windowMs);
    }

    /**
     * Record a value.
     *
     * @param value the metric value
     */
    public synchronized void record(double value) {
        long now = System.currentTimeMillis();
        values.add(new TimestampedValue(now, value));
        evictOld(now);
    }

    /**
     * Take a snapshot of current aggregated values.
     *
     * @return metric snapshot
     */
    public synchronized MetricSnapshot snapshot() {
        long now = System.currentTimeMillis();
        evictOld(now);

        long count = values.size();
        if (count == 0) {
            return new MetricSnapshot(now - windowMs, now, name, 0, 0,
                    Double.MAX_VALUE, Double.MIN_VALUE, Collections.emptyMap());
        }

        double sum = 0, min = Double.MAX_VALUE, max = Double.MIN_VALUE;
        double[] sorted = new double[values.size()];
        int i = 0;
        for (TimestampedValue tv : values) {
            sum += tv.value;
            if (tv.value < min) min = tv.value;
            if (tv.value > max) max = tv.value;
            sorted[i++] = tv.value;
        }
        Arrays.sort(sorted);

        Map<Double, Double> percentiles = new LinkedHashMap<>();
        percentiles.put(0.5, percentile(sorted, 0.5));
        percentiles.put(0.9, percentile(sorted, 0.9));
        percentiles.put(0.95, percentile(sorted, 0.95));
        percentiles.put(0.99, percentile(sorted, 0.99));

        long windowStart = values.isEmpty() ? now - windowMs : values.getFirst().timestamp;

        return new MetricSnapshot(windowStart, now, name, count, sum, min, max, percentiles);
    }

    /**
     * Get the metric name.
     */
    public String getName() {
        return name;
    }

    /**
     * Get the time window in milliseconds.
     */
    public long getWindowMs() {
        return windowMs;
    }

    /**
     * Get current value count in the window.
     */
    public synchronized int size() {
        return values.size();
    }

    /**
     * Clear all values.
     */
    public synchronized void clear() {
        values.clear();
    }

    private void evictOld(long now) {
        long cutoff = now - windowMs;
        while (!values.isEmpty() && values.getFirst().timestamp < cutoff) {
            values.removeFirst();
        }
    }

    private double percentile(double[] sorted, double p) {
        if (sorted.length == 0) return 0;
        if (sorted.length == 1) return sorted[0];
        double index = p * (sorted.length - 1);
        int lower = (int) Math.floor(index);
        int upper = (int) Math.ceil(index);
        if (lower == upper) return sorted[lower];
        return sorted[lower] + (index - lower) * (sorted[upper] - sorted[lower]);
    }

    private static class TimestampedValue {
        final long timestamp;
        final double value;

        TimestampedValue(long timestamp, double value) {
            this.timestamp = timestamp;
            this.value = value;
        }
    }
}
