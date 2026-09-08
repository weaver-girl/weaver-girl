package com.github.cc11001100.weavergirl.core.health;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Comprehensive health check subsystem providing component-level health status.
 *
 * <p>Inspired by Spring Boot's HealthIndicator pattern. Each component registers a health check
 * that is evaluated on demand to produce a detailed health report.
 *
 * <p>Components:
 *
 * <ul>
 *   <li>{@link HealthIndicator} — functional interface for component health checks
 *   <li>{@link HealthStatus} — health result with status, details, and timestamp
 *   <li>{@link CompositeHealthIndicator} — registry and aggregator
 * </ul>
 *
 * <p>Usage:
 *
 * <pre>
 * CompositeHealthIndicator registry = CompositeHealthIndicator.getInstance();
 * registry.register("memory", () -> {
 *     Runtime rt = Runtime.getRuntime();
 *     long used = rt.totalMemory() - rt.freeMemory();
 *     long max = rt.maxMemory();
 *     double pct = (double) used / max * 100;
 *     return HealthStatus.builder()
 *         .status(pct > 90 ? "DOWN" : "UP")
 *         .detail("heapUsedMB", used / 1024 / 1024)
 *         .detail("heapMaxMB", max / 1024 / 1024)
 *         .detail("heapUsagePercent", String.format("%.1f%%", pct))
 *         .build();
 * });
 * HealthReport report = registry.check();
 * </pre>
 */
public final class CompositeHealthIndicator {

  private static final Logger log = LoggerFactory.getLogger(CompositeHealthIndicator.class);

  private static final CompositeHealthIndicator INSTANCE = new CompositeHealthIndicator();

  private final Map<String, HealthIndicator> indicators = new ConcurrentHashMap<>();

  private CompositeHealthIndicator() {}

  /** Get the singleton instance. */
  public static CompositeHealthIndicator getInstance() {
    return INSTANCE;
  }

  /** Register a health indicator for a named component. */
  public void register(String name, HealthIndicator indicator) {
    indicators.put(name, indicator);
    log.debug("[Health] Registered indicator: {}", name);
  }

  /** Unregister a health indicator. */
  public void unregister(String name) {
    indicators.remove(name);
  }

  /** Run all registered health checks and produce a composite report. */
  public HealthReport check() {
    Map<String, HealthStatus> components = new LinkedHashMap<>();
    long startMs = System.currentTimeMillis();

    for (Map.Entry<String, HealthIndicator> entry : indicators.entrySet()) {
      String name = entry.getKey();
      try {
        HealthStatus status = entry.getValue().check();
        components.put(name, status);
      } catch (Exception e) {
        components.put(
            name,
            HealthStatus.builder()
                .status("DOWN")
                .detail("error", e.getClass().getSimpleName() + ": " + e.getMessage())
                .build());
        log.warn("[Health] Check failed for {}: {}", name, e.getMessage());
      }
    }

    long durationMs = System.currentTimeMillis() - startMs;

    // Determine overall status: DOWN if any component is DOWN, DEGRADED if any is DEGRADED
    String overallStatus = "UP";
    for (HealthStatus status : components.values()) {
      if ("DOWN".equals(status.getStatus())) {
        overallStatus = "DOWN";
        break;
      }
      if ("DEGRADED".equals(status.getStatus())) {
        overallStatus = "DEGRADED";
      }
    }

    return new HealthReport(overallStatus, components, durationMs);
  }

  /** Check health of a specific component. */
  public HealthStatus checkComponent(String name) {
    HealthIndicator indicator = indicators.get(name);
    if (indicator == null) {
      return HealthStatus.builder()
          .status("UNKNOWN")
          .detail("message", "No indicator: " + name)
          .build();
    }
    try {
      return indicator.check();
    } catch (Exception e) {
      return HealthStatus.builder().status("DOWN").detail("error", e.getMessage()).build();
    }
  }

  /** Get the names of all registered components. */
  public Set<String> getComponentNames() {
    return Collections.unmodifiableSet(indicators.keySet());
  }

  /** Get the number of registered indicators. */
  public int size() {
    return indicators.size();
  }
}
