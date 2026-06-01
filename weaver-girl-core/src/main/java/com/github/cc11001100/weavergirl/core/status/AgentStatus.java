package com.github.cc11001100.weavergirl.core.status;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Runtime status of the weaver-girl agent.
 * Tracks transformation counts, error counts, active plugins, and custom metrics.
 *
 * <p>All methods are thread-safe. This class is a singleton — obtain via
 * {@link #getInstance()}.</p>
 */
public class AgentStatus {

    private static final AgentStatus INSTANCE = new AgentStatus();

    private final AtomicLong transformationCount = new AtomicLong(0);
    private final AtomicLong transformationErrorCount = new AtomicLong(0);
    private final AtomicLong interceptorInvocationCount = new AtomicLong(0);
    private final AtomicLong interceptorErrorCount = new AtomicLong(0);
    private final ConcurrentHashMap<String, String> customMetrics = new ConcurrentHashMap<>();
    private final CopyOnWriteArrayList<String> transformedClasses = new CopyOnWriteArrayList<>();

    private volatile long startTime = System.currentTimeMillis();
    private volatile int activePluginCount = 0;
    private volatile int registeredInterceptorCount = 0;

    private AgentStatus() {}

    public static AgentStatus getInstance() {
        return INSTANCE;
    }

    // --- Counters ---

    public long incrementTransformationCount() {
        return transformationCount.incrementAndGet();
    }

    public long incrementTransformationErrorCount() {
        return transformationErrorCount.incrementAndGet();
    }

    public long incrementInterceptorInvocationCount() {
        return interceptorInvocationCount.incrementAndGet();
    }

    public long incrementInterceptorErrorCount() {
        return interceptorErrorCount.incrementAndGet();
    }

    // --- Setters ---

    public void setActivePluginCount(int count) {
        this.activePluginCount = count;
    }

    public void setRegisteredInterceptorCount(int count) {
        this.registeredInterceptorCount = count;
    }

    public void putCustomMetric(String key, String value) {
        customMetrics.put(key, value);
    }

    public void addTransformedClass(String className) {
        if (transformedClasses.size() < 10000) { // prevent unbounded growth
            transformedClasses.add(className);
        }
    }

    public List<String> getTransformedClasses() {
        return Collections.unmodifiableList(new ArrayList<>(transformedClasses));
    }

    // --- Getters ---

    public long getTransformationCount() { return transformationCount.get(); }
    public long getTransformationErrorCount() { return transformationErrorCount.get(); }
    public long getInterceptorInvocationCount() { return interceptorInvocationCount.get(); }
    public long getInterceptorErrorCount() { return interceptorErrorCount.get(); }
    public int getActivePluginCount() { return activePluginCount; }
    public int getRegisteredInterceptorCount() { return registeredInterceptorCount; }
    public Map<String, String> getCustomMetrics() { return Collections.unmodifiableMap(customMetrics); }

    public long getUptimeSeconds() {
        return (System.currentTimeMillis() - startTime) / 1000;
    }

    /**
     * Generate a human-readable status report.
     */
    public String getReport() {
        StringBuilder sb = new StringBuilder();
        sb.append("=== Weaver-Girl Agent Status ===\n");
        sb.append("Uptime: ").append(getUptimeSeconds()).append("s\n");
        sb.append("Active Plugins: ").append(activePluginCount).append("\n");
        sb.append("Registered Interceptors: ").append(registeredInterceptorCount).append("\n");
        sb.append("Transformations: ").append(transformationCount.get())
          .append(" (errors: ").append(transformationErrorCount.get()).append(")\n");
        sb.append("Interceptor Invocations: ").append(interceptorInvocationCount.get())
          .append(" (errors: ").append(interceptorErrorCount.get()).append(")\n");
        if (!customMetrics.isEmpty()) {
            sb.append("Custom Metrics:\n");
            customMetrics.forEach((k, v) -> sb.append("  ").append(k).append(": ").append(v).append("\n"));
        }
        sb.append("================================");
        return sb.toString();
    }

    /**
     * Reset all counters. Useful for testing.
     */
    public void reset() {
        transformationCount.set(0);
        transformationErrorCount.set(0);
        interceptorInvocationCount.set(0);
        interceptorErrorCount.set(0);
        activePluginCount = 0;
        registeredInterceptorCount = 0;
        customMetrics.clear();
        transformedClasses.clear();
        startTime = System.currentTimeMillis();
    }
}
