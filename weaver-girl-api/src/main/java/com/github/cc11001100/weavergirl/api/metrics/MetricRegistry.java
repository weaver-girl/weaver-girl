package com.github.cc11001100.weavergirl.api.metrics;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Registry of named metric aggregators.
 *
 * @since 1.2.0
 */
public final class MetricRegistry {

    private static final ConcurrentHashMap<String, TimeWindowAggregator> aggregators = new ConcurrentHashMap<>();

    /** Default 1-minute window. */
    public static final long WINDOW_1MIN = 60000;
    /** Default 5-minute window. */
    public static final long WINDOW_5MIN = 300000;
    /** Default 1-hour window. */
    public static final long WINDOW_1HOUR = 3600000;

    private MetricRegistry() {
    }

    /**
     * Get or create an aggregator with the default 1-minute window.
     *
     * @param name metric name
     * @return the aggregator
     */
    public static TimeWindowAggregator getOrCreate(String name) {
        return getOrCreate(name, WINDOW_1MIN);
    }

    /**
     * Get or create an aggregator with a custom window.
     *
     * @param name     metric name
     * @param windowMs time window in milliseconds
     * @return the aggregator
     */
    public static TimeWindowAggregator getOrCreate(String name, long windowMs) {
        return aggregators.computeIfAbsent(name, n -> new TimeWindowAggregator(n, windowMs));
    }

    /**
     * Record a value to a named metric.
     *
     * @param name  metric name
     * @param value the value
     */
    public static void record(String name, double value) {
        getOrCreate(name).record(value);
    }

    /**
     * Take a snapshot of a named metric.
     *
     * @param name metric name
     * @return snapshot, or null if metric doesn't exist
     */
    public static MetricSnapshot snapshot(String name) {
        TimeWindowAggregator agg = aggregators.get(name);
        return agg != null ? agg.snapshot() : null;
    }

    /**
     * Take snapshots of all metrics.
     *
     * @return map of metric name to snapshot
     */
    public static Map<String, MetricSnapshot> snapshotAll() {
        Map<String, MetricSnapshot> result = new LinkedHashMap<>();
        for (Map.Entry<String, TimeWindowAggregator> entry : aggregators.entrySet()) {
            result.put(entry.getKey(), entry.getValue().snapshot());
        }
        return result;
    }

    /**
     * Get all registered metric names.
     */
    public static Set<String> getMetricNames() {
        return Collections.unmodifiableSet(aggregators.keySet());
    }

    /**
     * Remove a metric.
     */
    public static void remove(String name) {
        aggregators.remove(name);
    }

    /**
     * Clear all metrics.
     */
    public static void clear() {
        aggregators.clear();
    }

    /**
     * Get registered metric count.
     */
    public static int size() {
        return aggregators.size();
    }
}
