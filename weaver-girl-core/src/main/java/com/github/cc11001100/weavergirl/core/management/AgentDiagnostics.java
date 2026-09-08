package com.github.cc11001100.weavergirl.core.management;

import java.lang.management.*;
import java.util.*;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Agent self-diagnostics: memory trends, interceptor hotspots, fault detection.
 *
 * <p>Provides deep insight into agent health beyond basic metrics.
 *
 * @since 1.1.0
 */
public class AgentDiagnostics {

  private static final Logger log = LoggerFactory.getLogger(AgentDiagnostics.class);
  private static final AgentDiagnostics INSTANCE = new AgentDiagnostics();

  private static final int MAX_MEMORY_HISTORY = 60;
  private static final int MAX_HOTSPOT_ENTRIES = 20;

  private final LinkedList<MemorySnapshot> memoryHistory = new LinkedList<>();
  private final LinkedHashMap<String, InterceptorHotspot> hotspots = new LinkedHashMap<>();
  private final LinkedList<FaultRecord> faultLog = new LinkedList<>();
  private final long startTime;

  private AgentDiagnostics() {
    this.startTime = System.currentTimeMillis();
  }

  public static AgentDiagnostics getInstance() {
    return INSTANCE;
  }

  // ===== Memory tracking =====

  /** Record a memory snapshot. */
  public void recordMemorySnapshot() {
    MemoryMXBean memBean = ManagementFactory.getMemoryMXBean();
    MemoryUsage heap = memBean.getHeapMemoryUsage();
    MemoryUsage nonHeap = memBean.getNonHeapMemoryUsage();

    Runtime runtime = Runtime.getRuntime();
    MemorySnapshot snap =
        new MemorySnapshot(
            System.currentTimeMillis(),
            heap.getUsed(),
            heap.getMax(),
            heap.getCommitted(),
            nonHeap.getUsed(),
            runtime.totalMemory(),
            runtime.freeMemory(),
            Thread.activeCount());

    synchronized (memoryHistory) {
      memoryHistory.add(snap);
      while (memoryHistory.size() > MAX_MEMORY_HISTORY) {
        memoryHistory.removeFirst();
      }
    }

    // Auto-detect memory pressure
    double heapUsageRatio = heap.getMax() > 0 ? (double) heap.getUsed() / heap.getMax() : 0;
    if (heapUsageRatio > 0.9) {
      recordFault(
          "MEMORY_PRESSURE",
          String.format(
              "Heap usage at %.1f%% (%,d / %,d bytes)",
              heapUsageRatio * 100, heap.getUsed(), heap.getMax()));
    }
  }

  /** Get memory usage history. */
  public List<MemorySnapshot> getMemoryHistory() {
    synchronized (memoryHistory) {
      return Collections.unmodifiableList(new ArrayList<>(memoryHistory));
    }
  }

  /** Get memory usage trend: positive = growing, negative = shrinking. */
  public double getMemoryTrend() {
    synchronized (memoryHistory) {
      if (memoryHistory.size() < 2) return 0.0;
      MemorySnapshot first = memoryHistory.getFirst();
      MemorySnapshot last = memoryHistory.getLast();
      long durationMs = last.timestamp - first.timestamp;
      if (durationMs == 0) return 0.0;
      return (double) (last.heapUsed - first.heapUsed) / durationMs * 1000; // bytes/sec
    }
  }

  // ===== Interceptor hotspots =====

  /**
   * Record an interceptor invocation for hotspot analysis.
   *
   * @param interceptorName the interceptor name
   * @param durationNanos time taken in nanoseconds
   */
  public void recordInterceptorInvocation(String interceptorName, long durationNanos) {
    synchronized (hotspots) {
      InterceptorHotspot hotspot =
          hotspots.computeIfAbsent(interceptorName, k -> new InterceptorHotspot(k));
      hotspot.recordInvocation(durationNanos);

      // Trim if too large
      if (hotspots.size() > MAX_HOTSPOT_ENTRIES) {
        // Remove entry with lowest total time
        String lowest = null;
        long lowestTime = Long.MAX_VALUE;
        for (Map.Entry<String, InterceptorHotspot> entry : hotspots.entrySet()) {
          if (entry.getValue().totalTimeNanos < lowestTime) {
            lowestTime = entry.getValue().totalTimeNanos;
            lowest = entry.getKey();
          }
        }
        if (lowest != null) {
          hotspots.remove(lowest);
        }
      }
    }
  }

  /** Get interceptor hotspots sorted by total time (descending). */
  public List<InterceptorHotspot> getTopHotspots(int count) {
    synchronized (hotspots) {
      List<InterceptorHotspot> sorted = new ArrayList<>(hotspots.values());
      sorted.sort((a, b) -> Long.compare(b.totalTimeNanos, a.totalTimeNanos));
      return Collections.unmodifiableList(sorted.subList(0, Math.min(count, sorted.size())));
    }
  }

  /** Get all tracked interceptor hotspots. */
  public List<InterceptorHotspot> getAllHotspots() {
    return getTopHotspots(MAX_HOTSPOT_ENTRIES);
  }

  // ===== Fault detection =====

  /**
   * Record a detected fault.
   *
   * @param type fault type (e.g. "MEMORY_PRESSURE", "SLOW_INTERCEPTOR")
   * @param message human-readable description
   */
  public void recordFault(String type, String message) {
    synchronized (faultLog) {
      FaultRecord record = new FaultRecord(System.currentTimeMillis(), type, message);
      faultLog.add(record);
      while (faultLog.size() > 100) {
        faultLog.removeFirst();
      }
      log.warn("[AgentDiagnostics] Fault detected: {} - {}", type, message);
    }
  }

  /** Get all recorded faults. */
  public List<FaultRecord> getFaults() {
    synchronized (faultLog) {
      return Collections.unmodifiableList(new ArrayList<>(faultLog));
    }
  }

  /** Get recent faults (last N). */
  public List<FaultRecord> getRecentFaults(int count) {
    synchronized (faultLog) {
      int from = Math.max(0, faultLog.size() - count);
      return Collections.unmodifiableList(new ArrayList<>(faultLog.subList(from, faultLog.size())));
    }
  }

  /** Check if any faults have been recorded. */
  public boolean hasFaults() {
    synchronized (faultLog) {
      return !faultLog.isEmpty();
    }
  }

  // ===== Diagnostics report =====

  /** Generate a comprehensive diagnostics report. */
  public String generateReport() {
    StringBuilder sb = new StringBuilder();
    sb.append("=== Weaver-Girl Agent Diagnostics Report ===\n\n");

    // Uptime
    long uptimeMs = System.currentTimeMillis() - startTime;
    sb.append(String.format("Uptime: %,.1f seconds\n", uptimeMs / 1000.0));

    // Memory
    recordMemorySnapshot();
    Runtime runtime = Runtime.getRuntime();
    long usedMB = (runtime.totalMemory() - runtime.freeMemory()) / 1024 / 1024;
    long maxMB = runtime.maxMemory() / 1024 / 1024;
    sb.append(
        String.format(
            "Memory: %d MB / %d MB (%.1f%%)\n",
            usedMB, maxMB, maxMB > 0 ? (double) usedMB / maxMB * 100 : 0));
    sb.append(String.format("Memory trend: %+.2f MB/sec\n", getMemoryTrend() / 1024 / 1024));
    sb.append(String.format("Threads: %d active\n\n", Thread.activeCount()));

    // Hotspots
    List<InterceptorHotspot> top = getTopHotspots(5);
    if (!top.isEmpty()) {
      sb.append("Top 5 Interceptor Hotspots:\n");
      for (int i = 0; i < top.size(); i++) {
        InterceptorHotspot h = top.get(i);
        sb.append(
            String.format(
                "  %d. %s — %d calls, avg %.3fms, total %.1fms\n",
                i + 1, h.name, h.invocationCount, h.getAverageTimeMs(), h.getTotalTimeMs()));
      }
      sb.append("\n");
    }

    // Faults
    List<FaultRecord> faults = getRecentFaults(10);
    if (!faults.isEmpty()) {
      sb.append(String.format("Recent Faults (%d total):\n", faultLog.size()));
      for (FaultRecord f : faults) {
        sb.append(String.format("  [%s] %s: %s\n", f.type, f.timestamp, f.message));
      }
    } else {
      sb.append("No faults detected.\n");
    }

    sb.append("\n=== End Report ===");
    return sb.toString();
  }

  // ===== Inner classes =====

  /** Point-in-time memory snapshot. */
  public static class MemorySnapshot {
    public final long timestamp;
    public final long heapUsed;
    public final long heapMax;
    public final long heapCommitted;
    public final long nonHeapUsed;
    public final long totalMemory;
    public final long freeMemory;
    public final int activeThreads;

    MemorySnapshot(
        long timestamp,
        long heapUsed,
        long heapMax,
        long heapCommitted,
        long nonHeapUsed,
        long totalMemory,
        long freeMemory,
        int activeThreads) {
      this.timestamp = timestamp;
      this.heapUsed = heapUsed;
      this.heapMax = heapMax;
      this.heapCommitted = heapCommitted;
      this.nonHeapUsed = nonHeapUsed;
      this.totalMemory = totalMemory;
      this.freeMemory = freeMemory;
      this.activeThreads = activeThreads;
    }
  }

  /** Interceptor performance hotspot tracking. */
  public static class InterceptorHotspot {
    public final String name;
    public long invocationCount;
    public long totalTimeNanos;
    public long maxTimeNanos;

    InterceptorHotspot(String name) {
      this.name = name;
    }

    void recordInvocation(long durationNanos) {
      invocationCount++;
      totalTimeNanos += durationNanos;
      if (durationNanos > maxTimeNanos) {
        maxTimeNanos = durationNanos;
      }
    }

    /** Average time per invocation in milliseconds. */
    public double getAverageTimeMs() {
      return invocationCount > 0 ? (double) totalTimeNanos / invocationCount / 1_000_000 : 0;
    }

    /** Total time in milliseconds. */
    public double getTotalTimeMs() {
      return (double) totalTimeNanos / 1_000_000;
    }

    /** Max single invocation time in milliseconds. */
    public double getMaxTimeMs() {
      return (double) maxTimeNanos / 1_000_000;
    }
  }

  /** A recorded fault or anomaly. */
  public static class FaultRecord {
    public final long timestamp;
    public final String type;
    public final String message;

    FaultRecord(long timestamp, String type, String message) {
      this.timestamp = timestamp;
      this.type = type;
      this.message = message;
    }
  }
}
